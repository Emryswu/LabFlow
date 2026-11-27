import type { WeekChange, WeekOverWeek } from "./trends";
import type { Equipment, Reservation, Stats, WorkOrder } from "./types";

export type Tone = "orange" | "amber" | "coral" | "green" | "blue" | "graphite";

const OPEN_WORK_ORDER = new Set(["SUBMITTED", "ASSIGNED", "IN_PROGRESS", "RESOLVED"]);
const ACTIVE_RESERVATION = new Set(["PENDING", "APPROVED", "AWAITING_PAYMENT", "PAID"]);

export const BAR_WIDTH = 6;
export const BAR_GAP = 3;
export const CHART_HEIGHT = 24;
export const RING_RADIUS = 52;
export const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS;

export type DashboardSlices = {
  myPending: Reservation[];
  myActiveReservations: Reservation[];
  pendingApprovals: Reservation[];
  upcomingSchedule: Reservation[];
  claimPool: WorkOrder[];
  myInProgress: WorkOrder[];
  urgentOrders: WorkOrder[];
  openOrders: WorkOrder[];
};

export function deriveDashboardSlices(reservations: Reservation[], workorders: WorkOrder[]): DashboardSlices {
  const myPending = reservations.filter((item) => item.status === "PENDING");
  const myActiveReservations = reservations.filter((item) => ACTIVE_RESERVATION.has(item.status));
  const pendingApprovals = myPending;
  const upcomingSchedule = [...myActiveReservations]
    .sort((a, b) => new Date(a.startTime).getTime() - new Date(b.startTime).getTime())
    .slice(0, 4);
  const claimPool = workorders.filter(
    (item) => item.category === "FAULT" && item.status === "SUBMITTED" && item.equipmentStatus !== "RETIRED",
  );
  const myInProgress = workorders.filter(
    (item) => item.status === "ASSIGNED" || item.status === "IN_PROGRESS" || item.status === "RESOLVED",
  );
  const urgentOrders = workorders.filter((item) => item.priority === "URGENT" || item.priority === "HIGH");
  const openOrders = workorders.filter((item) => OPEN_WORK_ORDER.has(item.status));

  return {
    myPending,
    myActiveReservations,
    pendingApprovals,
    upcomingSchedule,
    claimPool,
    myInProgress,
    urgentOrders,
    openOrders,
  };
}

/** "2026-10-17" -> "10月17日" without going through Date (no time-zone shift). */
export function dayTitle(day: string): string {
  const [, month, date] = day.split("-").map(Number);
  return `${month}月${date}日`;
}

export function trendChangeText(change: WeekChange): string {
  if (change.kind === "flat") return "持平";
  if (change.kind === "new") return "新增";
  return `${change.percent}%`;
}

export type SparkBar = {
  day: string;
  count: number;
  x: number;
  y: number;
  height: number;
  rx: number;
  className: "spark-empty" | "spark-bar";
};

export function buildSparkline(data: WeekOverWeek): { width: number; bars: SparkBar[] } {
  const max = Math.max(1, ...data.bars);
  const width = data.bars.length * (BAR_WIDTH + BAR_GAP) - BAR_GAP;
  const bars = data.bars.map((count, index): SparkBar => {
    // Zero days keep a 2px stub so an empty day still reads as a day.
    const height = count === 0 ? 2 : Math.max(3, Math.round((count / max) * CHART_HEIGHT));
    return {
      day: data.days[index],
      count,
      x: index * (BAR_WIDTH + BAR_GAP),
      y: CHART_HEIGHT - height,
      height,
      rx: count === 0 ? 1 : 2,
      className: count === 0 ? "spark-empty" : "spark-bar",
    };
  });
  return { width, bars };
}

export type RingSegment = {
  key: string;
  label: string;
  value: number;
  color: string;
  percent: number;
  length: number;
  offset: number;
};

export function buildEquipmentRing(stats: Stats | null): { total: number; segments: RingSegment[] } {
  const total = stats?.equipmentTotal ?? 0;
  const base = [
    { key: "available", label: "可预约", value: stats?.equipmentAvailable ?? 0, color: "var(--ok)" },
    { key: "in-use", label: "使用中", value: stats?.equipmentInUse ?? 0, color: "var(--info)" },
    { key: "maintenance", label: "维护中", value: stats?.equipmentMaintenance ?? 0, color: "var(--orange)" },
    { key: "retired", label: "已退役", value: stats?.equipmentRetired ?? 0, color: "var(--idle)" },
  ];
  let offset = 0;
  const segments = base.map((segment) => {
    const length = total > 0 ? (segment.value / total) * RING_CIRCUMFERENCE : 0;
    const item: RingSegment = {
      ...segment,
      percent: total ? Math.round((segment.value / total) * 100) : 0,
      length,
      offset,
    };
    offset += length;
    return item;
  });
  return { total, segments };
}

export function equipmentNameOf(equipmentById: Map<number, Equipment>, id: number): string {
  return equipmentById.get(id)?.name ?? `设备 #${id}`;
}
