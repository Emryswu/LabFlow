import { api } from "./api";
import type { AuditFilter } from "../components/views/AuditView";
import type { AuditLog, Equipment, EquipmentFilter, PageData, Reservation } from "./types";

export const EQUIPMENT_LOOKUP = "/api/equipment?size=100&sort=name,asc";
export const LOOKUP_FAILED = "设备名录加载失败，设备列暂时只显示编号";

/**
 * Device names and icons only decorate a page's own rows, so the lookup settles on its own:
 * a failed lookup must never hide rows that did load. Rows fall back to "设备 #id" and the
 * page says why, instead of staying empty or stale.
 */
export async function settle<T>(request: Promise<T>): Promise<{ ok: true; data: T } | { ok: false }> {
  try {
    return { ok: true, data: await request };
  } catch {
    return { ok: false };
  }
}

export function scheduleQuery(now = new Date()) {
  const from = new Date(now);
  from.setHours(0, 0, 0, 0);
  const to = new Date(from);
  to.setDate(to.getDate() + 7);
  const query = new URLSearchParams({ from: from.toISOString(), to: to.toISOString(), size: "100", sort: "startTime,asc" });
  query.append("sort", "id,asc");
  return query;
}

// The calendar filters locally, so publish the week only after every page succeeds.
export async function loadSchedule(token: string, signal: AbortSignal): Promise<Reservation[]> {
  const query = scheduleQuery();
  const first = await api<PageData<Reservation>>(`/api/reservations?${query}`, token, { signal });
  const reservations = [...first.content];
  for (let page = 1; page < first.totalPages; page += 1) {
    query.set("page", String(page));
    const next = await api<PageData<Reservation>>(`/api/reservations?${query}`, token, { signal });
    reservations.push(...next.content);
  }
  return reservations;
}

export function fetchEquipmentBundle(token: string, filter: EquipmentFilter, signal: AbortSignal) {
  const query = new URLSearchParams({ size: "30", sort: "createdAt,desc" });
  if (filter.keyword) query.set("keyword", filter.keyword);
  if (filter.status) query.set("status", filter.status);
  if (filter.category) query.set("category", filter.category);
  return Promise.all([
    api<PageData<Equipment>>(`/api/equipment?${query}`, token, { signal }),
    // Optional: without it the filter falls back to the styled categories.
    api<string[]>("/api/equipment/categories", token, { signal }).catch(() => null),
  ]);
}

export function fetchReservationBundle(token: string, status: string, signal: AbortSignal) {
  const query = new URLSearchParams({ size: "30", sort: "createdAt,desc" });
  if (status) query.set("status", status);
  return Promise.all([
    api<PageData<Reservation>>(`/api/reservations?${query}`, token, { signal }),
    loadSchedule(token, signal),
  ]);
}

export function fetchAuditPage(token: string, filter: AuditFilter, page: number, signal: AbortSignal) {
  const query = new URLSearchParams({ page: String(page), size: "20", sort: "createdAt,desc" });
  if (filter.actorRole) query.set("actorRole", filter.actorRole);
  if (filter.action) query.set("action", filter.action);
  return api<PageData<AuditLog>>(`/api/audit-logs?${query}`, token, { signal });
}
