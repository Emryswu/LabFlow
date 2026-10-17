import type { Reservation, ReservationConflict } from "./types";

const clock = (date: Date) => `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;

function rangeText(conflict: ReservationConflict) {
  const start = new Date(conflict.startTime);
  const end = new Date(conflict.endTime);
  return `${start.getMonth() + 1}月${start.getDate()}日 ${clock(start)}–${clock(end)}`;
}

function describe(conflict: ReservationConflict) {
  return `#${conflict.id}${conflict.requesterName ? ` ${conflict.requesterName}` : ""} ${rangeText(conflict)}`;
}

export type ConflictSummary = {
  /** Short line shown next to the request. */
  text: string;
  /** Every clash, for the tooltip and for the disabled approve button. */
  detail: string;
};

/** Null when the request can be approved as far as the calendar is concerned. */
export function conflictSummary(reservation: Reservation): ConflictSummary | null {
  const conflicts = reservation.status === "PENDING" ? reservation.conflictsWith ?? [] : [];
  if (!conflicts.length) return null;
  const text = conflicts.length === 1
    ? `与 ${describe(conflicts[0])} 冲突`
    : `与 ${conflicts.length} 条已占用预约冲突`;
  const detail = `该时段已被占用，批准会被拒绝：${conflicts.map(describe).join("；")}`;
  return { text, detail };
}
