---
page: htroot/api/click_p.json
help: help/api/click_p.md
title: Report a Result Click
package: machine-api-peer
access: protected
kind: api-endpoint
backend_java: source/net/yacy/htroot/api/click_p.java
---

# Report a Result Click

## Purpose

The search page reports a result the administrator opened, so that later local searches for the same query rank
that page a little higher (`search.ranking.clicks.weight`, see `RankingSolr_p.md`). Clicks are stored in
`DATA/WORK/clicks.json` and never sent to other peers.

## What You Can Do Here

- Record that a result was opened for a query (the search page does this with `navigator.sendBeacon`).
- Read whether the click was recorded.

## Page Architecture

| Control | Meaning | Values or examples |
| --- | --- | --- |
| `query` | The query as typed. Operators such as `site:` or `/language/ja` are removed before it is stored. | `rust ownership` |
| `urlhash` | The 12-character URL hash of the opened result. | `qFLJjitD2jQw` |

Example request shape:

```http
POST /api/click_p.json
Content-Type: application/x-www-form-urlencoded

query=rust+ownership&urlhash=qFLJjitD2jQw
```

Answer: `{"recorded": true}` or `{"recorded": false}`.

## Correct Use

- Only POST requests count; a GET (a link or an image) records nothing.
- The page is protected (`_p`): only the administrator's clicks count, so visitors of a public search page cannot
  move rankings.
- Requests that a browser marks as coming from another site (`Sec-Fetch-Site`, `Origin`) are refused, so that other
  web sites cannot report clicks through the administrator's browser.
- At most 2000 queries with 50 pages each are kept; the least recently used queries are dropped.
- `search.ranking.clicks.weight=0` switches recording and its use off.

## What To Expect

`recorded` is false for a GET, a request from another site, an empty query, a malformed URL hash, or when clicks are
switched off. A recorded click changes the ranking of the next local search for the same query.

## Related Pages

- `yacysearch.html`
- `RankingSolr_p.html`
