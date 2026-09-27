---
page: htroot/yacy/trust.json
help: help/yacy/trust.md
title: trust
package: machine-api-peer
access: peer-service
kind: peer-endpoint
backend_java: source/net/yacy/htroot/yacy/trust.java
---

# trust

## Purpose

`/yacy/trust.json` returns the signed trust statements this peer holds: delegations from trust coordinators to operators and the peer lists of those operators (see `docs/trust-and-nat.md`, sections 3 and 4).

## What You Can Do Here

- Fetch the bundle of another peer when its seed field `TV` announces newer versions than yours. YaCy does this automatically at every peer ping.
- Copy the bundle to a web server and list that URL in `trust.bundle.urls` of other peers.

## Access And Safety

Public, read only. Every statement in the bundle is signed; receivers verify each one and keep only statements of the coordinators they configured (`trust.coordinators`) and of the operators those coordinators delegate to. Relaying a bundle therefore cannot change what a receiver trusts.

## Automation And API

| Endpoint | Method | Access | Backend |
| --- | --- | --- | --- |
| `/yacy/trust.json` | `GET` | public | `source/net/yacy/htroot/yacy/trust.java` |

Response:

```json
{"envelopes": [{"payload": "<base64url JSON>", "signer": "<base64url Ed25519 key>", "sig": "<base64url signature>"}]}
```

Payload types: `yacy-delegation-v1` (`network`, `operator`, `version`, `revoked`) and `yacy-peerlist-v1` (`network`, `version`, `peers`: `pk`, `priority` 0-100, `tags`). The highest version per signer wins; there are no expiry dates.

## Related Pages

- `yacy/seedlist.html`
- `yacy/hello.html`
