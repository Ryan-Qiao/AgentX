FROM node:22-alpine

RUN npm install --global mcp-searxng@1.11.0 \
    && npm cache clean --force

COPY patch-http.mjs /tmp/patch-http.mjs
RUN node /tmp/patch-http.mjs && rm /tmp/patch-http.mjs

ENV MCP_HTTP_PORT=3000
EXPOSE 3000

CMD ["mcp-searxng"]
