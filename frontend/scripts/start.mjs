import { parseArgs } from "node:util";
import { loadEnv } from "vite";

const { values } = parseArgs({ options: {
  hostname: { type: "string", short: "H" },
  port: { type: "string", short: "p" },
  help: { type: "boolean", short: "h" },
} });

if (values.help) {
  console.log("Usage: npm start -- [--hostname <host>] [--port <port>]");
} else {
  Object.assign(process.env, loadEnv("production", process.cwd(), ""));
  const { startProdServer } = await import("vinext/server/prod-server");
  const { createBackendIngress } = await import("../server/node-ingress.ts");
  const serveBackend = createBackendIngress();
  const { server } = await startProdServer({
    port: Number(values.port ?? process.env.PORT ?? 3000),
    host: values.hostname ?? "0.0.0.0",
  });
  const [render] = server.listeners("request");
  server.removeListener("request", render);
  server.on("request", (req, res) => serveBackend(req, res, () => render.call(server, req, res)));
}
