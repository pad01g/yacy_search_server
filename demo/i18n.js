// 画面の言語（日本語 / English）。?lang=en|ja、切り替えボタン（localStorage に覚える）、ブラウザの言語の順に決める。
// 日本語の文をそのまま鍵にし、英語のときだけ置き換える: 静的な文字は読み込み時に DOM を書き換え、スクリプトが作る文字は t() を通す。
// サーバー（demo.ts）が返す日本語のうち、ここに載っているもの（ノードの役割、プリセットの説明など）も t() で訳す。
(() => {
  const EN = {
    // header and setup
    "YaCy 複数ノード検索デモ": "YaCy multi-node search demo",
    "起動中": "starting",
    "同じサイトを crawl した 2 つの閉じた P2P 網（YaCy 本家 3 ノード / 改善版 6 ノード + リレー）に、同じクエリを global 検索で投げて並べる":
      "Two closed P2P networks that crawled the same sites (upstream YaCy: 3 nodes / improved fork: 6 nodes + relay) get the same query as a global search, side by side.",
    "docker compose を動かさずに、記録した本物の応答を画面の中で再生する": "replay recorded real answers in the page, without docker compose",
    "モック（サーバー無しで動かす）": "Mock (runs without a server)",
    "網の状態:": "Network status:",
    "改善版": "Fork",
    "本家": "Upstream",
    "ノード": "Node", "役割": "Role", "文書数": "Documents", "見えている senior": "Seniors seen", "到達": "Reach", "種別": "Type",
    "持っている信頼の一覧": "Trust lists held", "管理画面": "Admin UI",
    "（外から届かない）": "(not reachable from outside)",
    "準備完了": "ready", "準備中": "preparing", "一部停止": "partly down", "エラー": "error",
    "準備完了。検索できます": "Ready. You can search.",
    "デモのサーバーに接続できません": "Cannot reach the demo server",
    // search form
    "検索語（例: bitcoin lightning channel）": "search words (e.g. bitcoin lightning channel)",
    "両方で検索": "Search both",
    "信頼集合外の作者や署名の無い文書も「未検証」として、検証済みの後ろに出す（trust.search.acceptUnverified）":
      "also show documents of untrusted or unsigned authors as “unverified”, after the verified ones (trust.search.acceptUnverified)",
    "開放モード（未検証の結果も出す）": "Open mode (also show unverified results)",
    "ads を宣言した作者の文書を捨てる（trust.policy.excludeTags=ads）": "drop documents of authors that declared ads (trust.policy.excludeTags=ads)",
    "広告（ads タグ）を除外": "Exclude ads (ads tag)",
    "他ピアを待つ時間": "Wait for other peers",
    "3 秒": "3 s", "5 秒": "5 s", "10 秒": "10 s",
    "本家の問い合わせ元": "Upstream origin", "改善版の問い合わせ元": "Fork origin",
    // trust panel
    "コーディネータと信頼の一覧（改善版）": "Coordinators and trust lists (fork)",
    "改善版は、利用者が設定したコーディネータの一覧に載った作者の文書だけを使う。ここで問い合わせ元が信頼するコーディネータを選び、コーディネータ A の一覧を書き換えて署名し直せる。変更は数十秒で各ノードに届く（「持っている信頼の一覧」の列で確かめられる）。":
      "The fork only uses documents of authors on the lists of the coordinators the user configured. Here you choose which coordinators the searching node trusts, and edit and re-sign coordinator A's list. Changes reach the nodes within tens of seconds (see the column “Trust lists held”).",
    "問い合わせ元（": "Coordinators trusted by the origin (",
    "）が信頼するコーディネータ": ")",
    "A（デモの運営者）": "A (the demo's operator)",
    "B（別の運営者: 一覧は evil-1 だけ）": "B (another operator: lists only evil-1)",
    "両方のとき": "When both", "A を優先": "A first", "B を優先": "B first",
    "コーディネータが無いとき:": "Without coordinators:",
    "自分の文書だけ（既定）": "own documents only (default)",
    "署名されたピアすべて": "every signed peer",
    "この設定にする": "Apply",
    "B も信頼すると evil-1 のスパムが「検証済み」になる。どちらも外すと、他ピアの結果は出なくなる（「署名されたピアすべて」なら evil-1 も含めて出る）。":
      "Trusting B too makes evil-1's spam “verified”. With neither, results of other peers disappear (with “every signed peer” they all appear, evil-1 included).",
    "コーディネータ A の一覧": "Coordinator A's list",
    "ピア": "Peer", "信頼": "Trusted", "優先度 0–100": "Priority 0–100", "タグ（, 区切り）": "Tags (comma separated)",
    "配り方": "Distribution", "全ノードに URL で渡す": "give the URL to every node", "fork-2 にだけ渡す（ピア間の交換で広がる）": "give it to fork-2 only (spreads by peer exchange)",
    "署名して新しい版を配る": "Sign and publish a new version", "元に戻す": "Reset",
    "A からオペレータへの委任": "A's delegation to the operator",
    "委任を失効させる": "Revoke the delegation", "委任し直す": "Delegate again",
    "失効させると、A の一覧は無効になる（A の一覧に載ったピアの結果が消え、自分の文書だけが残る）。":
      "Revoking makes A's list invalid (results of the peers on it disappear; only own documents remain).",
    "鍵:": "Keys:",
    // result columns and legend
    "YaCy 本家（upstream）": "YaCy upstream",
    "信頼の仕組みが無い。網の誰かが持っている頁はすべて結果に混ざる。上のオプションは効かない":
      "No trust layer: every page anybody in the network holds can appear. The options above do not apply.",
    "改善版（pad01g/yacy_search_server improved-search）": "Improved fork (pad01g/yacy_search_server improved-search)",
    "作者の署名を検証し、コーディネータの一覧に載った作者の文書だけを使う。NAT の内側のピアにもリレー経由で届く":
      "Verifies author signatures and only uses documents of authors on the coordinators' lists. Reaches peers behind a NAT through a relay.",
    "検索するとここに結果が出ます": "Results appear here",
    "正解": "relevant", "罠": "decoy", "スパム": "spam", "広告": "ad", "NAT の内側": "behind NAT", "検証済み": "verified", "未検証": "unverified",
    "そのクエリの正解頁": "a relevant page for the query",
    "1 語だけの詰め込み頁・中身の薄いタグ一覧頁": "keyword-stuffed page with one term, or a thin tag-list page",
    "信頼集合外のピアが持つ頁": "a page held by a peer outside the trust set",
    "広告を宣言したピアの頁": "a page of a peer that declared ads",
    "NAT の内側のピアだけが持つ頁": "a page held only by the peer behind the NAT",
    "作者の署名の判定（改善版のみ）": "the author signature verdict (fork only)",
    "· ノード名は、その頁のサイトを crawl したノード。結果の 1 回目は届いた順、最後に順位どおりに並べ直す":
      "· the node name is the node that crawled the page's site. The first pass lists results as they arrive, the last one in rank order.",
    "閉じる": "Close",
    // dynamic
    "{origin} から検索中…": "searching from {origin}…",
    "検索中…": "searching…",
    "まだ届いていません…": "nothing yet…",
    "結果なし": "no results",
    "1 回目 {ms} ms: {n} 件（届いた順）": "first pass {ms} ms: {n} results (as they arrived)",
    "{origin} から / {first} → 他ピアを待っています…": "from {origin} / {first} → waiting for other peers…",
    "{origin} から / {first} → 最終 {ms} ms: {n} 件（順位どおり）": "from {origin} / {first} → final {ms} ms: {n} results (in rank order)",
    "エラー: {e}": "error: {e}",
    "検証済み ({trust})": "verified ({trust})", "未検証 ({trust})": "unverified ({trust})", "タグ: {tags}": "tags: {tags}",
    "網の準備が終わると使えます": "Available once the network is ready",
    "（現在 v{v}）": "(now v{v})",
    "（v{v}、{state}）": "(v{v}, {state})", "失効中": "revoked", "有効": "active",
    "A コーディネータ {a} / A オペレータ {o} / B コーディネータ {b}": "A coordinator {a} / A operator {o} / B coordinator {b}",
    "{node} の信頼の設定": "trust settings of {node}",
    "一覧の新しい版の配布": "publishing a new version of the list",
    "委任の失効": "revoking the delegation", "委任のし直し": "delegating again",
    "{label}…": "{label}…",
    "{label}: 済み（モックなのですぐ反映される）": "{label}: done (applied at once in the mock)",
    "{label}: 済み。各ノードに届くまで数十秒かかる。上の「網の状態」の表で確かめてから検索すると確実": "{label}: done. It takes tens of seconds to reach every node; check the network status table before you search.",
    "{label}: 失敗 {e}": "{label}: failed {e}",
    // server / recorded data
    "信頼集合（alpha.lab）": "trust set (alpha.lab)", "信頼集合（beta.lab）": "trust set (beta.lab)", "信頼集合（gamma.lab）": "trust set (gamma.lab)",
    "信頼集合・ads タグ（ads.lab）": "trust set, ads tag (ads.lab)",
    "署名あり・信頼集合外（spam.lab と偽の文書）": "signed, outside the trust set (spam.lab and forged documents)",
    "NAT の内側・リレー経由（delta.lab）": "behind NAT, through the relay (delta.lab)",
    "スパム頁。本家は網の誰かが持てば出る。改善版は既定で出さず、開放モードでも「未検証」として下に置く。偽の作者の文書は開放モードでも出ない。罠頁（1 語だけの詰め込み頁・中身の薄いタグ一覧頁）と正解の並び":
      "Spam pages: upstream shows them if anybody in the network holds them; the fork hides them by default and, in open mode, puts them below as “unverified”. Documents with a forged author never appear. Also shows how decoys (one-term stuffed pages, thin tag lists) rank against relevant pages.",
    "広告を宣言したピアの頁には ads タグが付く。「広告を除外」で消える。罠頁（1 語だけの詰め込み頁・中身の薄いタグ一覧頁）と正解の並び":
      "Pages of the peer that declared ads carry the ads tag and disappear with “Exclude ads”. Also shows how decoys rank against relevant pages.",
    "NAT の内側のピア（nat-1）だけが持つ頁。改善版はリレー経由で届く。本家には NAT の内側のピアに届く仕組みが無いので、本家側の網には入れていない（0 件になる）":
      "Pages held only by the peer behind the NAT (nat-1): the fork reaches it through the relay. Upstream has no way to reach peers behind a NAT, so its network has none (0 results).",
    "罠頁（1 語だけの詰め込み頁・中身の薄いタグ一覧頁）と正解の並び": "How decoys (one-term stuffed pages, thin tag lists) rank against relevant pages.",
    "日本語・中国語。本家は Solr では引けるが罠頁が上位に来る。本家の単語索引（RWI）では句読点までが 1 語になり引けない":
      "Japanese and Chinese: upstream finds them through Solr but ranks decoys high; its word index (RWI) cannot find them, because a whole sentence up to the punctuation becomes one word.",
    // mock
    "準備完了（モック: 記録した応答）": "ready (mock: recorded answers)",
    "モック: サーバー無しで、記録した応答を再生しています": "mock: replaying recorded answers without a server",
    "（記録に無いクエリ: 近似）": " (query not recorded: approximation)",
    "（モック: 記録した頁の本文）": " (mock: recorded page text)",
    "この頁は記録していません": "This page was not recorded",
    "GitHub Pages にはデモのサーバーが無いので、常にモックで動く": "GitHub Pages has no demo server, so the page always runs in mock mode",
    "モックのデータを読めませんでした: {e}": "Cannot read the mock data: {e}",
    "モックで動いています: {at} に本物のデモ（本家 3 ノード / 改善版 6 ノード + リレー）で記録した応答を再生しています。プリセットのクエリは記録どおり、それ以外のクエリは記録した頁から画面の中で探した近似です。開放モード・広告の除外・コーディネータの選択・一覧の編集・委任の失効は、記録した結果に当てはめた近似です（優先度の変更は順位に反映しません）。":
      "Mock mode: replaying answers recorded on {at} from the real demo (upstream 3 nodes / fork 6 nodes + relay). Preset queries are replayed as recorded; other queries are approximated from the recorded pages. Open mode, excluding ads, the choice of coordinators, list edits and revocation are applied to the recorded results as an approximation (priority changes do not affect the ranking).",
    " プロジェクトの説明へ戻る": " Back to the project overview",
    " / 自分のピアで本物の検索をする": " / Real search with your own peer",
    "A: 委任 v{d}{r} 一覧 v{l}": "A: delegation v{d}{r} list v{l}", "（失効）": " (revoked)", " / B: 委任 v1 一覧 v1": " / B: delegation v1 list v1",
    "クエリが空です": "The query is empty",
  };
  const params = new URLSearchParams(location.search);
  let lang = null;
  try { lang = localStorage.getItem("yacy-demo-lang"); } catch { /* ignore */ }
  if (params.get("lang") === "en" || params.get("lang") === "ja") lang = params.get("lang");
  if (lang !== "en" && lang !== "ja") lang = (navigator.language || "en").toLowerCase().startsWith("ja") ? "ja" : "en";
  window.LANG = lang;
  const fill = (s, v) => (v ? s.replace(/\{(\w+)\}/g, (_, k) => (v[k] ?? "")) : s);
  window.t = (ja, v) => fill(lang === "en" && EN[ja] !== undefined ? EN[ja] : ja, v);
  function translateDom(root) {
    if (lang !== "en") return;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const nodes = [];
    while (walker.nextNode()) nodes.push(walker.currentNode);
    for (const n of nodes) {
      const s = n.nodeValue.trim();
      if (s && EN[s] !== undefined) n.nodeValue = n.nodeValue.replace(s, EN[s]);
    }
    for (const el of root.querySelectorAll("[title],[placeholder]")) {
      for (const a of ["title", "placeholder"]) {
        const v = el.getAttribute(a);
        if (v && EN[v] !== undefined) el.setAttribute(a, EN[v]);
      }
    }
  }
  document.addEventListener("DOMContentLoaded", () => {
    document.documentElement.lang = lang;
    if (lang === "en") document.title = "YaCy multi-node search demo";
    translateDom(document.body);
    const b = document.getElementById("langToggle");
    if (b) {
      b.textContent = lang === "en" ? "日本語" : "English";
      b.addEventListener("click", () => {
        const next = lang === "en" ? "ja" : "en";
        try { localStorage.setItem("yacy-demo-lang", next); } catch { /* ignore */ }
        const u = new URL(location.href);
        u.searchParams.set("lang", next);
        location.href = u.toString();
      });
    }
  });
})();
