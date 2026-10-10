# ベクトル検索（意味で探す検索）

キーワード検索は、質問の語が頁に書かれていないと見つけられない。「how to run http server on python without extra
dependency」に対して、Python の `http.server` の説明は「without」「extra」「dependency」を含まないので、最小一致
（5 語を超える質問では 80%）で候補から外れていた。ベクトル検索は、質問と文書の意味の近さで探すので、言い回しが
違っても見つかる。英語の質問で日本語の頁も見つかる（多言語のモデルのため）。

## 構成

```
質問 ──▶ YaCy ──▶ キーワード検索（Solr edismax、今まで通り）─────────────────┐
              └──▶ 質問の埋め込み ──▶ Solr knn（embedding_knn と節の embedding_knn_1, _2）┴─▶ 点数で合わせる ──▶ 順位付け（作者の判定、被覆率など）

埋め込みの係（VectorIndexer）: 埋め込みの無い文書を探す ──▶ 埋め込みのサービス ──▶ 文書に書き足す（atomic update）
```

- **埋め込みのサービス**: OpenAI 互換の `POST /v1/embeddings`。既定は `embeddings/` のイメージ
  （`ghcr.io/pad01g/yacy-embeddings`、intfloat/multilingual-e5-small、384 次元、CPU、メモリ 1 GB ほど）。Ollama や
  text-embeddings-inference など、同じ API で 384 次元を返すものなら何でもよい。YaCy の外に置くのは、モデルを
  差し替えやすくし、YaCy の Java のメモリとモデルのメモリを分けるため。
- **Solr の項目**: `embedding_knn`（`DenseVectorField`、384 次元、cosine、HNSW）と `embedding_model_s`（埋め込んだ
  モデルの名前）。YaCy は起動のたびに `defaults/solr/schema.xml` を core に書き直すので、既存のピアにも入る。
- **埋め込みの係**: `embedding_model_s` が今の設定（モデルと節の数、例 `intfloat/multilingual-e5-small sections=2`）で
  ない文書を探し、1 回の要求に 32 文までまとめて埋め込む。新しく crawl した頁も、既存の索引も、モデルや節の数を
  変えたときの埋め直しも同じ仕組み。文書を表す文は、タイトル・説明・本文の先頭（合わせて
  `search.vector.passageChars` 字、既定 1500）に、e5 の約束の `passage: ` を付けたもの。
- **節の埋め込み**（`search.vector.sections`、既定 0 = 使わない）: 長い頁は、先頭の後の本文を見出し（パーサーが `# ` で書く）で
  区切って最大 1500 字の節にし、タイトルを前に付けて `embedding_knn_1`、`embedding_knn_2` に埋め込む。頁の中ほどの
  話題（asyncio の頁の `gather` など）にも届く。検索では節ごとに knn を問い合わせ、頁ごとに最も近い値を使う。
  Solr の DenseVectorField は 1 項目に 1 ベクトルなので、項目を分けている。docs-peer の質問では、正解の順位は
  良くならず（調整用 0.668 / 0.667、確認用 0.615 / 0.641）、検索の時間が倍になったので、既定では使わない。
- **書き足し方**: 読み出したときの `_version_` を付けた atomic update。その間に文書が消えたり crawl し直されたり
  していれば Solr が断り、文書は変わらない（後でまた拾う）。YaCy の書き込みの層（失敗すると文書を消して
  入れ直す）は通らない。そこを通ると、部分的な更新の失敗で文書が ID とベクトルだけになりうるため。
- **検索**: ローカルの検索の最初のページで、キーワードの Solr の問い合わせに続けて、同じ絞り込み（`fq`）と項目で
  knn の問い合わせ（上位 `search.vector.topK` 件、既定 50）をする。合わせ方は `search.vector.fusion`:
  - `score`（既定）: キーワードの点数を最高点で割った値 ＋ `search.vector.weight` × 類似度（knn の上位の中で
    0〜1 に引き伸ばしたもの）。
  - `abs`: 類似度を固定の範囲で測る（cosine が `search.vector.simLow` 以下で 0、`simHigh` 以上で 1）。似た頁が
    1 つも無い質問で、近傍の 1 位が満点を取らない。
  - `rrf`: Reciprocal Rank Fusion（順位 r に 1 / (60 + r)）。順位しか見ないので、キーワードのはっきりした差を
    均してしまう。
  合わせた値が Solr の点数の代わりになり、その後の順位付け（作者の署名の判定、被覆率、薄い頁の重み、言語の優先、
  クリック）は今まで通り。キーワードに引っかからずベクトルだけで入った文書には `keyword_match_b=false` が付く。
