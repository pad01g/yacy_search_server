# MCP server for a YaCy peer (stdio). Run with: docker run -i --rm -e YACY_URL=... ghcr.io/pad01g/yacy-search-mcp
FROM node:24-alpine
LABEL io.modelcontextprotocol.server.name="io.github.pad01g/yacy-search"
LABEL org.opencontainers.image.source="https://github.com/pad01g/yacy_search_server"
LABEL org.opencontainers.image.description="MCP server for a YaCy peer: peer-to-peer web search, crawling, ranking settings and search quality evaluation"
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund
COPY src ./src
USER node
ENTRYPOINT ["node", "--no-warnings", "src/server.ts"]
