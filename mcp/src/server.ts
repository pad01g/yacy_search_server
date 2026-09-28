#!/usr/bin/env node
// MCP server for a YaCy peer (pad01g/yacy_search_server, branch improved-search; most tools also work with upstream YaCy).
// It runs next to the agent (stdio) and talks to one YaCy peer over HTTP. There is no central service: the peer is
// the agent's own, and it searches the peer-to-peer network it belongs to.
//   YACY_URL             base URL of the peer (default http://localhost:8090)
//   YACY_ADMIN_USER      administrator account for crawling and settings (default admin)
//   YACY_ADMIN_PASSWORD  administrator password; without it only the read-only tools work
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { configFromEnv, YaCy, type Result } from "./yacy.ts";

export const VERSION = "0.1.0";

/** the settings an agent may read and change: ranking and result filtering, nothing that exposes the peer */
export const SETTINGS = {
  "search.ranking.solr.mm": "Solr minimum match for queries with more than one term (fork default '2<-1 5<80%'; '1' is an OR query, '100%' requires all terms)",
  "search.ranking.solr.mm.cjk": "minimum match for Chinese, Japanese and Korean queries",
  "search.ranking.coverage.exponent": "weight of (query terms found / query terms) ^ exponent after per-peer normalization; 0 switches it off (fork default 2)",
  "search.ranking.solr.titlePhraseBoost": "boost of the whole query as a phrase in the title; 0 switches it off (fork default 20)",
  "search.ranking.thin.words": "results with fewer words are weighted by words / this value; 0 switches it off (fork default 100)",
  "search.ranking.thin.exponent": "exponent of the thin page weighting (fork default 1.0)",
  "remotesearch.maxtime": "milliseconds to wait for other peers, at most 10000 (fork default 5000)",
  "trust.search.acceptUnverified": "true shows results of untrusted or unsigned authors, labelled unverified and ranked last (fork default false)",
  "trust.policy.excludeTags": "comma separated declared author tags whose documents are dropped, e.g. 'ads'",
} as const;
type SettingKey = keyof typeof SETTINGS;
const SETTING_KEYS = Object.keys(SETTINGS) as [SettingKey, ...SettingKey[]];

const out = (value: unknown) => ({ content: [{ type: "text" as const, text: JSON.stringify(value, null, 2) }] });
const fail = (e: unknown) => ({ isError: true, content: [{ type: "text" as const, text: e instanceof Error ? e.message : String(e) }] });
const safely =
  <A>(f: (a: A) => Promise<unknown>) =>
  async (a: A) => {
    try {
      return out(await f(a));
    } catch (e) {
      return fail(e);
    }
  };

/** normalize URLs for comparing results with expected pages: scheme, host case, trailing slash, fragment */
export function sameUrl(a: string, b: string): boolean {
  const norm = (u: string): string => {
    try {
      const x = new URL(u);
      x.hash = "";
      return (x.host.toLowerCase() + x.pathname.replace(/\/+$/, "") + x.search).replace(/^www\./, "");
    } catch {
      return u.trim().replace(/\/+$/, "");
    }
  };
  return norm(a) === norm(b);
}

/** precision at k, recall at k and R-precision of one ranked result list */
export function score(results: Result[], relevant: string[], k: number) {
  const ranks = relevant.map((r) => results.findIndex((h) => sameUrl(h.url, r)) + 1);
  const found = (n: number) => ranks.filter((p) => p > 0 && p <= n).length;
  const R = relevant.length;
  return {
    precisionAtK: found(k) / k,
    recallAtK: R ? found(k) / R : 0,
    rPrecision: R ? found(R) / R : 0,
    ranks: Object.fromEntries(relevant.map((r, i) => [r, ranks[i] || null])),
  };
}

