import type { Trends } from "./types";

export type TrendSeries = Exclude<keyof Trends, "days">;

export type WeekChange =
  | { kind: "up" | "down"; percent: number }
  | { kind: "flat" }
  /** Nothing in the previous 7 days, so a percentage would be meaningless. */
  | { kind: "new" };

export type WeekOverWeek = {
  days: string[];
  bars: number[];
  current: number;
  previous: number;
  change: WeekChange;
};

const WEEK = 7;

/**
 * Splits a 14-day series into "last 7 days" (bars, total) and the 7 days before,
 * for the stat cards. Returns null when the series is missing — the backend
 * omits discrepancy counts for non-admins, and a failed trends request must not
 * break the card.
 */
export function weekOverWeek(trends: Trends | null, series: TrendSeries): WeekOverWeek | null {
  const values = trends?.[series];
  if (!trends || !values || values.length < WEEK * 2 || trends.days.length !== values.length) return null;
  const bars = values.slice(-WEEK);
  const current = sum(bars);
  const previous = sum(values.slice(-WEEK * 2, -WEEK));
  return { days: trends.days.slice(-WEEK), bars, current, previous, change: changeOf(current, previous) };
}

function changeOf(current: number, previous: number): WeekChange {
  if (current === previous) return { kind: "flat" };
  if (previous === 0) return { kind: "new" };
  const percent = Math.round((Math.abs(current - previous) / previous) * 100);
  if (percent === 0) return { kind: "flat" };
  return { kind: current > previous ? "up" : "down", percent };
}

const sum = (values: number[]) => values.reduce((total, value) => total + value, 0);
