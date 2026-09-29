# yacy-search MCP server

An [MCP](https://modelcontextprotocol.io/) server for one YaCy peer: web search without a search API or API key,
crawling, ranking settings and search quality evaluation. It runs next to the agent (stdio) and talks to the
agent's own peer over HTTP. There is no central service: the peer is yours, and it searches the peer-to-peer
network it belongs to. Registry name: `io.github.pad01g/yacy-search`.

It is written for the [improved-search fork](https://pad01g.github.io/yacy_search_server/) (results carry author
signature verdicts and tags). Search, crawl, status and settings also work with upstream YaCy.

## Run

Start a peer and the MCP server on one Docker network:

```sh
docker network create yacy 2>/dev/null || true
docker run -d --name yacy --network yacy -p 127.0.0.1:8090:8090 \
  -v yacy_data:/opt/yacy_search_server/DATA ghcr.io/pad01g/yacy-improved-search:latest

# Claude Code
claude mcp add yacy -- docker run -i --rm --network yacy \
  -e YACY_URL=http://yacy:8090 -e YACY_ADMIN_PASSWORD='<your password>' ghcr.io/pad01g/yacy-search-mcp:0.2.0
```

The fork's image starts with the administrator account `admin` / `yacy`: change the password at
http://127.0.0.1:8090/ConfigAccounts_p.html before connecting the MCP server, and pass the new one.

Other clients (`mcp.json` style):

```json
{
  "mcpServers": {
    "yacy": {
      "command": "docker",
      "args": ["run", "-i", "--rm", "--network", "yacy", "-e", "YACY_URL=http://yacy:8090", "-e", "YACY_ADMIN_PASSWORD=<your password>", "ghcr.io/pad01g/yacy-search-mcp:0.2.0"]
    }
  }
}
```

For a peer outside Docker use `-e YACY_URL=http://host.docker.internal:8090` (Linux: add
`--add-host=host.docker.internal:host-gateway`). Without Docker: `npm ci && node src/server.ts` in this directory
(Node 24).

| Variable | Default | Meaning |
|---|---|---|
| `YACY_URL` | `http://localhost:8090` | base URL of the peer |
| `YACY_ADMIN_USER` | `admin` | administrator account (crawl, status counts, settings) |
| `YACY_ADMIN_PASSWORD` | unset | administrator password; without it `crawl`, the settings tools and the index counts of `index_status` are not available |
| `YACY_CRAWL_ALLOW_PRIVATE` | unset | `1`: `crawl` may reach loopback and private addresses (an intranet). Otherwise only http(s) URLs of public hosts |
| `YACY_ALLOW_TRUST_SETTINGS` | unset | `1`: `set_ranking_setting` may also change `trust.search.acceptUnverified` and `trust.policy.excludeTags` |

## Tools

| Tool | What it does |
|---|---|
| `search` | full-text search; `resource: "global"` also asks connected peers and waits `waitMs` before ranking all answers |
| `crawl` | start a crawl (`url`, `depth`, `range`: domain / subpath / wide, `maxPages` per host, default 200) |
| `index_status` | indexed documents, crawl queues, peer type and connections; explains a paused crawler (load average) and a peer without a public address |
| `peers` | connected peers: signed seed, reach (direct / relay), tags |
| `get_ranking_settings` | the ranking and filtering settings with their meaning |
| `set_ranking_setting` | change one of them (allow-list only: minimum match, coverage and thin page weights, title phrase boost, wait time, unverified results, excluded tags) |
| `evaluate_ranking` | queries with known relevant URLs: precision@k, recall@k, R-precision, the rank of each relevant URL; stops starting new queries after about 50 s (reported as `skipped`) |
| `trust_status` | the signed delegations and peer lists the peer holds |

A typical loop for improving results: `evaluate_ranking` → `set_ranking_setting` → `evaluate_ranking`.

Titles and snippets come from page authors and other peers: the tool descriptions tell the model to treat them as
data, not instructions. `crawl` and `set_ranking_setting` are marked destructive so that clients ask before calling
them. Values of settings are checked per key, and a value YaCy stores differently is rolled back.

## Test

```sh
npm ci && npm run typecheck && npm test                      # unit tests
YACY_URL=http://localhost:8090 YACY_ADMIN_PASSWORD=yacy npm test   # also calls every tool on a live peer
```
