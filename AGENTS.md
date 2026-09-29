# Repository Instructions

## Using This Project From An Agent

If you want to *use* this search engine rather than change it: start a peer with
`docker run -d --name yacy -p 127.0.0.1:8090:8090 -v yacy_data:/opt/yacy_search_server/DATA ghcr.io/pad01g/yacy-improved-search:latest`
change the default administrator password `yacy` (ConfigAccounts_p.html), and connect the MCP server in `mcp/`
(image `ghcr.io/pad01g/yacy-search-mcp`, registry name `io.github.pad01g/yacy-search`).
Step-by-step instructions for agents: https://github.com/pad01g/yacy-lab/blob/main/skills/yacy-p2p-search/SKILL.md.
Overview for machines: https://pad01g.github.io/yacy_search_server/llms.txt. Trust registry (join by pull request):
https://github.com/pad01g/yacy-trust. To join other peers, add `-e YACY_P2P_BOOTSTRAP_PEERS=http://<any member>:8090`;
you may join, publish your own pages and run your own coordinator without asking anyone:
https://pad01g.github.io/yacy_search_server/join.html. Pull requests are welcome, from agents too (measure ranking
changes with yacy-lab). The sections below are for changing the code.


## Web UI, API, Help, And Localization

When changing YaCy web pages or API endpoints, update all matching user-facing and tool-facing artifacts in the same change.

- For `htroot/**/*.html` changes, update the corresponding localization files under `locales/` when visible text, labels, form controls, messages, or navigation text changes.
- For `htroot/**/*.html` changes, update the corresponding Markdown help file under `help/`.
- For API or servlet behavior changes under `source/net/yacy/htroot/**`, update the related `help/**/*.md` file with changed endpoints, access requirements, parameters, side effects, response fields, and automation guidance.
- Treat `locales` and `help` updates as required checklist items for HTML, servlet, and API changes. Do not leave them for a follow-up unless the change is explicitly internal and has no user-visible page, request parameter, response, or behavior impact.

## Tests During Code Reviews

Do not treat the repository-wide test backlog as a separate mass-rewrite project unless explicitly requested. Improve tests incrementally in the context of individual code reviews.

- During each code review, identify the behavior affected by the reviewed code and add, update, or repair focused tests where they provide useful regression coverage.
- Keep test work scoped to the reviewed area and the changes needed to verify it.
- Report unrelated existing test failures, but do not expand the review into a repository-wide test cleanup solely because those failures exist.
- Over time, use successive reviews to bring the test suite up to date alongside the production code.
