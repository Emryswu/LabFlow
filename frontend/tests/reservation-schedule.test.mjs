import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { runInThisContext } from "node:vm";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";

process.env.TZ = "Asia/Shanghai";
const appDirectory = fileURLToPath(new URL("../app/", import.meta.url));
const require = createRequire(import.meta.url);

function load(path) {
  const cache = new Map();
  function loadFile(filename) {
    if (cache.has(filename)) return cache.get(filename).exports;
    const loaded = { exports: {} };
    cache.set(filename, loaded);
    const output = ts.transpileModule(readFileSync(filename, "utf8"), {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true },
    }).outputText;
    const localRequire = (specifier) => {
      if (!specifier.startsWith(".")) return require(specifier);
      const base = resolve(dirname(filename), specifier);
      return loadFile([`${base}.ts`, `${base}.tsx`].find(existsSync));
    };
    runInThisContext(`(function(require, module, exports) {${output}\n})`, { filename })(localRequire, loaded, loaded.exports);
    return loaded.exports;
  }
  return loadFile(resolve(appDirectory, path));
}

const monday = new Date("2026-10-19T00:00:00+08:00");
let nextId = 1;
const reservation = (start, end, status = "PENDING", extra = {}) => ({
  id: nextId++, equipmentId: 1, requesterId: 9, requesterName: "林知夏", purpose: "实验",
  startTime: `${start}+08:00`, endTime: `${end}+08:00`, status, createdAt: "2026-10-18T09:00:00+08:00", expiresAt: "2026-10-20T00:00:00+08:00", ...extra,
});

test("overlapping blocks share the column; back-to-back ones reuse a lane", () => {
  const { buildWeekSchedule } = load("lib/schedule.ts");
  const held = reservation("2026-10-20T10:00:00", "2026-10-20T12:00:00", "APPROVED");
  const competing = reservation("2026-10-20T11:00:00", "2026-10-20T13:00:00");
  const after = reservation("2026-10-20T12:00:00", "2026-10-20T13:00:00");
  const alone = reservation("2026-10-21T15:00:00", "2026-10-21T16:00:00");
  const { blocks } = buildWeekSchedule([held, competing, after, alone], monday);
  const byId = new Map(blocks.map((block) => [block.reservation.id, block]));
  assert.deepEqual([byId.get(held.id).lane, byId.get(competing.id).lane, byId.get(after.id).lane], [0, 1, 0]);
  assert.equal(byId.get(held.id).lanes, 2);
  assert.equal(byId.get(after.id).lanes, 2, "same cluster as the two it touches through the competing request");
  assert.deepEqual([byId.get(alone.id).lane, byId.get(alone.id).lanes], [0, 1]);
  assert.equal(byId.get(held.id).kind, "occupied");
  assert.equal(byId.get(competing.id).kind, "pending");
  assert.deepEqual([byId.get(held.id).dayIndex, byId.get(held.id).startMinute, byId.get(held.id).endMinute], [1, 600, 720]);
});

test("a reservation crossing midnight becomes one block per day", () => {
  const { buildWeekSchedule } = load("lib/schedule.ts");
  const overnight = reservation("2026-10-20T22:00:00", "2026-10-21T02:00:00", "PAID");
  const { blocks, hours } = buildWeekSchedule([overnight], monday);
  assert.deepEqual(blocks.map((block) => [block.dayIndex, block.startMinute, block.endMinute, block.continuesBefore, block.continuesAfter]),
    [[1, 1320, 1440, false, true], [2, 0, 120, true, false]]);
  assert.deepEqual(hours, [0, 24], "the visible range widens to fit both halves");
});

test("only reservations still on the calendar are drawn", () => {
  const { buildWeekSchedule } = load("lib/schedule.ts");
  const statuses = ["PENDING", "APPROVED", "AWAITING_PAYMENT", "PAID", "COMPLETED", "CANCELLED", "REJECTED", "EXPIRED", "REFUNDING"];
  const { blocks, hours } = buildWeekSchedule(statuses.map((status) => reservation("2026-10-22T09:00:00", "2026-10-22T10:00:00", status)), monday);
  assert.deepEqual(blocks.map((block) => block.reservation.status).sort(), ["APPROVED", "AWAITING_PAYMENT", "COMPLETED", "PAID", "PENDING"]);
  assert.deepEqual(hours, [8, 20]);
  const outside = buildWeekSchedule([reservation("2026-10-26T09:00:00", "2026-10-26T10:00:00")], monday);
  assert.equal(outside.blocks.length, 0, "day 8 is outside the week");
});

