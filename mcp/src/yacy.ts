// A small HTTP client for one YaCy peer. Administrative pages (*_p.html) use HTTP Digest authentication.
import { createHash, randomBytes } from "node:crypto";

export type Config = { url: string; user: string; password: string | undefined; timeoutMs: number };

export function configFromEnv(env: NodeJS.ProcessEnv = process.env): Config {
  const url = (env.YACY_URL ?? "http://localhost:8090").replace(/\/+$/, "");
  return { url, user: env.YACY_ADMIN_USER ?? "admin", password: env.YACY_ADMIN_PASSWORD || undefined, timeoutMs: Number(env.YACY_TIMEOUT_MS ?? 30000) };
}

const md5 = (s: string): string => createHash("md5").update(s).digest("hex");

export class YaCy {
  readonly cfg: Config;
  constructor(cfg: Config) {
    this.cfg = cfg;
  }

  private async fetch(path: string, init: { method?: "GET" | "POST"; body?: URLSearchParams; headers?: Record<string, string> } = {}): Promise<Response> {
    return fetch(this.cfg.url + path, { method: init.method ?? "GET", body: init.body, headers: init.headers, signal: AbortSignal.timeout(this.cfg.timeoutMs) });
  }

  /** a request to an administrative page: answers the Digest challenge with the configured account */
  async admin(path: string, init: { method?: "GET" | "POST"; body?: URLSearchParams } = {}): Promise<Response> {
    const method = init.method ?? "GET";
    const first = await this.fetch(path, init);
    if (first.status !== 401) return first;
    await first.arrayBuffer();
    if (!this.cfg.password) throw new Error("This action needs the YaCy administrator password: set YACY_ADMIN_PASSWORD (and YACY_ADMIN_USER if it is not 'admin').");
    const challenge = first.headers.get("www-authenticate") ?? "";
    const param = (name: string): string => challenge.match(new RegExp(`${name}="([^"]*)"`))?.[1] ?? "";
    const realm = param("realm");
    const nonce = param("nonce");
    const qop = param("qop");
    const cnonce = randomBytes(8).toString("hex");
    const nc = "00000001";
    const ha1 = md5(`${this.cfg.user}:${realm}:${this.cfg.password}`);
    const ha2 = md5(`${method}:${path}`);
    const response = qop ? md5(`${ha1}:${nonce}:${nc}:${cnonce}:auth:${ha2}`) : md5(`${ha1}:${nonce}:${ha2}`);
    const header =
      `Digest username="${this.cfg.user}", realm="${realm}", nonce="${nonce}", uri="${path}", response="${response}"` + (qop ? `, qop=auth, nc=${nc}, cnonce="${cnonce}"` : "");
    const res = await this.fetch(path, { ...init, headers: { Authorization: header } });
    if (res.status === 401) throw new Error("YaCy rejected the administrator account (YACY_ADMIN_USER / YACY_ADMIN_PASSWORD).");
    return res;
  }

  async json<T>(path: string): Promise<T> {
    const res = await this.fetch(path);
    if (!res.ok) throw new Error(`${path}: HTTP ${res.status}`);
    return (await res.json()) as T;
  }

  // ---- search
  /**
   * Remote peers answer over several seconds and YaCy fixes the order of results when they are first taken.
   * The first request starts the search; after waitMs the second one asks YaCy to sort all results that arrived.
   */
  async search(query: string, opts: { resource: "global" | "local"; count: number; waitMs: number }): Promise<{ total: number; results: Result[] }> {
    const params = new URLSearchParams({ query, resource: opts.resource, maximumRecords: String(opts.count), verify: "false", nav: "none", timezoneOffset: "0" });
    // a pattern that matches nothing makes the cache key unique, so the result reflects the current settings
    params.set("prefermaskfilter", `\\Qno-match-${randomBytes(6).toString("hex")}\\E`);
    await this.json(`/yacysearch.json?${params}`);
    if (opts.resource === "global" && opts.waitMs > 0) await new Promise((r) => setTimeout(r, opts.waitMs));
    params.set("resortCachedResults", "true");
    type Item = { title: string; link: string; description: string; verified?: string; trust?: string; trustTags?: string };
    const channel = (await this.json<{ channels: { totalResults: string; items: Item[] }[] }>(`/yacysearch.json?${params}`)).channels[0];
    return {
      total: Number(channel.totalResults),
      results: channel.items.map((i) => ({
        title: text(i.title),
        url: i.link,
        snippet: text(i.description),
        verified: i.verified === undefined ? null : i.verified === "true",
        trust: i.trust ?? null,
        tags: i.trustTags ? i.trustTags.split(" ").filter(Boolean) : [],
      })),
    };
  }

