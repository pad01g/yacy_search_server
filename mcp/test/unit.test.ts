import assert from "node:assert/strict";
import { test } from "node:test";
import { sameUrl, score } from "../src/server.ts";

const r = (url: string) => ({ title: "", url, snippet: "", verified: null, trust: null, tags: [] });

test("sameUrl ignores fragment, trailing slash, host case and www", () => {
  assert.ok(sameUrl("https://Example.org/a/#x", "https://example.org/a"));
  assert.ok(sameUrl("http://www.example.org/", "https://example.org"));
  assert.ok(!sameUrl("https://example.org/a?b=1", "https://example.org/a"));
});

test("score computes precision, recall, R-precision and ranks", () => {
  const results = ["https://a/1", "https://a/x", "https://a/2", "https://a/y"].map(r);
  const s = score(results, ["https://a/1", "https://a/2", "https://a/3"], 2);
  assert.equal(s.precisionAtK, 0.5);
  assert.equal(s.recallAtK, 1 / 3);
  assert.equal(s.rPrecision, 2 / 3);
  assert.deepEqual(s.ranks, { "https://a/1": 1, "https://a/2": 3, "https://a/3": null });
});