test("conflict summary names the clash, hides nobody's name it does not have", () => {
  const { conflictSummary } = load("lib/conflicts.ts");
  const clash = { id: 12, startTime: "2026-10-20T10:00:00+08:00", endTime: "2026-10-20T12:00:00+08:00", status: "APPROVED", requesterName: "周教授" };
  assert.equal(conflictSummary(reservation("2026-10-20T11:00:00", "2026-10-20T13:00:00", "PENDING", { conflictsWith: [clash] })).text,
    "与 #12 周教授 10月20日 10:00–12:00 冲突");
  assert.equal(conflictSummary(reservation("2026-10-20T11:00:00", "2026-10-20T13:00:00", "PENDING", { conflictsWith: [{ ...clash, requesterName: null }] })).text,
    "与 #12 10月20日 10:00–12:00 冲突");
  assert.equal(conflictSummary(reservation("2026-10-20T11:00:00", "2026-10-20T13:00:00", "PENDING", { conflictsWith: [clash, { ...clash, id: 13 }] })).text,
    "与 2 条已占用预约冲突");
  assert.equal(conflictSummary(reservation("2026-10-20T11:00:00", "2026-10-20T13:00:00", "PENDING", { conflictsWith: [] })), null);
  assert.equal(conflictSummary(reservation("2026-10-20T11:00:00", "2026-10-20T13:00:00", "APPROVED", { conflictsWith: [clash] })), null);
});

const page = (content) => ({ content, page: 0, size: 100, totalElements: content.length, totalPages: 1 });
const teacher = { id: 2, username: "teacher", displayName: "周教授", role: "TEACHER" };
const equipment = page([{ id: 1, code: "SEM-201", name: "扫描电镜", category: "显微成像", status: "AVAILABLE", location: "A101" }]);

function renderView(t, { reservations = [], schedule = [] } = {}) {
  t.mock.timers.enable({ apis: ["Date"], now: new Date("2026-10-19T09:30:00+08:00").getTime() });
  const { ReservationsView } = load("components/views/ReservationsView.tsx");
  return renderToStaticMarkup(React.createElement(ReservationsView, {
    user: teacher, reservations: page(reservations), scheduleReservations: page(schedule), equipment,
    filter: "", canApprove: true, onFilterChange() {}, onLoad() {}, onCreate() {}, onDecide() {}, onCancel() {}, onComplete() {},
  }));
}

test("a conflicting request cannot be approved from the list; reject stays available", (t) => {
  const clash = { id: 12, startTime: "2026-10-20T10:00:00+08:00", endTime: "2026-10-20T12:00:00+08:00", status: "APPROVED", requesterName: "周教授" };
  const html = renderView(t, { reservations: [
    reservation("2026-10-20T11:00:00", "2026-10-20T13:00:00", "PENDING", { conflictsWith: [clash] }),
    reservation("2026-10-20T14:00:00", "2026-10-20T15:00:00", "PENDING", { conflictsWith: [] }),
  ] });
  assert.equal(html.match(/<button class="approve" disabled=""/g)?.length, 1);
  assert.equal(html.match(/<button class="approve"/g)?.length, 2);
  assert.equal(html.match(/class="text-link">拒绝</g)?.length, 2);
  assert.ok(html.includes("与 #12 周教授 10月20日 10:00–12:00 冲突"));
  assert.ok(html.includes("扫描电镜"), "rows show the equipment name instead of a bare id");
});

test("an empty week gets one empty state instead of seven 'nothing here' columns", (t) => {
  const empty = renderView(t);
  assert.ok(empty.includes("未来 7 天还没有预约"));
  assert.ok(!empty.includes("暂无排期"));
  assert.ok(!empty.includes("week-block"));
});

test("the week draws blocks with their kind and marks clashes", (t) => {
  const clash = { id: 12, startTime: "2026-10-20T10:00:00+08:00", endTime: "2026-10-20T12:00:00+08:00", status: "APPROVED", requesterName: "周教授" };
  const html = renderView(t, { schedule: [
    reservation("2026-10-20T10:00:00", "2026-10-20T12:00:00", "APPROVED"),
    reservation("2026-10-20T11:00:00", "2026-10-20T13:00:00", "PENDING", { conflictsWith: [clash] }),
  ] });
  assert.equal(html.match(/class="week-block block-occupied /g)?.length, 1);
  assert.equal(html.match(/class="week-block block-pending block-conflict"/g)?.length, 1);
  assert.equal(html.match(/class="week-head/g)?.length, 7);
  assert.ok(html.includes("now-line"), "today's column shows the current time at 09:30");
});