  // ---- crawling and status
  async crawl(url: string, depth: number, range: "domain" | "subpath" | "wide"): Promise<string> {
    const params = new URLSearchParams({ crawlingstart: "", crawlingMode: "url", crawlingURL: url, crawlingDepth: String(depth), range, indexText: "on", indexMedia: "off", deleteold: "off" });
    const html = await (await this.admin(`/Crawler_p.html?${params}`)).text();
    const plain = html.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ");
    return plain.match(/Crawling of "[^"]*" (started|failed[^.]*)/)?.[0] ?? "YaCy did not report the crawl start; check the crawler queue with index_status.";
  }

  async status(): Promise<Record<string, number | string>> {
    const xml = await (await this.admin("/api/status_p.xml")).text();
    const num = (tag: string, scope = xml): number => Number(scope.match(new RegExp(`<${tag}>\\s*(\\d+)`))?.[1] ?? 0);
    const section = (tag: string): string => xml.match(new RegExp(`<${tag}>([\\s\\S]*?)</${tag}>`))?.[1] ?? "";
    return {
      documents: num("urlpublictext"),
      wordReferences: num("rwipublictext"),
      loaderQueue: num("size", section("loaderqueue")),
      localCrawlerQueue: num("size", section("localcrawlerqueue")),
      remoteCrawlerQueue: num("size", section("remotecrawlerqueue")),
      loadAverage: Number(xml.match(/<load>\s*([\d.]+)/)?.[1] ?? NaN),
    };
  }

  async seeds(me: boolean): Promise<Record<string, string>[]> {
    return (await this.json<{ peers: Record<string, string>[] }>(me ? "/yacy/seedlist.json?my=" : "/yacy/seedlist.json?me=false")).peers;
  }

  // ---- settings
  async getSettings(keys: readonly string[]): Promise<Record<string, string | null>> {
    const html = await (await this.admin("/ConfigProperties_p.html")).text();
    return Object.fromEntries(keys.map((k) => [k, optionValue(html, k)]));
  }

  async setSetting(key: string, value: string): Promise<string | null> {
    const page = await this.admin("/ConfigProperties_p.html");
    await page.arrayBuffer();
    const token = page.headers.get("x-yacy-transaction-token");
    if (!token) throw new Error("YaCy did not send a transaction token (X-YaCy-Transaction-Token); is this a YaCy peer?");
    const res = await this.admin("/ConfigProperties_p.html", { method: "POST", body: new URLSearchParams({ key, value, transactionToken: token }) });
    if (!res.ok) throw new Error(`setting ${key} failed: HTTP ${res.status}`);
    return optionValue(await res.text(), key);
  }

  async trustBundle(): Promise<{ type: string; signer: string; version: number; revoked: boolean; peers?: number }[]> {
    const json = await this.json<{ envelopes: { payload: string; signer: string }[] }>("/yacy/trust.json");
    return json.envelopes.map((e) => {
      const p = JSON.parse(Buffer.from(e.payload, "base64url").toString("utf8")) as { type: string; version: number; revoked?: boolean; peers?: unknown[] };
      return { type: p.type, signer: e.signer, version: p.version, revoked: p.revoked === true, ...(Array.isArray(p.peers) ? { peers: p.peers.length } : {}) };
    });
  }
}

export type Result = { title: string; url: string; snippet: string; verified: boolean | null; trust: string | null; tags: string[] };

const text = (s: string): string =>
  s.replace(/<[^>]*>/g, "").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, "&").trim();

/** ConfigProperties_p.html lists every setting as <option id="k<key>" value="<value>"> */
function optionValue(html: string, key: string): string | null {
  const m = html.match(new RegExp(`id="k${key.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}" value="([^"]*)"`));
  return m ? text(m[1]) : null;
}
