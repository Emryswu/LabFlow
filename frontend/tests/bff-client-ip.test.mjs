import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { createServer } from "node:http";
import { createInterface } from "node:readline";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { clientIpFromPeer, parseTrustedProxies } from "../server/client-ip.ts";

test("direct peers keep their socket address even when another proxy is configured", () => {
  const forwarded = "192.0.2.10";
  assert.equal(clientIpFromPeer("192.0.2.20", forwarded, parseTrustedProxies("")), "192.0.2.20");
  assert.equal(clientIpFromPeer("192.0.2.20", forwarded, parseTrustedProxies("192.0.2.30")), "192.0.2.20");
});

test("trusted proxy chains stop at the first untrusted address or invalid hop", () => {
  const trusted = parseTrustedProxies("192.0.2.30, 2001:db8::10");
  const peer = "::ffff:192.0.2.30";
  const expandedProxy = "2001:0db8:0:0:0:0:0:10";
  assert.equal(clientIpFromPeer(peer, `192.0.2.90, 192.0.2.20, ${expandedProxy}`, trusted), "192.0.2.20");
  assert.equal(clientIpFromPeer(peer, `192.0.2.90, invalid, ${expandedProxy}`, trusted), "2001:db8::10");
  assert.equal(clientIpFromPeer(peer, null, trusted), "192.0.2.30");
  assert.throws(() => parseTrustedProxies("192.0.2.0/24"), /TRUSTED_PROXIES/);
});

const frontend = fileURLToPath(new URL("../", import.meta.url));

async function productionUrl(t, backendPort, trustedProxies) {
  const child = spawn(process.execPath, [
    "--experimental-strip-types", "scripts/start.mjs", "--hostname", "127.0.0.1", "--port", "0",
  ], {
    cwd: frontend,
    env: {
      ...process.env,
      BACKEND_BASE_URL: `http://127.0.0.1:${backendPort}`,
      TRUSTED_PROXIES: trustedProxies,
      TRUST_EDGE_IP_HEADERS: "true",
    },
  });
  const closed = once(child, "close");
  t.after(async () => {
    child.kill();
    await closed;
  });

  let stderr = "";
  child.stderr.setEncoding("utf8").on("data", (chunk) => { stderr += chunk; });
  const lines = createInterface({ input: child.stdout });
  const startup = async () => {
    for await (const line of lines) {
      const ready = line.match(/Production server running at (http:\/\/127\.0\.0\.1:\d+)/);
      if (ready) return ready[1];
    }
    throw new Error(`Production server exited before startup: ${stderr}`);
  };
  return Promise.race([
    startup(),
    closed.then(([code]) => { throw new Error(`Production server exited (${code}): ${stderr}`); }),
  ]);
}

for (const [trustedProxies, expected] of [["", "127.0.0.1"], ["127.0.0.1, 192.0.2.30", "192.0.2.20"]]) {
  test(`production BFF stamps ${trustedProxies ? "the trusted proxy chain's client" : "the socket peer"}`, { timeout: 15000 }, async (t) => {
    const backend = createServer((req, res) => {
      req.on("end", () => {
        res.setHeader("Content-Type", "application/json");
        res.end(JSON.stringify({ ip: req.headers["x-bff-client-ip"], path: req.url }));
      });
      req.resume();
    });
    backend.listen(0, "127.0.0.1");
    await once(backend, "listening");
    t.after(() => new Promise((resolve) => backend.close(resolve)));

    const base = await productionUrl(t, backend.address().port, trustedProxies);
    const response = await fetch(`${base}/api/backend/api/auth/login?source=test`, {
      method: "POST",
      headers: { "x-forwarded-for": "192.0.2.20, 192.0.2.30" },
      body: "{}",
    });
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { ip: expected, path: "/api/auth/login?source=test" });
  });
}

test("built Worker uses only the platform IP when the Worker binding enables it", async (t) => {
  const { default: worker } = await import("../dist/server/index.js");
  const originalFetch = globalThis.fetch;
  t.after(() => { globalThis.fetch = originalFetch; });
  globalThis.fetch = async (_url, { headers }) => Response.json({
    ip: headers.get("x-bff-client-ip"),
  });
  const ctx = { waitUntil() {}, passThroughOnException() {} };

  for (const [binding, expected] of [[undefined, null], ["true", "192.0.2.20"]]) {
    const response = await worker.fetch(new Request("https://lab.example.edu/api/backend/api/auth/login", {
      method: "POST",
      headers: { "cf-connecting-ip": "::ffff:192.0.2.20", "x-forwarded-for": "192.0.2.30" },
      body: "{}",
    }), { TRUST_EDGE_IP_HEADERS: binding }, ctx);
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { ip: expected });
  }
});
