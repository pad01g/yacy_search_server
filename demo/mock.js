// デモ画面のモック。docker compose を動かさずに、記録した本物の応答（mock-data.json）を画面の中で再生する。
// window.fetch の /api/* をこのファイルが答える。記録したのは次の組み合わせ（問い合わせ元 fork-1、開放モード）:
//   本家、改善版 × 信頼するコーディネータ A だけ / A と B / 無し（署名されたピアすべて）
// 開放モード・広告の除外・自分の文書だけ・委任の失効・一覧の編集は、記録した結果に画面の中で当てはめた近似。
// 有効になる条件: ?mock=1、画面のチェックボックス（localStorage に覚える）、または GitHub Pages での表示。
(() => {
  const params = new URLSearchParams(location.search);
  const onPages = location.hostname.endsWith("github.io");
  let wanted = onPages;
  try {
    const saved = localStorage.getItem("yacy-demo-mock");
    if (saved !== null) wanted = saved === "1";
  } catch { /* storage may be blocked */ }
  if (params.has("mock")) wanted = params.get("mock") !== "0";
  if (onPages) wanted = true; // no demo server behind GitHub Pages

  document.addEventListener("DOMContentLoaded", () => {
    const t = document.getElementById("mockToggle");
    if (!t) return;
    t.checked = wanted;
    if (onPages) {
      t.disabled = true;
      t.parentElement.title = "GitHub Pages にはデモのサーバーが無いので、常にモックで動く";
    }
    t.addEventListener("change", () => {
      try { localStorage.setItem("yacy-demo-mock", t.checked ? "1" : "0"); } catch { /* ignore */ }
      const u = new URL(location.href);
      u.searchParams.set("mock", t.checked ? "1" : "0");
      location.href = u.toString();
    });
  });
  if (!wanted) return;

  const realFetch = window.fetch.bind(window);
  const dataUrl = new URL("mock-data.json", location.href).toString();
  let data = null;
  const loaded = realFetch(dataUrl).then((r) => {
    if (!r.ok) throw new Error("mock-data.json: HTTP " + r.status);
    return r.json();
  }).then((d) => {
    data = d;
    initTrust();
    showBanner();
    return d;
  });

  // ---- 信頼の状態（画面の中だけ。再読み込みで元に戻る）
  let trust = null;
  const baseEntries = () => JSON.parse(JSON.stringify(data.trust.A.list.entries));
  function initTrust() {
    trust = JSON.parse(JSON.stringify(data.trust));
    trust.ready = true;
    for (const n of Object.keys(trust.modes)) trust.modes[n] = { coordinators: ["A"], fallback: "self" };
  }
  function showBanner() {
    const bar = document.getElementById("mockBar");
    if (!bar) return;
    const at = new Date(data.recordedAt).toLocaleString();
    bar.hidden = false;
    bar.textContent = `モックで動いています: ${at} に本物のデモ（本家 3 ノード / 改善版 6 ノード + リレー）で記録した応答を再生しています。` +
      "プリセットのクエリは記録どおり、それ以外のクエリは記録した頁から画面の中で探した近似です。" +
      "開放モード・広告の除外・コーディネータの選択・一覧の編集・委任の失効は、記録した結果に当てはめた近似です（優先度の変更は順位に反映しません）。";
    if (onPages) {
      const a = document.createElement("a");
      a.href = "../";
      a.textContent = " プロジェクトの説明へ戻る";
      a.className = "plain";
      bar.append(a);
    }
  }

  const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  function stateView() {
    const s = JSON.parse(JSON.stringify(data.state));
    s.phase = "ready";
    s.message = "準備完了（モック: 記録した応答）";
    s.log = [...(s.log || []).slice(-4), "モック: サーバー無しで、記録した応答を再生しています"];
    for (const n of s.nodes) {
      n.ui = null;
      if (n.side !== "fork") continue;
      const own = n.name === "fork-1";
      n.trust = `A: 委任 v${trust.A.delegation.version}${trust.A.delegation.revoked ? "（失効）" : ""} 一覧 v${trust.A.list.version}` + (own ? " / B: 委任 v1 一覧 v1" : "");
    }
    return s;
  }

  // ---- 検索
  const words = (q) => q.toLowerCase().split(/\s+/).filter(Boolean);
  function matches(url, title, q, all) {
    const pg = data.pages[url] || {};
    const text = (title + " " + url + " " + (pg.title || "") + " " + (pg.text || "")).toLowerCase();
    const ws = words(q);
    return all ? ws.every((w) => text.includes(w)) : ws.some((w) => text.includes(w));
  }
  /** every hit recorded for any query, once per URL */
  function recordedHits(side) {
    const seen = new Map();
    const sets = side === "upstream" ? [data.upstream] : Object.values(data.fork);
    for (const set of sets) for (const r of Object.values(set)) for (const h of r.final.hits) if (!seen.has(h.url)) seen.set(h.url, { ...h, judge: undefined });
    return [...seen.values()];
  }
  function baseResult(side, q, mode) {
    if (side === "upstream") {
      const r = data.upstream[q];
      if (r) return { recorded: true, ...r };
      // upstream sends multi-word queries with minimum match 1: any word
      const hits = recordedHits("upstream").filter((h) => matches(h.url, h.title || "", q, false));
      return { recorded: false, pass1: { ms: 400, hits: hits.slice(0, 6) }, final: { ms: 900, hits: hits.slice(0, 20) } };
    }
    const cs = mode.coordinators;
    const config = cs.includes("B") ? "AB" : cs.length === 0 && mode.fallback === "signedOnly" ? "signedOnly" : "A";
    const r = data.fork[config]?.[q];
    if (r) return { recorded: true, config, ...r };
    const hits = recordedHits("fork").filter((h) => matches(h.url, h.title || "", q, true));
    return { recorded: false, config, pass1: { ms: 400, hits: hits.slice(0, 6) }, final: { ms: 900, hits: hits.slice(0, 20) } };
  }

  /** apply the current trust state and the options to recorded fork hits (open mode, recorded under `config`) */
  function adjustFork(hits, config, mode, origin, open, noads) {
    const aActive = mode.coordinators.includes("A") && !trust.A.delegation.revoked;
    const bActive = mode.coordinators.includes("B");
    const recorded = baseEntries();
    const now = trust.A.list.entries;
    const out = [];
    for (const h0 of hits) {
      const h = { ...h0 };
      const node = h.crawledBy;
      const self = node === origin || h.trust === "self";
      if (config !== "signedOnly" && !self) {
        const viaB = bActive && node === "evil-1";
        const inA = aActive && now[node]?.trusted;
        // an unsigned document stays unverified whoever is trusted
        const trusted = (inA || viaB) && h.trust !== "unsigned";
        if (trusted && h.verified !== "true") { h.verified = "true"; h.trust = "trusted"; }
        if (!trusted && h.verified === "true") { h.verified = "false"; h.trust = "signed"; }
        if (inA && !viaB) {
          const tags = (now[node].tags || []).join(",");
          const was = (recorded[node]?.tags || []).join(",");
          if (tags !== was) h.tags = tags;
        }
      }
      if (!open && h.verified !== "true") continue;
      if (noads && String(h.tags || "").split(",").map((t) => t.trim()).includes("ads")) continue;
      out.push(h);
    }
    // verified results first, then unverified ones, as the fork orders them
    const ordered = [...out.filter((h) => h.verified === "true"), ...out.filter((h) => h.verified !== "true")];
    return ordered.map((h, i) => ({ ...h, rank: i + 1 }));
  }

  function searchStream(body) {
    const side = body.side === "fork" ? "fork" : "upstream";
    const q = String(body.q || "").trim();
    const open = body.open === "1";
    const noads = body.noads === "1";
    const wait = Math.min(10000, Math.max(1000, Number(body.wait) || 5000));
    const origin = String(body.origin || (side === "fork" ? "fork-1" : "up-1"));
    const enc = new TextEncoder();
    const stream = new ReadableStream({
      async start(ctl) {
        await loaded;
        const line = (o) => ctl.enqueue(enc.encode(JSON.stringify(o) + "\n"));
        if (!q) { line({ error: "クエリが空です" }); ctl.close(); return; }
        const mode = trust.modes["fork-1"] || { coordinators: ["A"], fallback: "self" };
        const base = baseResult(side, q, mode);
        const fix = (hits) => (side === "fork" ? adjustFork(hits, base.config, mode, "fork-1", open, noads) : hits.map((h, i) => ({ ...h, rank: i + 1 })));
        const note = base.recorded ? "" : "（記録に無いクエリ: 近似）";
        const first = Math.min(1500, base.pass1.ms || 500);
        await sleep(first);
        line({ pass: 1, ms: base.pass1.ms, origin: origin + note, hits: fix(base.pass1.hits) });
        await sleep(Math.max(300, wait / 5));
        line({ pass: 2, ms: Math.max(base.final.ms, wait), origin: origin + note, hits: fix(base.final.hits) });
        ctl.close();
      },
    });
    return new Response(stream, { headers: { "content-type": "application/x-ndjson; charset=utf-8" } });
  }

  // ---- 信頼の操作
  const TAG = /^[a-z0-9][a-z0-9._:-]{0,31}$/;
  function trustAction(action, body) {
    if (action === "mode") {
      const cs = (Array.isArray(body.coordinators) ? body.coordinators : []).filter((c) => c === "A" || c === "B");
      trust.modes[String(body.node)] = { coordinators: [...new Set(cs)], fallback: body.fallback === "signedOnly" ? "signedOnly" : "self" };
    } else if (action === "list") {
      for (const [node, e] of Object.entries(body.entries || {})) {
        if (!trust.A.list.entries[node]) continue;
        trust.A.list.entries[node] = {
          trusted: e.trusted === true,
          priority: Math.max(0, Math.min(100, Math.round(Number(e.priority) || 0))),
          tags: (Array.isArray(e.tags) ? e.tags : []).map((t) => String(t).trim().toLowerCase()).filter((t) => TAG.test(t)).slice(0, 8),
        };
      }
      trust.A.list.version++;
    } else if (action === "delegation") {
      trust.A.delegation = { version: trust.A.delegation.version + 1, revoked: body.revoked === true };
      if (body.revoked !== true) trust.A.list.version++;
    } else {
      return json({ error: "unknown action " + action }, 400);
    }
    return json(trust);
  }

  window.fetch = async (input, init = {}) => {
    const url = new URL(typeof input === "string" ? input : input.url, location.href);
    const path = url.pathname.replace(/^.*?(\/api\/)/, "/api/");
    if (!path.startsWith("/api/")) return realFetch(input, init);
    await loaded;
    const body = init.body ? JSON.parse(init.body) : {};
    if (path === "/api/state") return json(stateView());
    if (path === "/api/search") return searchStream(body);
    if (path === "/api/trust" && (init.method || "GET") === "GET") return json(trust);
    if (path.startsWith("/api/trust/")) {
      await sleep(400);
      return trustAction(path.slice("/api/trust/".length), body);
    }
    return json({ error: "not found" }, 404);
  };

  window.MOCK = {
    page: (u) => (data && data.pages[u]) || { title: "", text: "" },
  };
  loaded.catch((e) => {
    document.addEventListener("DOMContentLoaded", () => {
      const bar = document.getElementById("mockBar");
      if (bar) { bar.hidden = false; bar.textContent = "モックのデータを読めませんでした: " + e.message; }
    });
  });
})();
