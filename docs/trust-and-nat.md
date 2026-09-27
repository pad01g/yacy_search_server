# ピアの身元・信頼・NAT 越えの設計

対象: pad01g/yacy_search_server branch `improved-search`。既存の YaCy 網との互換は必須としない（旧網を検索する「開放モード」は残す）。

## 1. 目的と脅威モデル

| 脅威 | 現状 | この設計での扱い |
|---|---|---|
| 検索結果の捏造（広告・マルウェアへの URL を返す） | 形式検査のみ。remote 結果は検証に失敗しても残る | 結果の文書に **作者（crawl したピア）の署名** を要求し、作者が **コーディネータの一覧に載ったピア** でなければ既定で捨てる |
| 他ピアの名を騙る / seed の改ざん | ピア ID は乱数。seed に署名なし | ピア ID = Ed25519 公開鍵のハッシュ。seed の中核項目に本人が署名。hello で鍵の所有をチャレンジで確かめる |
| 偽ピアの大量生成（Sybil） | 3 日待てば DHT 検索先になる | 検索の信頼は一覧（入場管理）で決める。一覧に無いピアの作った文書は既定で使わない |
| DHT の保存ピアによる改ざん | 検出できない | 保存ピアは信頼しなくてよい。作者の署名で改ざんを検出できる。できるのは「返さない」ことだけ |
| 信頼されたピアの「広告を混ぜる」「外部エンジンの中継」 | ― | 禁止しない。一覧で **宣言タグ** として明示させ、利用者の方針で使うか決める |

**守らないもの:** 保存ピアによる隠蔽（返さない）、信頼された作者自身の虚偽（監査とタグで扱う）、検索語の秘匿。

## 2. 身元

- 各ピアは起動時に `DATA/SETTINGS/peer.key`（Ed25519 秘密鍵、PKCS#8 PEM）を読む。無ければ作る。
- ピア ID（12 文字の YaCy hash）= `Base64Order.enhancedCoder(SHA-256(公開鍵 32 byte))` の先頭 12 文字。
  旧実装の「DHT の隙間に合わせて ID を選ぶ」処理はやめる（鍵から決まる）。
- 同じ鍵を NAT 越えの横付けプロセス（§6）の libp2p の鍵として使う。libp2p のピア ID（`12D3KooW…`）は公開鍵から計算できるので、seed には載せない。

### 2.1 seed の署名

seed は `k=v` の集合で、受け取った側が IP・種別・フラグ・最終確認時刻などを書き換えて第三者へ中継する。
そこで **本人しか決めない項目だけ** を署名対象（中核）にする。

| 中核（署名対象） | 対象外（観測値・受け手が書き換える） |
|---|---|
| `Hash`, `PK`（公開鍵 base64url）, `Name`, `Port`, `PortSSL`, `BDate`, `SigT`（署名時刻）, `Tags`（自己宣言タグ）, `Reach`, `P2PA`, `RDS`（§6） | `IP`, `IP6`, `PeerType`, `Flags`, 各種カウンタ, `LastSeen`, `news`, `TV`（§4） |

- 署名 `Sig` = Ed25519(`"yacy-seed-v1\n"` + 中核項目をキー順に `k=v\n` で連結)。
- 受信時（`Seed.genRemoteSeed`）: `PK` があれば `Hash == H(PK)` と `Sig` を検証し、どちらか失敗なら **常に拒否**。
  `PK` が無い（旧実装の）seed は `trust.seed.acceptUnsigned=false`（既定）なら拒否。
- 自分の seed は送るたびに（中核が変わっていれば）署名し直す。

### 2.2 hello のチャレンジ

IP は署名対象外なので、「その IP に居るのが本当にその鍵の持ち主か」を hello で確かめる。

- 呼び出し側は `challenge`（16 byte 乱数）を送り、応答側は `challengeSig = Sign("yacy-hello-v1|" + challenge + "|" + 自分の Hash)` を返す。
  呼び出し側は応答の自己 seed（`seed0`）の `PK` で検証する。失敗したら hello 失敗。
- 応答側が呼び出し元へ接続し直して到達性を確かめる（back-ping, `yacy/query.html`）ときも同じチャレンジを付け、
  呼び出し元の鍵で検証できた IP だけを採る。他人の IP を名乗った seed はこれで弾かれる。
- 第三者から中継された seed の IP は、自分で hello するまで未確認。改ざんされていても、文書の署名（§5）で結果は守られる（影響は可用性のみ）。

## 3. 信頼の委譲と一覧

