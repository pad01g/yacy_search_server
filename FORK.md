# pad01g/yacy_search_server: 検索品質の改善フォーク

プロジェクトの説明: https://pad01g.github.io/yacy_search_server/ （English / [日本語](https://pad01g.github.io/yacy_search_server/ja/)）

[yacy/yacy_search_server](https://github.com/yacy/yacy_search_server) master `b50b76b` からの派生。
変更はすべて branch `improved-search` にある。効果は [pad01g/yacy-lab](https://github.com/pad01g/yacy-lab) で
docker compose の閉じた P2P 網を立てて測っている（検索品質: upstream とフォーク各 3 ノード / 信頼と NAT 越え: 8 ピア + リレー + NAT）。

変更は 2 つ:

1. **検索品質**（下の表）: 1 語だけ一致する頁が上位に来る、CJK が引けない、小さな網で他ピアを検索しない。
2. **ピアの身元・信頼・NAT 越え**（[docs/trust-and-nat.md](docs/trust-and-nat.md)）: Ed25519 の鍵から決まるピア ID と seed の署名、
   コーディネータが署名した信頼ピアの一覧、文書ごとの作者の署名、NAT の内側のピアを libp2p のリレーで届かせる sidecar。

**既存ピアとの互換性は考慮していない。** CJK の単語 hash が変わるため、日本語・中国語・韓国語の DHT 交換は
このフォーク同士でしか成り立たない。既存の索引は再索引が必要。

## 直したこと

公開網（freeworld）で 16 クエリを測ったところ、上位 10 件のうちクエリの全語を含む結果は 11% だった。原因と修正:

| # | 症状 | 原因 | 修正 | コミット |
|---|---|---|---|---|
| 1 | 1 語だけ一致する頁（キーワード詰め込み頁）が上位に来る | 2 語以上のクエリに Solr の `mm=1`（OR）を付けていた。remote ピアにも同じクエリが送られる | `search.ranking.solr.mm` / `search.ranking.solr.mm.cjk`（既定とも `2<-1 5<80%`: 2 語は両方必須、3〜5 語は 1 語の欠けを許す）で設定可能に。title へのフレーズ boost を追加（`search.ranking.solr.titlePhraseBoost`、既定 20） | Require all query terms in Solr by default |
| 2 | 1 語一致しか持たないピアの 1 位が、他ピアの完全一致と同じ順位になる | ピアごとに Solr スコアを最高点で割って 0〜1 に正規化している | 正規化後のスコアに「見つかったクエリ語 / 全クエリ語」の 2 乗を掛ける（`search.ranking.coverage.exponent`） | Weight Solr results by query term coverage |
| 3 | 日本語・中国語が単語索引（RWI）で引けない | 空白と句読点でしか区切らず、「暗号資産交換業者の登録について」全体が 1 語になる | CJK の連続を重なりのある 2 文字（bigram）に分ける。クエリ側も同じ規則で分ける | Index and search CJK text as overlapping bigrams |
| 4 | Solr で漢字・かなが 1 文字ずつの token になる | `text_general` が StandardTokenizer のみ | `CJKWidthFilter` + `CJKBigramFilter` を追加 | 同上 |
| 5 | 新しいピアだけの網では他ピアの単語索引を検索しない | DHT 検索先の最低年齢が 3 日で固定 | `remotesearch.dht.minage`（既定 3）で設定可能に | Search all peers of small networks ... |
| 6 | パーティション 1・冗長度 1 の網では他ピアへ Solr 検索が 1 件も飛ばない | 追加の検索先数 = パーティション数 × 冗長度 / 2 が 0 になる | 32 ピア以下の網（DHT 転送をしない規模）では接続中の全ピアに問い合わせる | 同上 |
| 7 | 全語をタイトルに持つが本文の薄い頁（タグ一覧など）が上位に来る | 既定の `qf` で title^15・h1^11 に対して本文 text_t^1。mm も被覆率も全語を含む頁は通す | 本文が `search.ranking.thin.words`（既定 100）語未満の結果に (語数 / 100) の重みを掛ける（下限 0.1）。CJK の語数は 2 文字で 1 語と数える（従来は空白の数で、CJK の段落は 1 語だった） | Weight down thin pages |
| 8 | 既定の検索で、言い換えの正解（クエリ語が 1 つ欠ける頁）を落とす | 小さな網では他ピアを DHT 検索先にすると Solr の追加問い合わせ先から外す。単語索引の検索は全語を要求する | DHT 転送をしない規模の網では、DHT 検索先にも Solr で問い合わせる | 同上 |
| - | `docker build -f docker/Dockerfile .` が失敗する | `.dockerignore` が `test/` を丸ごと除外するが、ビルドは `test/jetty` を使う | `test/jetty` を除外対象から外す | Keep test/jetty in the Docker build context |

設定値の説明は `help/RankingSolr_p.md` の "Related Settings Outside This Page" にある。

## ピアの身元・信頼・NAT 越え

設計と設定値の一覧は [docs/trust-and-nat.md](docs/trust-and-nat.md)。既定の動作:

- 署名のない（旧実装の）seed は受け入れない（`trust.seed.acceptUnsigned=false`）。
- 検索結果は、作者の署名が正しく、作者がコーディネータの一覧に載ったピアの文書だけを使う（`trust.search.acceptUnverified=false`）。
  **`trust.coordinators` が空なら自ピアの文書だけ**になる（閉じる側に倒す）。閉じた網で全員を信頼するなら `trust.signedOnly=true`。
  旧網も検索する「開放モード」は `trust.seed.acceptUnsigned=true` + `trust.search.acceptUnverified=true`（未検証の結果は印付きで後ろに並ぶ）。
- 他ピアの応答は既定で 5 秒待ち（`remotesearch.maxtime`、10 秒まで）、届いた順に表示する。
- NAT 越えは `p2p.sidecar.url` を設定し、`sidecar/` の sidecar を同じ鍵（`DATA/SETTINGS/peer.key`）とトークン
  （`DATA/SETTINGS/sidecar.token`）で動かしたときだけ有効。

鍵と一覧は `TrustTool` で作る（Docker イメージの中なら `/opt/yacy_search_server/lib/*`）:

```sh
T() { java -cp '/opt/yacy_search_server/lib/*' net.yacy.peers.trust.TrustTool "$@"; }
T keygen coordinator.key                    # コーディネータの鍵。表示される public key を各ピアの trust.coordinators に
T keygen operator.key
T delegate coordinator.key <operator の public key> freeworld 1 > delegation.json
T peerlist operator.key freeworld 1 peers.json > list.json   # peers.json: [{"pk": "<ピアの PK>", "priority": 100, "tags": ["ads"]}]
T bundle delegation.json list.json > bundle.json             # trust.bundle.urls で配るか、どれか 1 つのピアの /yacy/trust.json へ
T verify bundle.json freeworld <coordinator の public key>
```

ピアの公開鍵は `/yacy/seedlist.json?my=` の `PK`、または `T pubkey DATA/SETTINGS/peer.key`。
委任の失効は `delegate ... <より大きい版> --revoke`。

## ビルドとテスト

```sh
docker build -t yacy-lab/fork:latest -f docker/Dockerfile .
docker build -t yacy-lab/sidecar:latest sidecar/
(cd sidecar && go test ./...)          # Go 1.26 以上
```

`ant compileTest` は upstream の時点で既存テスト（Solr クラスを使うもの）のコンパイルに失敗する。
今回追加・関係するテストは、クラスパスに `lib/*` を加えて直接コンパイルすると通る:

```sh
CP="build:lib/*:libt/*"
TESTS="net.yacy.document.CJKBigramsTest net.yacy.document.WordTokenizerTest net.yacy.document.TokenizerTest
  net.yacy.search.query.QueryGoalCJKTest net.yacy.search.query.QueryGoalTest net.yacy.search.query.QueryParamsTest
  net.yacy.search.query.SearchEventCoverageTest net.yacy.search.snippet.TextSnippetTest
  net.yacy.peers.trust.PeerIdentityTest net.yacy.peers.trust.SeedSignatureTest net.yacy.peers.trust.TrustStoreTest
  net.yacy.peers.trust.ProvenanceTest net.yacy.peers.trust.TrustServiceTest net.yacy.peers.PeerActionsReplayTest
  net.yacy.peers.SeedTest net.yacy.peers.SeedDBTest net.yacy.peers.ProtocolTest net.yacy.htroot.yacy.SearchPeerResolutionTest"
ant compile
FILES="test/java/net/yacy/peers/trust/PeerIdentityTestAccess.java"
for t in $TESTS; do FILES="$FILES test/java/$(echo $t | tr . /).java"; done
javac -encoding UTF-8 -d /tmp/t -cp "$CP" $FILES
java -cp "/tmp/t:$CP" org.junit.runner.JUnitCore $TESTS
```

複数ピアでの確認（署名・一覧・タグ・偽の作者・NAT 越え・一覧の更新と失効）は yacy-lab の `compose.trust.yaml`。
