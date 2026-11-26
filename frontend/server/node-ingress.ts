import type { IncomingMessage, ServerResponse } from "node:http";
import { nodeToWebRequest, sendWebResponse } from "vinext/server/prod-server";
import { BACKEND_PROXY_PREFIX, proxyBackend } from "./backend-proxy.ts";
import { clientIpFromPeer, parseTrustedProxies } from "./client-ip.ts";

export function createBackendIngress() {
  const trustedProxies = parseTrustedProxies(process.env.TRUSTED_PROXIES ?? "");

  return function serveBackend(req: IncomingMessage, res: ServerResponse, next: () => void) {
    if (!req.url?.startsWith(BACKEND_PROXY_PREFIX)) return next();

    void forward(req, res, trustedProxies).catch(() => {
      if (!res.headersSent) res.writeHead(500);
      res.end();
    });
  };
}

async function forward(req: IncomingMessage, res: ServerResponse, trustedProxies: ReadonlySet<string>) {
  const request = nodeToWebRequest(req);
  const address = clientIpFromPeer(req.socket.remoteAddress, request.headers.get("x-forwarded-for"), trustedProxies);
  const response = await proxyBackend(request, address);
  await sendWebResponse(response, req, res, true);
}