- **他のピアの要求には使わない**: 他のピアのための検索（`servesRemotePeer`）では knn も埋め込みも使わない。重い処理を
  外から引き起こされないため。
- **失敗したとき**: 埋め込みのサービスが答えなければ、キーワード検索だけで答える（30 秒は問い合わせない）。

## 設定

| キー | 既定 | 意味 |
|---|---|---|
| `search.vector.enabled` | `false` | ベクトル検索と埋め込みの係を有効にする |
| `search.vector.public` | `false` | false: 管理者の検索（パスワード付き、または localhost から）だけがベクトル検索を使う。新しい質問のたびにモデルを走らせるので、公開の検索画面の訪問者には使わせない。true: この検索画面のすべての検索（他のピアの検索には使わない） |
| `search.vector.embeddings.url` | `http://127.0.0.1:8095/v1/embeddings` | 埋め込みのサービス |
| `search.vector.model` | `intfloat/multilingual-e5-small` | モデルの名前（サービスに渡し、文書に記録する。変えると全文書を埋め直す） |
| `search.vector.queryPrefix` / `passagePrefix` | `query:` / `passage:` | e5 の約束の接頭辞（空白は YaCy が足す）。他のモデルでは空にする |
| `search.vector.passageChars` | `1500` | 文書を表す文の長さ |
| `search.vector.topK` | `50` | knn で取る文書の数 |
| `search.vector.weight` | `1.0` | ベクトルの側の重み（0 にすると、キーワードの結果の後ろに足すだけ） |
| `search.vector.fusion` | `score` | 合わせ方: `score`、`abs`、`rrf`（上の「検索」） |
| `search.vector.simLow` / `simHigh` | `0.80` / `0.90` | `abs` で類似度を測る範囲（cosine） |
| `search.vector.sections` | `0` | 先頭のほかに埋め込む節の数（0〜3）。変えると全文書を埋め直す |
| `search.vector.sections.search` | `true` | 検索で節の埋め込みを使う（false: 先頭だけ。比べるため。埋め直さない） |

Docker では `-e YACY_SEARCH_VECTOR_ENABLED=true -e YACY_SEARCH_VECTOR_EMBEDDINGS_URL=http://<サービス>:8095/v1/embeddings`。
例は yacy-example の `docs-peer/start.sh`。

## 既知の制約

- **P2P には広がらない**: ベクトルを使うのは、このピアのローカルの索引の検索だけ。他のピアへの Solr の問い合わせ
  （キーワード）と DHT の検索は今まで通り。他のピアが同じモデルを使っているとは限らないので、質問のベクトルを
  送る拡張は、モデルを seed で宣言する仕組みと合わせて後の段階で考える。
- 既定では 1 文書に 1 つのベクトル（先頭）、節を使っても最大 3 つ。とても長い頁（Node.js の fs の頁など）の後ろの方の話題には届かない。
- 次元は schema に 384 で固定。別の次元のモデルを使うには `knn_vector_384` を変え、全文書を埋め直す。
- 最初のページだけ合わせる。2 ページ目以降はキーワードの結果で、1 ページ目でベクトルの結果に押し出された
  キーワードの結果（10 位前後）は出ず、1 ページ目に出たベクトルの結果がキーワードの結果として再び出ることがある。
- 画像などの文字以外の検索、他のピアのための検索、公開の検索画面の訪問者の検索（`search.vector.public=false`）では
  使わない。MCP サーバー（別のコンテナ）から検索するときにベクトル検索を使うには `search.vector.public=true` にする。
- 一括の埋め込みの最中は、埋め込みのサービスが 1 件ずつしか処理しないので、新しい質問の埋め込みが遅れる
  ことがある。1 秒で答えが無ければ、その質問と続く 30 秒の質問はキーワードだけで答える（以前は 3 秒待ち、その間に
  ローカル検索の並べる待ち時間 2 秒が過ぎて、キーワードの結果まで抜け落ちることがあった）。
- 被覆率の重みは、ベクトルだけで入った文書（質問の語を一部しか含まない）を下げる方に働く。
