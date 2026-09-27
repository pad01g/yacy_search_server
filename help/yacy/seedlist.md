---
page: htroot/yacy/seedlist.html
help: help/yacy/seedlist.md
title: seedlist
package: machine-api-peer
access: peer-service
kind: peer-endpoint
backend_java: source/net/yacy/htroot/yacy/seedlist.java
---

# seedlist

## Purpose

seedlist publishes known peer seeds.

Use it when bootstrapping or inspecting YaCy network peer knowledge.

## What You Can Do Here

- Retrieve known peer seed information.
- Use the response for network bootstrap, peer diagnostics, or network-state inspection.
- Do not treat the seed list as user search content.

## Page Architecture

This is a compact endpoint-style page. Its behavior is driven mainly by request parameters and the selected response template, so callers should send only the fields needed for the specific query or peer-service action.

## Correct Use

Call the endpoint as a protocol surface. Use exact parameter names and encoded values, authenticate when required, and inspect the response before relying on it. Avoid sending browser-only submit buttons unless the backend explicitly requires the action key.

## Access And Safety

This is a peer-service endpoint for YaCy peer communication, not a normal editing page.

## Automation And API

Page backend: `source/net/yacy/htroot/yacy/seedlist.java`.

| Endpoint | Method | Access | Backend |
| --- | --- | --- | --- |
| `/yacy/seedlist.html` | `GET or POST` | peer-service | `source/net/yacy/htroot/yacy/seedlist.java` |

### Parameter Guide

The table explains values that an agent or script must set deliberately. Parameters not relevant to a task should be omitted or left at the page default.

| Parameter | Meaning and valid values | Care |
| --- | --- | --- |
| `callback` | JSONP callback name for legacy script clients. Leave empty for normal HTML or JSON-style use. | Set only when this option is part of the intended request; otherwise omit it and let YaCy use the page default. |
| `peername` | Public peer name. Use 3 to 80 letters, digits, hyphen, or underscore; spaces are converted to hyphens. | Changes stored data, configuration, or a running job. Use the authenticated action flow where required and verify the result. |

Example request shape:

```http
GET or POST /yacy/seedlist.html?peername=...&address=...&callback=...&id=...&maxcount=...
```

### Signed Seeds

In this fork each seed carries the public key `PK` of the peer, the signature `Sig` and the signing time `SigT`; the peer hash is derived from `PK`. The fields `Reach`, `P2PA` and `RDS` describe whether the peer is reached through a libp2p relay, its circuit addresses and whether it offers DHT storage; `TV` announces the version of the trust lists the peer holds; `Tags` are self-declared tags. Unsigned seeds are rejected unless `trust.seed.acceptUnsigned=true`. `IP` and the counters are not signed; peers only believe an address after the owner of the key answered a hello challenge there.

## What To Expect

Expect a compact service response rather than a teaching interface. The response may be XML, RSS, JSON-like text, plain text, or a small HTML template depending on the endpoint.

## Related Pages

- Related protocol work usually continues through the calling tool, the peer endpoint family under `/yacy/`, or the API page that consumes this response.
