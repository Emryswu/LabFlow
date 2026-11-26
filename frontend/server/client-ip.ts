import { isIP } from "node:net";
import ipaddr from "ipaddr.js";

export function normalizeIp(value: string | null | undefined): string | null {
  const candidate = value?.trim();
  return candidate && !candidate.includes("%") && isIP(candidate)
    ? ipaddr.process(candidate).toString()
    : null;
}

export function parseTrustedProxies(config: string): ReadonlySet<string> {
  const proxies = config.split(",").map((part) => part.trim()).filter(Boolean);
  return new Set(proxies.map((proxy) => {
    const ip = normalizeIp(proxy);
    if (!ip) throw new Error("TRUSTED_PROXIES must contain IP addresses");
    return ip;
  }));
}

export function clientIpFromPeer(
  peer: string | undefined,
  forwardedFor: string | null,
  trustedProxies: ReadonlySet<string>,
): string | null {
  let current = normalizeIp(peer);
  if (!current || !trustedProxies.has(current) || !forwardedFor) return current;

  // Each trusted hop vouches for only the address it appended.
  for (const hop of forwardedFor.split(",").reverse()) {
    if (!trustedProxies.has(current)) break;
    const ip = normalizeIp(hop);
    if (!ip) break;
    current = ip;
  }
  return current;
}
