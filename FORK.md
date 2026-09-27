# pad01g/yacy_search_server: 検索品質の改善フォーク

[yacy/yacy_search_server](https://github.com/yacy/yacy_search_server) master `b50b76b` からの派生。
変更はすべて branch `improved-search` にある。効果は [pad01g/yacy-lab](https://github.com/pad01g/yacy-lab) で
upstream とフォークの閉じた P2P 網（各 3 ノード）を docker compose で立てて測っている。

**既存ピアとの互換性は考慮していない。** CJK の単語 hash が変わるため、日本語・中国語・韓国語の DHT 交換は
このフォーク同士でしか成り立たない。既存の索引は再索引が必要。

## 直したこと

公開網（freeworld）で 16 クエリを測ったところ、上位 10 件のうちクエリの全語を含む結果は 11% だった。原因と修正:

| # | 症状 | 原因 | 修正 | コミット |
|---|---|---|---|---|
| 1 | 1 語だけ一致する頁（キーワード詰め込み頁）が上位に来る | 2 語以上のクエリに Solr の `mm=1`（OR）を付けていた。remote ピアにも同じクエリが送られる | `search.ranking.solr.mm`（既定 `3<-1 5<80%`）と `search.ranking.solr.mm.cjk`（既定 `100%`）で設定可能に。title へのフレーズ boost を追加 | Require all query terms in Solr by default |
| 2 | 1 語一致しか持たないピアの 1 位が、他ピアの完全一致と同じ順位になる | ピアごとに Solr スコアを最高点で割って 0〜1 に正規化している | 正規化後のスコアに「見つかったクエリ語 / 全クエリ語」の 2 乗を掛ける（`search.ranking.coverage.exponent`） | Weight Solr results by query term coverage |
| 3 | 日本語・中国語が単語索引（RWI）で引けない | 空白と句読点でしか区切らず、「暗号資産交換業者の登録について」全体が 1 語になる | CJK の連続を重なりのある 2 文字（bigram）に分ける。クエリ側も同じ規則で分ける | Index and search CJK text as overlapping bigrams |
| 4 | Solr で漢字・かなが 1 文字ずつの token になる | `text_general` が StandardTokenizer のみ | `CJKWidthFilter` + `CJKBigramFilter` を追加 | 同上 |
| 5 | 新しいピアだけの網では他ピアの単語索引を検索しない | DHT 検索先の最低年齢が 3 日で固定 | `remotesearch.dht.minage`（既定 3）で設定可能に | Search all peers of small networks ... |
| 6 | パーティション 1・冗長度 1 の網では他ピアへ Solr 検索が 1 件も飛ばない | 追加の検索先数 = パーティション数 × 冗長度 / 2 が 0 になる | 32 ピア以下の網（DHT 転送をしない規模）では接続中の全ピアに問い合わせる | 同上 |
| - | `docker build -f docker/Dockerfile .` が失敗する | `.dockerignore` が `test/` を丸ごと除外するが、ビルドは `test/jetty` を使う | `test/jetty` を除外対象から外す | Keep test/jetty in the Docker build context |

設定値の説明は `help/RankingSolr_p.md` の "Related Settings Outside This Page" にある。

## ビルドとテスト

```sh
docker build -t yacy-lab/fork:latest -f docker/Dockerfile .
```

`ant compileTest` は upstream の時点で既存テスト（Solr クラスを使うもの）のコンパイルに失敗する。
今回追加・関係するテストは、クラスパスに `lib/*` を加えて直接コンパイルすると通る:

```sh
CP="build:lib/*:libt/*"
javac -encoding UTF-8 -d /tmp/t -cp "$CP" test/java/net/yacy/document/{CJKBigramsTest,WordTokenizerTest,TokenizerTest}.java \
  test/java/net/yacy/search/query/{QueryGoalCJKTest,QueryGoalTest,QueryParamsTest,SearchEventCoverageTest}.java \
  test/java/net/yacy/search/snippet/TextSnippetTest.java
java -cp "/tmp/t:$CP" org.junit.runner.JUnitCore net.yacy.document.CJKBigramsTest net.yacy.document.WordTokenizerTest \
  net.yacy.document.TokenizerTest net.yacy.search.query.QueryGoalCJKTest net.yacy.search.query.QueryGoalTest \
  net.yacy.search.query.QueryParamsTest net.yacy.search.query.SearchEventCoverageTest net.yacy.search.snippet.TextSnippetTest
```
