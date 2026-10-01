// Calls every tool through a real MCP client against a running YaCy peer. Skipped unless YACY_URL is set.
import assert from "node:assert/strict";
import { test } from "node:test";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const live = !!process.env.YACY_URL;
const query = process.env.YACY_TEST_QUERY ?? "bitcoin lightning channel";

test("all tools against a live peer", { skip: !live && "set YACY_URL (and YACY_ADMIN_PASSWORD) to run" }, async () => {
  const client = new Client({ name: "test", version: "0" });
  await client.connect(
    new StdioClientTransport({
      command: process.execPath,
      args: ["--no-warnings", new URL("../src/server.ts", import.meta.url).pathname],
      env: process.env as Record<string, string>,
    }),
  );
  try {
    const call = async (name: string, args: Record<string, unknown> = {}) => {
      const res = (await client.callTool({ name, arguments: args })) as { isError?: boolean; content: { text: string }[] };
      assert.ok(!res.isError, `${name}: ${res.content[0]?.text}`);
      return JSON.parse(res.content[0].text);
    };
    const tools = (await client.listTools()).tools.map((t) => t.name).sort();
    assert.deepEqual(tools, ["control_crawl", "crawl_site", "delete_document", "evaluate_ranking", "get_index_status", "get_ranking_settings", "get_trust_status", "list_crawls", "list_peers", "search_web", "set_ranking_setting"]);

    const status = await call("get_index_status");
    console.log("get_index_status", JSON.stringify(status));
    assert.ok(status.connectedPeers >= 0);
    const peers = await call("list_peers", { limit: 5 });
    console.log("list_peers", peers.length);
    const found = await call("search_web", { query, count: 10, waitMs: 3000 });
    console.log(
      "search_web",
      found.total,
      found.results.slice(0, 3).map((x: { url: string; verified: boolean }) => `${x.url} verified=${x.verified}`),
    );
    assert.ok(found.results.length > 0, "the search found nothing");
    const settings = await call("get_ranking_settings");
    const before = settings["search.ranking.thin.words"].value;
    const changed = await call("set_ranking_setting", { key: "search.ranking.thin.words", value: "123" });
    assert.equal(changed.after, "123");
    await call("set_ranking_setting", { key: "search.ranking.thin.words", value: before });
    const evaluation = await call("evaluate_ranking", { cases: [{ query, relevant: found.results.slice(0, 2).map((x: { url: string }) => x.url) }], k: 5, waitMs: 3000 });
    console.log("evaluate_ranking", JSON.stringify(evaluation.mean));
    assert.ok(evaluation.mean.recallAtK > 0);
    if (process.env.YACY_TEST_CRAWL) {
      const crawl = await call("crawl_site", { url: process.env.YACY_TEST_CRAWL, depth: 0, maxPages: 10 });
      console.log("crawl_site", crawl.message);
      assert.match(crawl.message, /started/);
      const running = await call("list_crawls");
      const host = new URL(process.env.YACY_TEST_CRAWL!).hostname;
      const mine = running.find((c: { name: string }) => c.name === host);
      assert.ok(mine, "the new crawl is listed");
      const stopped = await call("control_crawl", { action: "stop", handle: mine.handle });
      assert.ok(!stopped.crawls.some((c: { handle: string }) => c.handle === mine.handle), "the crawl is gone after stop");
    }
    console.log("list_crawls", (await call("list_crawls")).length);
    assert.equal((await call("control_crawl", { action: "pause" })).action, "pause");
    assert.equal((await call("control_crawl", { action: "resume" })).action, "resume");
    const noHandle = (await client.callTool({ name: "control_crawl", arguments: { action: "stop" } })) as { isError?: boolean };
    assert.ok(noHandle.isError, "stop without a handle is refused");
    if (process.env.YACY_TEST_DELETE) {
      const del = await call("delete_document", { url: process.env.YACY_TEST_DELETE });
      console.log("delete_document", del.result);
      assert.match(del.result, /Removed URL/);
    }
    const trust = await call("get_trust_status");
    console.log("get_trust_status", trust.length, "envelopes");
    // a bad key is refused by the schema, not sent to YaCy
    const bad = (await client.callTool({ name: "set_ranking_setting", arguments: { key: "adminAccountBase64MD5", value: "x" } })) as { isError?: boolean };
    assert.ok(bad.isError);
  } finally {
    await client.close();
  }
});

test("admin tools explain a missing password", { skip: !live && "set YACY_URL to run" }, async () => {
  const env = { ...(process.env as Record<string, string>) };
  delete env.YACY_ADMIN_PASSWORD;
  const client = new Client({ name: "test", version: "0" });
  await client.connect(new StdioClientTransport({ command: process.execPath, args: ["--no-warnings", new URL("../src/server.ts", import.meta.url).pathname], env }));
  try {
    const res = (await client.callTool({ name: "get_ranking_settings", arguments: {} })) as { isError?: boolean; content: { text: string }[] };
    assert.ok(res.isError);
    assert.match(res.content[0].text, /YACY_ADMIN_PASSWORD/);
  } finally {
    await client.close();
  }
});
