# ベクトル検索（意味で探す検索）

キーワード検索は、質問の語が頁に書かれていないと見つけられない。「how to run http server on python without extra
dependency」に対して、Python の `http.server` の説明は「without」「extra」「dependency」を含まないので、最小一致
（5 語を超える質問では 80%）で候補から外れていた。ベクトル検索は、質問と文書の意味の近さで探すので、言い回しが
違っても見つかる。英語の質問で日本語の頁も見つかる（多言語のモデルのため）。

## 構成

```
質問 ──▶ YaCy ──▶ キーワード検索（Solr edismax、今まで通り）──┐
              └──▶ 質問の埋め込み ──▶ Solr knn（embedding_knn）──┴─▶ RRF で合わせる ──▶ 順位付け（作者の判定、被覆率など）

埋め込みの係（VectorIndexer）: 埋め込みの無い文書を探す ──▶ 埋め込みのサービス ──▶ 文書に書き足す（atomic update）
```

- **埋め込みのサービス**: OpenAI 互換の `POST /v1/embeddings`。既定は `embeddings/` のイメージ
  （`ghcr.io/pad01g/yacy-embeddings`、intfloat/multilingual-e5-small、384 次元、CPU、メモリ 1 GB ほど）。Ollama や
  text-embeddings-inference など、同じ API で 384 次元を返すものなら何でもよい。YaCy の外に置くのは、モデルを
  差し替えやすくし、YaCy の Java のメモリとモデルのメモリを分けるため。
- **Solr の項目**: `embedding_knn`（`DenseVectorField`、384 次元、cosine、HNSW）と `embedding_model_s`（埋め込んだ
  モデルの名前）。YaCy は起動のたびに `defaults/solr/schema.xml` を core に書き直すので、既存のピアにも入る。
- **埋め込みの係**: `embedding_model_s` が今のモデルでない文書を 64 件ずつ探し、16 件ずつ埋め込む。新しく crawl した
  頁も、既存の索引も、モデルを変えたときの埋め直しも同じ仕組み。文書を表す文は、タイトル・説明・本文の先頭
  （合わせて `search.vector.passageChars` 字、既定 1500）に、e5 の約束の `passage: ` を付けたもの。
- **書き足し方**: 読み出したときの `_version_` を付けた atomic update。その間に文書が消えたり crawl し直されたり
  していれば Solr が断り、文書は変わらない（後でまた拾う）。YaCy の書き込みの層（失敗すると文書を消して
  入れ直す）は通らない。そこを通ると、部分的な更新の失敗で文書が ID とベクトルだけになりうるため。
- **検索**: ローカルの検索の最初のページで、キーワードの Solr の問い合わせに続けて、同じ絞り込み（`fq`）と項目で
  knn の問い合わせ（上位 `search.vector.topK` 件、既定 50）をする。2 つの順位を Reciprocal Rank Fusion で合わせる:
  順位 r の文書に 1 / (60 + r)、ベクトルの一覧は `search.vector.weight`（既定 1.0）倍。両方に入った文書は足し合わ
  される。合わせた値が Solr の点数の代わりになり、その後の順位付け（作者の署名の判定、被覆率、薄い頁の重み）は
  今まで通り。キーワードに引っかからずベクトルだけで入った文書には `keyword_match_b=false` が付く。
- **失敗したとき**: 埋め込みのサービスが答えなければ、キーワード検索だけで答える（30 秒は問い合わせない）。

## 設定

| キー | 既定 | 意味 |
|---|---|---|
| `search.vector.enabled` | `false` | ベクトル検索と埋め込みの係を有効にする |
| `search.vector.embeddings.url` | `http://127.0.0.1:8095/v1/embeddings` | 埋め込みのサービス |
| `search.vector.model` | `intfloat/multilingual-e5-small` | モデルの名前（サービスに渡し、文書に記録する。変えると全文書を埋め直す） |
| `search.vector.queryPrefix` / `passagePrefix` | `query:` / `passage:` | e5 の約束の接頭辞（空白は YaCy が足す）。他のモデルでは空にする |
| `search.vector.passageChars` | `1500` | 文書を表す文の長さ |
| `search.vector.topK` | `50` | knn で取る文書の数 |
| `search.vector.weight` | `1.0` | RRF でのベクトルの一覧の重み（0 にすると、キーワードの結果の後ろに足すだけ） |

Docker では `-e YACY_SEARCH_VECTOR_ENABLED=true -e YACY_SEARCH_VECTOR_EMBEDDINGS_URL=http://<サービス>:8095/v1/embeddings`。
例は yacy-example の `docs-peer/start.sh`。

## 既知の制約

- **P2P には広がらない**: ベクトルを使うのは、このピアのローカルの索引の検索だけ。他のピアへの Solr の問い合わせ
  （キーワード）と DHT の検索は今まで通り。他のピアが同じモデルを使っているとは限らないので、質問のベクトルを
  送る拡張は、モデルを seed で宣言する仕組みと合わせて後の段階で考える。
- 1 文書に 1 つのベクトル（Solr の DenseVectorField は 1 値）。長い頁は先頭の 1500 字で表すので、頁の後ろの方の
  話題には弱い。頁を段落に分けて別の文書にする方法は後の段階。
- 次元は schema に 384 で固定。別の次元のモデルを使うには `knn_vector_384` を変え、全文書を埋め直す。
- 最初のページだけ合わせる。2 ページ目以降はキーワードの結果。
- 一括の埋め込みの最中は、埋め込みのサービスが 1 件ずつしか処理しないので、新しい質問の埋め込みが数秒待つ
  ことがある。
- 被覆率の重みは、ベクトルだけで入った文書（質問の語を一部しか含まない）を下げる方に働く。
