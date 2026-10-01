// A small HTTP client for one YaCy peer. Administrative pages (*_p.html) use HTTP Digest authentication.
import { createHash, randomBytes } from "node:crypto";

export type Config = { url: string; user: string; password: string | undefined; timeoutMs: number };

/** read and check the configuration; mistakes fail at start instead of in every call (and never echo a password) */
export function configFromEnv(env: NodeJS.ProcessEnv = process.env): Config {
  const raw = (env.YACY_URL ?? "http://localhost:8090").replace(/\/+$/, "");
  let u: URL;
  try {
    u = new URL(raw);
  } catch {
    throw new Error("YACY_URL is not a URL (example: http://localhost:8090)");
  }
  if (u.protocol !== "http:" && u.protocol !== "https:") throw new Error("YACY_URL must be an http or https URL");
  if (u.username || u.password) throw new Error("YACY_URL must not contain a user or password; use YACY_ADMIN_USER and YACY_ADMIN_PASSWORD");
  const timeout = Number(env.YACY_TIMEOUT_MS ?? 30000);
  return {
    url: raw,
    user: env.YACY_ADMIN_USER || "admin",
    password: env.YACY_ADMIN_PASSWORD || undefined,
    timeoutMs: Number.isFinite(timeout) && timeout > 0 ? Math.min(timeout, 300000) : 30000,
  };
}

const md5 = (s: string): string => createHash("md5").update(s).digest("hex");

/** the parameters of a WWW-Authenticate: Digest challenge, quoted or not */
export function parseChallenge(header: string): Record<string, string> {
  const out: Record<string, string> = {};
  const body = header.replace(/^\s*Digest\s+/i, "");
  for (const m of body.matchAll(/([a-zA-Z0-9_-]+)\s*=\s*(?:"((?:[^"\\]|\\.)*)"|([^,\s]*))/g)) out[m[1].toLowerCase()] = m[2] !== undefined ? m[2].replace(/\\(.)/g, "$1") : m[3];
  return out;
}

export type Result = { title: string; url: string; snippet: string; verified: boolean | null; trust: string | null; tags: string[] };

/** text from other peers: no markup, no control characters, bounded length */
export function clean(s: string, max: number): string {
  const t = s
    .replace(/<[^>]*>/g, "")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, "&")
    .replace(/[\p{Cc}\u200b-\u200f\u202a-\u202e\u2066-\u2069]/gu, " ")
    .replace(/\s+/g, " ")
    .trim();
  return t.length > max ? t.slice(0, max - 1) + "…" : t;
}

export function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(signal.reason);
    const t = setTimeout(resolve, ms);
    signal?.addEventListener(
      "abort",
      () => {
        clearTimeout(t);
        reject(signal.reason);
      },
      { once: true },
    );
  });
}

export class YaCy {
  readonly cfg: Config;
  constructor(cfg: Config) {
    this.cfg = cfg;
  }

  private signal(outer?: AbortSignal): AbortSignal {
    const t = AbortSignal.timeout(this.cfg.timeoutMs);
    return outer ? AbortSignal.any([t, outer]) : t;
  }

  private async fetch(path: string, init: { method?: "GET" | "POST"; body?: URLSearchParams; headers?: Record<string, string>; signal?: AbortSignal } = {}): Promise<Response> {
    return fetch(this.cfg.url + path, { method: init.method ?? "GET", body: init.body, headers: init.headers, signal: this.signal(init.signal) });
  }

