import { readFile, writeFile } from "node:fs/promises";

const path = "/usr/local/lib/node_modules/mcp-searxng/dist/http-server.js";
const source = await readFile(path, "utf8");
const marker = "    app.use(express.json());";
const compatibilityMiddleware = `${marker}
    // Spring AI 1.1 rejects a valid empty 202 response when Express adds
    // text/plain. Remove that header for MCP notification acknowledgements.
    app.use('/mcp', (_req, res, next) => {
        const originalWriteHead = res.writeHead;
        res.writeHead = function (statusCode, statusMessage, headers) {
            if (statusCode === 202) {
                res.removeHeader('Content-Type');
                const target = typeof statusMessage === 'object' ? statusMessage : headers;
                if (target) {
                    for (const key of Object.keys(target)) {
                        if (key.toLowerCase() === 'content-type') delete target[key];
                    }
                }
            }
            return originalWriteHead.call(this, statusCode, statusMessage, headers);
        };
        next();
    });`;

if (!source.includes(marker)) {
    throw new Error("mcp-searxng HTTP server layout changed; compatibility patch not applied");
}
await writeFile(path, source.replace(marker, compatibilityMiddleware));