```
コーディネータ鍵（利用者が設定。複数可、先頭ほど優先）
  └─ 委任書: 「このオペレータ鍵に、この網の一覧を作らせる」（バージョン付き。失効も新しい版で出す）
       └─ ピア一覧: 「これらのピアを、この優先度・このタグで信頼する」（オペレータが署名。バージョン付き）
```

- 委譲は 1 段（コーディネータ → オペレータ → ピア一覧）。コーディネータ自身がオペレータを兼ねてもよい（自分宛ての委任書を出す）。
- **有効期限は持たない。バージョンで管理する。** 運営者がいなくなっても最後の版で動き続ける。
- すべて同じ形の封筒で運ぶ。署名は payload のバイト列そのものに対して行うので、JSON の正規化は要らない。

```json
{"payload": "<base64url(JSON bytes)>", "signer": "<base64url 公開鍵>", "sig": "<base64url 署名>"}
```

payload の種類:

```json
{"type": "yacy-delegation-v1", "network": "freeworld", "operator": "<pk>", "version": 3,
 "revoked": false, "note": "任意"}
{"type": "yacy-peerlist-v1", "network": "freeworld", "version": 12,
 "peers": [{"pk": "<pk>", "priority": 100, "tags": ["ads"]}, ...]}
```

- 委任書は `signer` がコーディネータ鍵であること。`(coordinator, operator)` ごとに **最大バージョン** を採り、`revoked:true` なら以後その operator の一覧は無効。
- 一覧は `signer` が有効な委任を持つ operator であること。operator ごとに最大バージョンを採る。
- `network` は `network.unit.name` と一致するか `*`。
- 実効の信頼集合 = 優先順位の高いコーディネータから順に一覧を重ね、同じピアは先に決まった優先度・タグを使う。
- `priority` は 0〜100。結果スコアに `0.5 + 0.5 * priority / 100` を掛ける。
- `trust.coordinators` が空のときは「署名されたピアはすべて信頼」（署名のみモード）として動き、起動時に警告する。
  配布物は自分のコーディネータ鍵を既定値に入れて配る想定（このフォーク自体は鍵を同梱しない）。

### 3.1 宣言タグ

共通の最小語彙を仕様で決め、コーディネータは `x-<name>:` 接頭辞で独自タグを足せる。

| タグ | 意味 |
|---|---|
| `ads` | 結果に広告を混ぜる |
| `proxy:<engine>` | 外部検索エンジンの結果を中継する（例 `proxy:google`） |
| `curated` | 人手で選別した索引 |
| `unfiltered` | 選別しない |
| `adult` | 成人向けの内容を含む |

- 一覧のタグが正（コーディネータが確認したもの）。seed の `Tags` は自己申告として表示だけに使う。
- 利用者の方針 `trust.policy.excludeTags`（既定は空）に当たる作者の文書は捨てる。検索結果にはタグを付けて返す。

## 4. 一覧の配り方

- 各ピアは受け取った封筒を検証して `DATA/SETTINGS/trust-bundle.json` に保存し、`/yacy/trust.json` で公開する（誰が中継してもよい）。
- seed に `TV`（信頼しているコーディネータごとの「保持している一覧の最大バージョン」）を載せる。形式は `<コーディネータ鍵のハッシュ先頭 8 文字>.<版>|…`。
- peer ping のたびに、接続中の seed の `TV` が自分より新しければ、そのピアの `/yacy/trust.json` を取りに行って検証・統合する。
- 起動時と 10 分ごとに `trust.bundle.urls`（seed の配布先と同じ場所を想定）からも取得する。
- 古い版を掴まされる攻撃は、複数の経路（接続中のピアの `TV`）で最新版の存在を知ることで緩和する。

## 5. 文書の出所（作者の署名）

- 自ピアが crawl して索引した文書に、索引時に署名を付ける（`Segment.storeDocument`）。
- Solr フィールド `provenance_s` と、DHT 転送・RWI 検索結果のプロパティ `prov` に同じ値を入れる:
  `1|<作者の公開鍵>|<署名>|<単語 Bloom フィルタ>`（各 base64url）
- 署名対象: `"yacy-doc-v1\n" + URL(正規形) + "\n" + タイトル + "\n" + Bloom`。
- Bloom フィルタ: その文書の単語（`Condenser` の単語。RWI に入るものと同じ）の hash を入れる。
  ビット数は単語数 × 12 以上の 2 の冪（512〜32768）、ハッシュ関数 4 個。