  /** a request to an administrative page: answers the Digest challenge with the configured account */
  async admin(path: string, init: { method?: "GET" | "POST"; body?: URLSearchParams; signal?: AbortSignal } = {}): Promise<Response> {
    const method = init.method ?? "GET";
    // the challenge is fetched with GET, so that a POST body is sent only once
    const first = await this.fetch(path, { signal: init.signal });
    if (first.status !== 401) {
      if (method === "GET") return first;
      await first.arrayBuffer();
      return this.fetch(path, init);
    }
    await first.arrayBuffer();
    if (!this.cfg.password) throw new Error("This action needs the YaCy administrator password: set YACY_ADMIN_PASSWORD (and YACY_ADMIN_USER if it is not 'admin').");
    const c = parseChallenge(first.headers.get("www-authenticate") ?? "");
    const target = new URL(this.cfg.url + path);
    const uri = target.pathname + target.search;
    const qop = (c.qop ?? "")
      .split(",")
      .map((q) => q.trim())
      .includes("auth")
      ? "auth"
      : "";
    const cnonce = randomBytes(8).toString("hex");
    const nc = "00000001";
    const ha1 = md5(`${this.cfg.user}:${c.realm ?? ""}:${this.cfg.password}`);
    const ha2 = md5(`${method}:${uri}`);
    const response = qop ? md5(`${ha1}:${c.nonce ?? ""}:${nc}:${cnonce}:auth:${ha2}`) : md5(`${ha1}:${c.nonce ?? ""}:${ha2}`);
    const q = (v: string) => v.replace(/["\\]/g, "\\$&");
    const header =
      `Digest username="${q(this.cfg.user)}", realm="${q(c.realm ?? "")}", nonce="${q(c.nonce ?? "")}", uri="${q(uri)}", response="${response}"` +
      (c.opaque !== undefined ? `, opaque="${q(c.opaque)}"` : "") +
      (c.algorithm ? `, algorithm=${c.algorithm}` : "") +
      (qop ? `, qop=auth, nc=${nc}, cnonce="${cnonce}"` : "");
    const res = await this.fetch(path, { ...init, headers: { Authorization: header } });
    if (res.status === 401) throw new Error("YaCy rejected the administrator account (YACY_ADMIN_USER / YACY_ADMIN_PASSWORD).");
    return res;
  }

  async json<T>(path: string, signal?: AbortSignal): Promise<T> {
    const res = await this.fetch(path, { signal });
    if (!res.ok) throw new Error(`${path}: HTTP ${res.status}`);
    return (await res.json()) as T;
  }

  // ---- search
  /**
   * Remote peers answer over several seconds and YaCy fixes the order of results when they are first taken.
   * The first request starts the search; after waitMs the second one asks YaCy to sort all results that arrived.
   */
  async search(query: string, opts: { resource: "global" | "local"; count: number; waitMs: number; signal?: AbortSignal }): Promise<{ total: number; results: Result[] }> {
    const params = new URLSearchParams({ query, resource: opts.resource, maximumRecords: String(opts.count), verify: "false", nav: "none", timezoneOffset: "0" });
    // a pattern that matches nothing makes the cache key unique, so the result reflects the current settings
    params.set("prefermaskfilter", `\\Qno-match-${randomBytes(6).toString("hex")}\\E`);
    await this.json(`/yacysearch.json?${params}`, opts.signal);
    if (opts.resource === "global" && opts.waitMs > 0) await sleep(opts.waitMs, opts.signal);
    params.set("resortCachedResults", "true");
    type Item = { title: string; link: string; description: string; verified?: string; trust?: string; trustTags?: string };
    const channel = (await this.json<{ channels: { totalResults: string; items: Item[] }[] }>(`/yacysearch.json?${params}`, opts.signal)).channels[0];
    return {
      total: Number(channel.totalResults) || 0,
      results: (channel.items ?? []).slice(0, opts.count).map((i) => ({
        title: clean(String(i.title ?? ""), 300),
        url: String(i.link ?? "").slice(0, 2000),
        snippet: clean(String(i.description ?? ""), 500),
        verified: i.verified === undefined ? null : i.verified === "true",
        trust: i.trust ? clean(i.trust, 20) : null,
        tags: i.trustTags ? i.trustTags.split(" ").filter((t) => /^[a-z0-9][a-z0-9._:-]{0,63}$/.test(t)).slice(0, 8) : [],
      })),
    };
  }

  // ---- crawling and status
  async crawl(url: string, depth: number, range: "domain" | "subpath" | "wide", maxPages: number, signal?: AbortSignal): Promise<string> {
    const params = new URLSearchParams({
      crawlingstart: "",
      crawlingMode: "url",
      crawlingURL: url,
      crawlingDepth: String(depth),
      range,
      crawlingDomMaxCheck: "on",
      crawlingDomMaxPages: String(maxPages),
      indexText: "on",
      indexMedia: "off",
      deleteold: "off",
    });
    const html = await (await this.admin(`/Crawler_p.html?${params}`, { signal })).text();
    const plain = html.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ");
    return plain.match(/Crawling of "[^"]*" (started|failed[^.]*)/)?.[0] ?? "YaCy did not report the crawl start; check the crawler queue with index_status.";
  }

  async status(signal?: AbortSignal): Promise<Record<string, number>> {
    const xml = await (await this.admin("/api/status_p.xml", { signal })).text();
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

  /** crawls started by a user (YaCy's own profiles are in robot_* collections), with the handle that crawl_control needs */
  async crawls(signal?: AbortSignal): Promise<CrawlProfile[]> {
    return parseCrawlProfiles(await (await this.admin("/CrawlProfileEditor_p.xml", { signal })).text());
  }

  /** pause or resume the local crawler queue, or stop one crawl (removes its queued URLs; indexed pages stay) */
  async crawlControl(action: "pause" | "resume" | "stop", handle: string | undefined, signal?: AbortSignal): Promise<void> {
    const params =
      action === "pause" ? new URLSearchParams({ pause: "localcrawler" })
      : action === "resume" ? new URLSearchParams({ continue: "localcrawler" })
      : new URLSearchParams({ terminate: "", handle: handle ?? "" });
    const res = await this.admin(`/Crawler_p.html?${params}`, { signal });
    await res.arrayBuffer();
    if (!res.ok) throw new Error(`crawl ${action} failed: HTTP ${res.status}`);
  }

  /** remove one URL from this peer's index (full text and word index); returns YaCy's message */
  async deleteDocument(url: string, signal?: AbortSignal): Promise<string> {
    const page = await this.admin("/IndexControlURLs_p.html", { signal });
    await page.arrayBuffer();
    const token = page.headers.get("x-yacy-transaction-token");
    if (!token) throw new Error("YaCy did not send a transaction token (X-YaCy-Transaction-Token); is this a YaCy peer?");
    const res = await this.admin("/IndexControlURLs_p.html", { method: "POST", body: new URLSearchParams({ urlstring: url, urldelete: "", transactionToken: token }), signal });
    if (!res.ok) throw new Error(`delete failed: HTTP ${res.status}`);
    const plain = (await res.text()).replace(/<[^>]*>/g, " ").replace(/\s+/g, " ");
    return plain.match(/(Removed URL [^ ]+|No input given[^.]*\.|No Entry for URL[^.]*\.)/)?.[0] ?? "YaCy did not report the result; check with search_web, resource 'local'.";
  }

  async seeds(me: boolean, signal?: AbortSignal): Promise<Record<string, string>[]> {
    return (await this.json<{ peers: Record<string, string>[] }>(me ? "/yacy/seedlist.json?my=" : "/yacy/seedlist.json?me=false", signal)).peers ?? [];
  }

  // ---- settings
  async getSettings(keys: readonly string[], signal?: AbortSignal): Promise<Record<string, string | null>> {
    const html = await (await this.admin("/ConfigProperties_p.html", { signal })).text();
    return Object.fromEntries(keys.map((k) => [k, optionValue(html, k)]));
  }

  async setSetting(key: string, value: string, signal?: AbortSignal): Promise<string | null> {
    const page = await this.admin("/ConfigProperties_p.html", { signal });
    await page.arrayBuffer();
    const token = page.headers.get("x-yacy-transaction-token");
    if (!token) throw new Error("YaCy did not send a transaction token (X-YaCy-Transaction-Token); is this a YaCy peer?");
    const res = await this.admin("/ConfigProperties_p.html", { method: "POST", body: new URLSearchParams({ key, value, transactionToken: token }), signal });
    if (!res.ok) throw new Error(`setting ${key} failed: HTTP ${res.status}`);
    return optionValue(await res.text(), key);
  }

  async trustBundle(signal?: AbortSignal): Promise<{ type: string; signer: string; version: number; revoked: boolean; peers?: number }[]> {
    const json = await this.json<{ envelopes: { payload: string; signer: string }[] }>("/yacy/trust.json", signal);
    return (json.envelopes ?? []).slice(0, 1000).map((e) => {
      const p = JSON.parse(Buffer.from(e.payload, "base64url").toString("utf8")) as { type: string; version: number; revoked?: boolean; peers?: unknown[] };
      return { type: String(p.type), signer: String(e.signer), version: Number(p.version), revoked: p.revoked === true, ...(Array.isArray(p.peers) ? { peers: p.peers.length } : {}) };
    });
  }
}

/** ConfigProperties_p.html lists every setting as <option id="k<key>" value="<value>"> */
function optionValue(html: string, key: string): string | null {
  const m = html.match(new RegExp(`id="k${key.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}" value="([^"]*)"`));
  return m ? m[1].replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, "&") : null;
}

export type CrawlProfile = { handle: string; name: string; depth: number; maxPagesPerHost: number; status: string };

/** the <crawlProfile> entries of CrawlProfileEditor_p.xml that a user started; YaCy's built-in profiles have robot_* collections */
export function parseCrawlProfiles(xml: string): CrawlProfile[] {
  const out: CrawlProfile[] = [];
  for (const m of xml.matchAll(/<crawlProfile>([\s\S]*?)<\/crawlProfile>/g)) {
    const tag = (t: string) => (m[1].match(new RegExp(`<${t}>([^<]*)</${t}>`))?.[1] ?? "").trim().replace(/&amp;/g, "&");
    if (tag("collections").split(",").some((c) => c.trim().startsWith("robot_"))) continue;
    const max = Number(tag("domMaxPages")) || 0;
    out.push({ handle: tag("handle"), name: tag("name"), depth: Number(tag("depth")) || 0, maxPagesPerHost: max >= 2147483647 ? 0 : max, status: tag("status") || "active" });
  }
  return out;
}
