import type { Reservation } from "./types";

/**
 * Statuses drawn on the calendar. Mirrors ReservationStatuses.SCHEDULED on the
 * backend: waiting for a decision, holding the slot, or already used.
 */
export const SCHEDULE_STATUSES = new Set(["PENDING", "APPROVED", "AWAITING_PAYMENT", "PAID", "COMPLETED"]);

export type BlockKind = "pending" | "occupied" | "done";

export type ScheduleBlock = {
  reservation: Reservation;
  kind: BlockKind;
  dayIndex: number;
  /** Minutes since that day's local midnight. */
  startMinute: number;
  endMinute: number;
  /** Side-by-side position among blocks that overlap in time on the same day. */
  lane: number;
  lanes: number;
  /** The reservation started on an earlier day / ends on a later day (crosses midnight). */
  continuesBefore: boolean;
  continuesAfter: boolean;
};

export type WeekSchedule = {
  days: Date[];
  blocks: ScheduleBlock[];
  /** Visible hours [from, to): 08–20 by default, widened to fit every block. */
  hours: [number, number];
};

const DAY_MINUTES = 24 * 60;
const DEFAULT_HOURS: [number, number] = [8, 20];

export function startOfDay(date: Date) {
  const value = new Date(date);
  value.setHours(0, 0, 0, 0);
  return value;
}

export function addDays(date: Date, days: number) {
  const value = new Date(date);
  value.setDate(value.getDate() + days);
  return value;
}

function kindOf(status: string): BlockKind {
  if (status === "PENDING") return "pending";
  if (status === "COMPLETED") return "done";
  return "occupied";
}

const minutesBetween = (from: Date, to: Date) => Math.round((to.getTime() - from.getTime()) / 60_000);

/**
 * Lays reservations out on a 7-day calendar starting at {@code weekStart}'s local
 * midnight. A reservation crossing midnight becomes one block per day. Blocks that
 * overlap on the same day share the column side by side — pending requests are
 * allowed to overlap each other and the slot they are competing for.
 */
export function buildWeekSchedule(reservations: Reservation[], weekStart: Date, dayCount = 7): WeekSchedule {
  const firstDay = startOfDay(weekStart);
  const days = Array.from({ length: dayCount }, (_, index) => addDays(firstDay, index));
  const segments: Omit<ScheduleBlock, "lane" | "lanes">[] = [];

  for (const reservation of reservations) {
    if (!SCHEDULE_STATUSES.has(reservation.status)) continue;
    const start = new Date(reservation.startTime);
    const end = new Date(reservation.endTime);
    if (!(end > start)) continue;
    days.forEach((day, dayIndex) => {
      const dayEnd = addDays(day, 1);
      if (start >= dayEnd || end <= day) return;
      segments.push({
        reservation,
        kind: kindOf(reservation.status),
        dayIndex,
        startMinute: start > day ? minutesBetween(day, start) : 0,
        endMinute: end < dayEnd ? minutesBetween(day, end) : minutesBetween(day, dayEnd),
        continuesBefore: start < day,
        continuesAfter: end > dayEnd,
      });
    });
  }

  const blocks = days.flatMap((_, dayIndex) => assignLanes(segments.filter((segment) => segment.dayIndex === dayIndex)));
  return { days, blocks, hours: visibleHours(blocks) };
}

/** Greedy interval colouring inside each cluster of mutually overlapping segments. */
function assignLanes(segments: Omit<ScheduleBlock, "lane" | "lanes">[]): ScheduleBlock[] {
  const sorted = [...segments].sort((a, b) => a.startMinute - b.startMinute || a.endMinute - b.endMinute
    || a.reservation.id - b.reservation.id);
  const result: ScheduleBlock[] = [];
  let cluster: ScheduleBlock[] = [];
  let laneEnds: number[] = [];
  let clusterEnd = -1;

  const closeCluster = () => {
    for (const block of cluster) block.lanes = laneEnds.length;
    result.push(...cluster);
    cluster = [];
    laneEnds = [];
  };

  for (const segment of sorted) {
    if (segment.startMinute >= clusterEnd) {
      closeCluster();
      clusterEnd = segment.endMinute;
    } else {
      clusterEnd = Math.max(clusterEnd, segment.endMinute);
    }
    // Back-to-back blocks reuse a lane: [10:00, 12:00) and [12:00, 13:00) do not overlap.
    let lane = laneEnds.findIndex((laneEnd) => laneEnd <= segment.startMinute);
    if (lane === -1) {
      lane = laneEnds.length;
      laneEnds.push(segment.endMinute);
    } else {
      laneEnds[lane] = segment.endMinute;
    }
    cluster.push({ ...segment, lane, lanes: 1 });
  }
  closeCluster();
  return result;
}

function visibleHours(blocks: ScheduleBlock[]): [number, number] {
  if (!blocks.length) return DEFAULT_HOURS;
  const earliest = Math.min(...blocks.map((block) => block.startMinute));
  const latest = Math.max(...blocks.map((block) => block.endMinute));
  return [
    Math.max(0, Math.min(DEFAULT_HOURS[0], Math.floor(earliest / 60))),
    Math.min(24, Math.max(DEFAULT_HOURS[1], Math.ceil(Math.min(latest, DAY_MINUTES) / 60))),
  ];
}