- 検証（`Provenance.verify`）の結果:

| 判定 | 条件 | 既定の扱い |
|---|---|---|
| `SELF` | 作者が自分 | 使う |
| `TRUSTED` | 署名が正しく、作者が実効の信頼集合にいる | 使う。優先度とタグを結果に付ける |
| `SIGNED` | 署名は正しいが、作者が信頼集合にいない | 捨てる（開放モードでは「未検証」として別枠） |
| `UNSIGNED` | `provenance` が無い | 捨てる（開放モードでは「未検証」） |
| `INVALID` | 署名が合わない / 鍵と作者が合わない / タイトルか URL が改ざん | **常に捨てる** |

- RWI 由来の結果（DHT 検索）は、クエリの全単語が Bloom に含まれることも確かめる（保存ピアが無関係な単語に信頼文書を結び付けるのを防ぐ）。Solr 由来は URL やホスト名で一致することがあるので、Bloom 検査はしない。
- 要約（snippet）は署名対象外。**応答したピアが作者でも信頼ピアでもないときは、そのピアが付けた snippet を捨てる**。
- 受信側の DHT 保存（`transferURL`）は、`INVALID` を拒否し、`UNSIGNED` は開放モードでのみ受け入れる。保存ピアは作者を信頼しなくてよい。

## 6. NAT 越え

```
 NAT の内側                               公開側
┌──────────────────────┐          ┌──────────────────┐          ┌──────────────────────┐
│ YaCy ── sidecar ─────┼── 外向き ─▶ relay（sidecar）  ◀─ 外向き ─┼── sidecar ── YaCy    │
│  :8090   :8095 /:4001│  予約    │  :4001           │  circuit │  :8095     :8090     │
└──────────────────────┘          └──────────────────┘          └──────────────────────┘
          ◀──────────────── DCUtR（穴あけ）で直結に切り替え（成功すれば）────────────────▶
```

- **sidecar**（`sidecar/`, Go, go-libp2p）: YaCy と同じ鍵で libp2p ホストを立てる。AutoNAT で到達性を判定し、届かなければ設定されたリレーに予約（circuit relay v2）し、DCUtR で直結を試みる。
  - `127.0.0.1:8095/p2p/<libp2p ピア ID>/<path>`: 相手の sidecar へ libp2p ストリーム（`/yacy/http/1.0.0`）を開き、HTTP 要求をそのまま運ぶ。
  - 受け側は `/yacy/` と `/solr/select` だけを自分の YaCy（`127.0.0.1:8090`）へ転送する（管理画面 `*_p` は通さない）。
  - `/status`: libp2p ピア ID、到達性、リレー経由のアドレス。YaCy が peer ping ごとに読む。
  - `-relay-service`: リレーとして動く。1 回線あたりの転送量と時間の上限を検索応答に合わせて広げる（既定の 128 KiB / 2 分では足りない）。
- YaCy 側:
  - `p2p.sidecar.url`（空なら無効）、`p2p.mode = auto | direct | leecher`。
  - sidecar が「private」かつリレーのアドレスを持つとき、seed の `Reach=relay`、`P2PA=<アドレス>` にする。`leecher` は `Reach=none`（外から受けない。今の junior と同じ役）。
  - 相手の seed が `Reach=relay` で、自分に sidecar があれば、その相手への URL を `http://127.0.0.1:8095/p2p/<id>` にする（`Seed.getPublicURL`）。hello の back-ping もこの経路を通るので、リレー越しに到達できれば senior になる。
  - **役割:** `Reach=relay` のピアは既定で「自分の索引への問い合わせに応答するだけ」。DHT の保存先（索引の預け先・DHT 検索先）にはしない。本人が `p2p.relay.dhtStorage=true` を宣言したら（seed の `RDS=1`）保存先にも入れる。宣言の真偽は確かめられないのでオプション扱い。
- リレーのアドレスはブートストラップで配る（seed 一覧と同じ場所）。

## 7. 設定値

| キー | 既定 | 意味 |
|---|---|---|
| `trust.seed.acceptUnsigned` | `false` | 署名のない（旧実装の）seed を受け入れる |
| `trust.coordinators` | 空 | 信頼するコーディネータの公開鍵（base64url, `,` 区切り、先頭ほど優先） |
| `trust.bundle.urls` | 空 | 一覧の束を取りに行く URL |
| `trust.search.acceptUnverified` | `false` | 信頼集合外・署名なしの文書も「未検証」として結果の後ろに出す |
| `trust.policy.excludeTags` | 空 | この宣言タグを持つ作者の文書を捨てる |
| `trust.selfTags` | 空 | 自分の seed に載せる自己申告タグ |
| `p2p.sidecar.url` | 空 | sidecar の HTTP 口 |
| `p2p.mode` | `auto` | `auto` / `direct` / `leecher` |
| `p2p.relay.dhtStorage` | `false` | リレー経由でも DHT の保存先になる |
| `remotesearch.maxtime` | `5000` | 他ピアの応答を待つ時間（ms）。10000 で頭打ち |