export function createServer(yacy: YaCy): McpServer {
  const server = new McpServer({ name: "yacy-search", version: VERSION });

  server.registerTool(
    "search",
    {
      title: "Search the web through a YaCy peer",
      description:
        "Full-text web search on your own YaCy peer. With resource 'global' (default) the query also goes to the other peers of its peer-to-peer network, so the results are not limited to what this peer crawled. " +
        "On the improved-search fork every result carries 'verified' (the author's signature is valid and the author is on a trusted list), 'trust' and the author's declared 'tags' (e.g. 'ads'). " +
        "No API key, no central service. Only the pages that peers crawled can be found: use 'crawl' to add sites.",
      inputSchema: {
        query: z.string().min(1).max(300).describe("search words; YaCy operators such as site:example.org work"),
        resource: z.enum(["global", "local"]).default("global").describe("'global': ask the other peers too; 'local': only this peer's index"),
        count: z.number().int().min(1).max(50).default(10),
        waitMs: z.number().int().min(0).max(10000).default(4000).describe("how long to let other peers answer before the results are ranked (global only)"),
      },
      annotations: { readOnlyHint: true, openWorldHint: true },
    },
    safely(async ({ query, resource, count, waitMs }) => yacy.search(query, { resource, count, waitMs })),
  );

  server.registerTool(
    "crawl",
    {
      title: "Crawl a site into the YaCy index",
      description:
        "Start a crawl on your YaCy peer: the pages are fetched, indexed and (on the fork) signed by this peer as their author, and become searchable by this peer and its network. " +
        "Crawling runs in the background; follow it with index_status. Needs YACY_ADMIN_PASSWORD. Only crawl sites you are allowed to crawl; YaCy honours robots.txt.",
      inputSchema: {
        url: z.string().url().describe("start URL, e.g. https://example.org/docs/"),
        depth: z.number().int().min(0).max(4).default(1).describe("link depth from the start URL"),
        range: z.enum(["domain", "subpath", "wide"]).default("domain").describe("'domain': stay on the host, 'subpath': below the start path, 'wide': follow links anywhere"),
      },
      annotations: { readOnlyHint: false, destructiveHint: false, openWorldHint: true },
    },
    safely(async ({ url, depth, range }) => ({ message: await yacy.crawl(url, depth, range) })),
  );

  server.registerTool(
    "index_status",
    {
      title: "Index and network status",
      description: "Number of indexed documents, crawl queues, this peer's type and reachability, and how many other peers it is connected to. Needs YACY_ADMIN_PASSWORD for the index counts.",
      inputSchema: {},
      annotations: { readOnlyHint: true },
    },
    safely(async () => {
      const [me, others] = await Promise.all([yacy.seeds(true), yacy.seeds(false)]);
      const my = me[0] ?? {};
      let index: Record<string, number | string> | string;
      let crawlerPaused: string | undefined;
      try {
        index = await yacy.status();
        // YaCy's crawler silently waits while the system load average is above 50_localcrawl_loadprereq
        const limit = Number((await yacy.getSettings(["50_localcrawl_loadprereq"]))["50_localcrawl_loadprereq"]);
        const load = Number(index.loadAverage);
        if (Number(index.localCrawlerQueue) > 0 && load > limit)
          crawlerPaused = `The crawler waits: the system load average ${load} is above 50_localcrawl_loadprereq=${limit}. Wait for the machine to be less busy, or raise the limit in ConfigProperties_p.html and restart the peer (YaCy reads it only at start).`;
      } catch (e) {
        index = (e as Error).message;
      }
      return {
        peer: my.Hash
          ? { name: my.Name, hash: my.Hash, type: my.PeerType, reach: my.Reach ?? "direct", signed: !!my.PK, version: my.Version }
          : {
              type: "virgin",
              note: "This peer does not know its public address yet, so it publishes no seed. It can crawl and search its own index. Other peers reach it once it is reachable (open port 8090, or the libp2p sidecar behind a NAT) and knows at least one peer of its network.",
            },
        connectedPeers: others.length,
        seniorPeers: others.filter((p) => p.PeerType === "senior" || p.PeerType === "principal").length,
        index,
        ...(crawlerPaused ? { crawlerPaused } : {}),
      };
    }),
  );

  server.registerTool(
    "peers",
    {
      title: "Connected peers",
      description: "The peers this peer knows in its network: name, hash, type, whether its seed is signed, how it is reached (direct or through a libp2p relay) and its declared tags.",
      inputSchema: { limit: z.number().int().min(1).max(500).default(50) },
      annotations: { readOnlyHint: true },
    },
    safely(async ({ limit }) =>
      (await yacy.seeds(false)).slice(0, limit).map((p) => ({ name: p.Name, hash: p.Hash, type: p.PeerType, signed: !!p.PK, reach: p.Reach ?? "direct", tags: p.Tags ?? "", lastSeen: p.LastSeen })),
    ),
  );

  server.registerTool(
    "get_ranking_settings",
    {
      title: "Read the ranking and filtering settings",
      description: "Current values of the settings that change ranking and result filtering, with what each one does. Needs YACY_ADMIN_PASSWORD.",
      inputSchema: {},
      annotations: { readOnlyHint: true },
    },
    safely(async () => {
      const values = await yacy.getSettings(SETTING_KEYS);
      return Object.fromEntries(SETTING_KEYS.map((k) => [k, { value: values[k], meaning: SETTINGS[k] }]));
    }),
  );

  server.registerTool(
    "set_ranking_setting",
    {
      title: "Change a ranking or filtering setting",
      description:
        "Change one ranking or filtering setting of your YaCy peer. It applies to every search this peer starts, including the queries it sends to other peers. " +
        "Measure before and after with evaluate_ranking. Needs YACY_ADMIN_PASSWORD.",
      inputSchema: { key: z.enum(SETTING_KEYS), value: z.string().max(200) },
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true },
    },
    safely(async ({ key, value }) => {
      const before = (await yacy.getSettings([key]))[key];
      const after = await yacy.setSetting(key, value);
      if (after !== value) throw new Error(`${key} reads ${JSON.stringify(after)} after setting it to ${JSON.stringify(value)}`);
      return { key, before, after };
    }),
  );

  server.registerTool(
    "evaluate_ranking",
    {
      title: "Measure search quality",
      description:
        "Run queries whose relevant result URLs you know and report precision@k, recall@k and R-precision per query and on average, plus where each relevant URL ranked. " +
        "Use it to compare settings: evaluate, change a setting, evaluate again.",
      inputSchema: {
        cases: z
          .array(z.object({ query: z.string().min(1).max(300), relevant: z.array(z.string()).min(1).max(50) }))
          .min(1)
          .max(30),
        k: z.number().int().min(1).max(50).default(10),
        resource: z.enum(["global", "local"]).default("global"),
        waitMs: z.number().int().min(0).max(10000).default(4000),
      },
      annotations: { readOnlyHint: true, openWorldHint: true },
    },
    safely(async ({ cases, k, resource, waitMs }) => {
      const perQuery: (ReturnType<typeof score> & { query: string; top: string[] })[] = [];
      for (const c of cases) {
        const { results } = await yacy.search(c.query, { resource, count: Math.max(k, c.relevant.length), waitMs });
        perQuery.push({ query: c.query, ...score(results, c.relevant, k), top: results.slice(0, k).map((r) => r.url) });
      }
      const mean = (f: (q: (typeof perQuery)[number]) => number) => Math.round((perQuery.reduce((s, q) => s + f(q), 0) / perQuery.length) * 1000) / 1000;
      return { k, mean: { precisionAtK: mean((q) => q.precisionAtK), recallAtK: mean((q) => q.recallAtK), rPrecision: mean((q) => q.rPrecision) }, perQuery };
    }),
  );

  server.registerTool(
    "trust_status",
    {
      title: "Trust lists held by this peer",
      description:
        "improved-search fork only: the signed delegations and trusted peer lists this peer holds (/yacy/trust.json). Results count as verified only if their author is on one of these lists " +
        "(or trust.signedOnly=true in a closed network).",
      inputSchema: {},
      annotations: { readOnlyHint: true },
    },
    safely(async () => yacy.trustBundle()),
  );

  return server;
}

// run as a program (not when imported by the tests)
if (import.meta.url === `file://${process.argv[1]}` || process.argv[1]?.endsWith("/server.ts")) {
  const server = createServer(new YaCy(configFromEnv()));
  await server.connect(new StdioServerTransport());
}
