import assert from "node:assert/strict";
import { test } from "node:test";
import { checkCrawlTarget, isPrivateAddress, SETTINGS, sameUrl, score, VERSION } from "../src/server.ts";
import { clean, configFromEnv, parseChallenge } from "../src/yacy.ts";

const r = (url: string) => ({ title: "", url, snippet: "", verified: null, trust: null, tags: [] });

test("sameUrl ignores fragment, trailing slash, host case, www and query order", () => {
  assert.ok(sameUrl("https://Example.org/a/#x", "https://example.org/a"));
  assert.ok(sameUrl("http://www.example.org/", "https://example.org"));
  assert.ok(sameUrl("https://example.org/a?b=2&a=1", "https://example.org/a?a=1&b=2"));
  assert.ok(!sameUrl("https://example.org/a?b=1", "https://example.org/a"));
});

test("score computes precision, recall, R-precision and ranks; duplicates count once", () => {
  const results = ["https://a/1", "https://a/x", "https://a/2", "https://a/y"].map(r);
  const s = score(results, ["https://a/1", "https://a/2", "https://a/3"], 2);
  assert.equal(s.precisionAtK, 0.5);
  assert.equal(s.recallAtK, 1 / 3);
  assert.equal(s.rPrecision, 2 / 3);
  assert.deepEqual(s.ranks, { "https://a/1": 1, "https://a/2": 3, "https://a/3": null });
  const dup = score([r("https://a.org/x")], ["https://a.org/x", "http://www.a.org/x/"], 1);
  assert.equal(dup.precisionAtK, 1);
});

test("private and special addresses are recognised", () => {
  for (const ip of ["127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1", "::ffff:127.0.0.1"]) assert.ok(isPrivateAddress(ip), ip);
  for (const ip of ["8.8.8.8", "2606:4700::1111", "172.32.0.1"]) assert.ok(!isPrivateAddress(ip), ip);
});

test("crawl refuses other schemes and private hosts unless allowed", async () => {
  await assert.rejects(checkCrawlTarget("file:///etc/passwd", false), /http and https/);
  await assert.rejects(checkCrawlTarget("http://127.0.0.1:8090/ConfigProperties_p.html", false), /private/);
  await assert.rejects(checkCrawlTarget("http://169.254.169.254/latest", false), /private/);
  await assert.rejects(checkCrawlTarget("http://user:pw@example.org/", false), /user or password/);
  await checkCrawlTarget("http://10.0.0.5/", true);
});

test("setting values are checked per key", () => {
  assert.ok(SETTINGS["search.ranking.solr.mm"].valid("2<-1 5<80%"));
  assert.ok(!SETTINGS["search.ranking.solr.mm"].valid("abc"));
  assert.ok(!SETTINGS["search.ranking.thin.words"].valid("1.5"));
  assert.ok(!SETTINGS["remotesearch.maxtime"].valid("60000"));
  assert.ok(SETTINGS["trust.policy.excludeTags"].valid("ads,proxy:google"));
  assert.ok(!SETTINGS["trust.policy.excludeTags"].valid("ads\nx=y"));
  assert.ok(SETTINGS["trust.search.acceptUnverified"].trust);
});

test("configuration mistakes fail at start without echoing secrets", () => {
  assert.throws(() => configFromEnv({ YACY_URL: "http://admin:secret@peer:8090" }), (e: Error) => /user or password/.test(e.message) && !e.message.includes("secret"));
  assert.throws(() => configFromEnv({ YACY_URL: "file:///x" }), /http or https/);
  assert.equal(configFromEnv({ YACY_TIMEOUT_MS: "abc" }).timeoutMs, 30000);
});

test("Digest challenges are parsed quoted and unquoted; text from peers is cleaned", () => {
  const c = parseChallenge('Digest realm="YaCy", nonce="abc", qop=auth, opaque="o1", algorithm=MD5');
  assert.deepEqual([c.realm, c.nonce, c.qop, c.opaque, c.algorithm], ["YaCy", "abc", "auth", "o1", "MD5"]);
  assert.equal(clean("<b>a</b>‮\u0000b", 10), "a b");
  assert.equal(clean("x".repeat(20), 5).length, 5);
  assert.match(VERSION, /^\d+\.\d+\.\d+$/);
});