**開放モード**（旧網の索引を使う）= `trust.seed.acceptUnsigned=true` + `trust.search.acceptUnverified=true`。
未検証の結果は検証済みの後ろに並べ、`yacysearch.json` の各結果に `verified`・`trust`・`trustTags` を付ける。

## 8. 検索の流れ（変更点）

1. 検索先の選択（`DHTSelection`）: seed の署名が検証できないピアは除く。Solr の追加問い合わせ先は信頼集合のピアに限る（開放モードでは全ピア）。DHT の保存先・DHT 検索先は、署名されたピアなら信頼集合外でもよい（文書の署名で守るため）。`Reach=relay` で `RDS` の無いピアは DHT の役割から外す。
2. 結果の受信（`Protocol` の RWI / Solr 結果、自ピアの Solr 結果）: §5 の判定で振り分け、snippet を落とす。
3. 順位（`SearchEvent.addResult`）: 信頼集合の優先度で重み付け。未検証は検証済みより必ず下にする。

## 9. 実装の順序

1. 身元（鍵・ピア ID・seed 署名・hello チャレンジ）
2. 一覧（封筒・検証・統合・保存・`/yacy/trust.json`・`TV` による交換・URL 取得）と `TrustTool`（鍵生成・署名の CLI）
3. 文書の出所（索引時の署名、Solr / プロパティでの運搬、受信時の検証、snippet の扱い）
4. 検索先の選択・順位・結果の表示（JSON の項目）
5. sidecar（Go）と YaCy 側の経路切り替え、`Reach` / `RDS`
6. 待ち時間の既定値と上限

## 10. yacy-lab での測り方

compose で次の構成を立てる（`compose.trust.yaml`）。

| ノード | 役 |
|---|---|
| `fork-1` 〜 `fork-3` | 信頼集合（優先度 100）。検索は `fork-1` から出す |
| `ads-1` | 信頼集合。タグ `ads` を宣言し、`ads.lab`（広告頁）を crawl |
| `evil-1` | 署名はあるが信頼集合外。`spam.lab`（全クエリ語を含むマルウェア誘導頁）を crawl。さらに信頼ピアを作者と偽った文書を Solr に直接書き込む |
| `nat-1` | NAT（MASQUERADE するルーター）の内側。`delta.lab` を crawl。外からは sidecar とリレー経由でしか届かない |
| `relay` | リレー（sidecar の relay モード） |

確かめること:

1. すべての seed が署名付きで、`evil-1` は「署名あり・信頼集合外」と判定される。
2. 既定では `spam.lab` の結果が 0 件。開放モードでは出るが `verified=false` で、信頼済みの結果より下。
3. `ads.lab` の結果に `ads` タグが付く。`excludeTags=ads` で消える。
4. 作者を偽った文書は、開放モードでも出ない（`INVALID`）。
5. `nat-1` は `Reach=relay` になり、`fork-1` の global 検索で `delta.lab` の頁が見つかる。`nat-1` の sidecar を止めると見つからない。`nat-1` は DHT の保存先に選ばれない。
6. 一覧を v2（`fork-3` を外す）にし、`fork-2` にだけ渡す。`fork-1` が `TV` の交換で v2 を取り込み、`fork-3` の結果が消える。
7. オペレータの委任を失効させると、その一覧のピアの結果が消える。

## 11. 既知の制約

- 旧網とは、日本語・中国語の単語 hash が異なる（CJK bigram 化のため）。開放モードでも旧網の CJK 索引とは突き合わない。
- Bloom フィルタは単語数が多い文書（約 2700 語超）で偽陽性が増え、RWI 由来の単語検査が甘くなる。
- 第三者から中継された seed の IP は hello するまで未確認。
- 信頼された作者が虚偽の内容に署名することは防げない（タグと監査で扱う）。
- 保存ピアが結果を返さない攻撃は防げない（冗長化で薄める）。
- 自ピアの既存の索引は署名が無い。署名付きにするには再索引が要る。
