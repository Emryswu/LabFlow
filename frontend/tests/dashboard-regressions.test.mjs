import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { runInThisContext } from "node:vm";
import { setTimeout as delay } from "node:timers/promises";
import { JSDOM } from "jsdom";
import React, { act } from "react";
import { createRoot } from "react-dom/client";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";

process.env.TZ = "Asia/Shanghai";
const appDirectory = fileURLToPath(new URL("../app/", import.meta.url));
const require = createRequire(import.meta.url);

// Run the real TSX components with React; only replace the API boundary.
function loadComponent(path, apiModule) {
  const cache = new Map();
  function load(filename) {
    if (filename === resolve(appDirectory, "lib/api.ts")) return apiModule;
    if (cache.has(filename)) return cache.get(filename).exports;
    const loadedModule = { exports: {} };
    cache.set(filename, loadedModule);
    const output = ts.transpileModule(readFileSync(filename, "utf8"), {
      compilerOptions: {
        module: ts.ModuleKind.CommonJS,
        target: ts.ScriptTarget.ES2022,
        jsx: ts.JsxEmit.ReactJSX,
        esModuleInterop: true,
      },
    }).outputText;
    const localRequire = (specifier) => {
      if (!specifier.startsWith(".")) return require(specifier);
      const base = resolve(dirname(filename), specifier);
      return load([`${base}.ts`, `${base}.tsx`].find(existsSync));
    };
    runInThisContext(`(function(require, module, exports) {${output}\n})`, { filename })(localRequire, loadedModule, loadedModule.exports);
    return loadedModule.exports;
  }
  return load(resolve(appDirectory, path));
}

const equipment = [
  { id: 1, code: "EQ-1", name: "可用显微镜", category: "显微成像", status: "AVAILABLE", location: "A101" },
  { id: 2, code: "EQ-2", name: "维修离心机", category: "样品制备", status: "MAINTENANCE", location: "A102" },
];
const page = (content) => ({ content, page: 0, size: 100, totalElements: content.length, totalPages: 1 });
const user = { id: 1, username: "teacher", displayName: "测试教师", role: "TEACHER" };
const reservation = {
  id: 1, equipmentId: 1, requesterId: 2, requesterName: "学生", purpose: "实验",
  status: "PENDING", createdAt: "2026-10-17T09:00:00+08:00",
  startTime: "2026-10-20T23:00:00+08:00", endTime: "2026-10-21T02:00:00+08:00",
};

async function waitFor(check) {
  for (let attempt = 0; attempt < 100; attempt++) {
    await act(async () => { await delay(5); });
    if (check()) return;
  }
  assert.fail("Timed out waiting for React and the requested data");
}

test("late dashboard equipment cannot replace a completed equipment filter", async (t) => {
  const dom = new JSDOM("<div id='root'></div>", { url: "http://localhost/" });
  const globals = { window: dom.window, document: dom.window.document, IS_REACT_ACT_ENVIRONMENT: true };
  const previous = Object.fromEntries(Object.keys(globals).map((key) => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  for (const [key, value] of Object.entries(globals)) {
    Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
  }
  const container = document.getElementById("root");
  const root = createRoot(container);
  t.after(async () => {
    await act(async () => root.unmount());
    dom.window.close();
    for (const key of Object.keys(globals)) {
      if (previous[key]) Object.defineProperty(globalThis, key, previous[key]);
      else delete globalThis[key];
    }
  });

  let listener;
  let releaseDashboard;
  let dashboardEquipmentRequested = false;
  const dashboardResponse = new Promise((resolve) => { releaseDashboard = resolve; });
  const { default: Home } = loadComponent("page.tsx", {
    subscribeSession: (next) => { listener = next; return () => {}; },
    clearLegacyAuthStorage() {},
    refreshSession: async () => listener({ accessToken: "test-token", user }),
    api: async (path) => {
      const url = new URL(path, "http://localhost/");
      switch (url.pathname) {
        case "/api/dashboard/stats": return { equipmentTotal: 2, equipmentAvailable: 1, equipmentMaintenance: 1 };
        case "/api/reservations": return page([reservation]);
        case "/api/work-orders": return page([]);
        case "/api/equipment":
          if (url.searchParams.get("size") === "100") {
            dashboardEquipmentRequested = true;
            return dashboardResponse;
          }
          return page(equipment.filter((item) => !url.searchParams.has("status") || item.status === url.searchParams.get("status")));
        default: throw new Error(`Unexpected API request: ${path}`);
      }
    },
  });

  await act(async () => root.render(React.createElement(Home)));
  await waitFor(() => dashboardEquipmentRequested);
  await act(async () => container.querySelector('[data-nav="equipment"]').click());
  await waitFor(() => container.querySelectorAll(".equipment-card").length === 2);
  const filter = container.querySelector(".toolbar select");
  await act(async () => {
    filter.value = "MAINTENANCE";
    filter.dispatchEvent(new window.Event("change", { bubbles: true }));
  });
  await act(async () => container.querySelector(".toolbar .secondary").click());
  await waitFor(() => container.querySelectorAll(".equipment-card").length === 1);

  await act(async () => { releaseDashboard(page(equipment)); await dashboardResponse; });
  assert.equal(filter.value, "MAINTENANCE");
  assert.deepEqual([...container.querySelectorAll(".equipment-card h4")].map((node) => node.textContent), ["维修离心机"]);

  // The dashboard still gets the complete name lookup, including the device
  // excluded by the equipment page's maintenance filter.
  await act(async () => container.querySelector('[data-nav="dashboard"]').click());
  await waitFor(() => container.querySelector(".row-main b")?.textContent === "可用显微镜");
});

for (const [name, startTime, endTime, expected] of [
  ["same day", "2026-10-20T09:00:00+08:00", "2026-10-20T10:00:00+08:00", "10月20日 09:00–10:00"],
  ["midnight", "2026-10-20T23:00:00+08:00", "2026-10-21T02:00:00+08:00", "10月20日 23:00–10月21日 02:00"],
  ["month boundary", "2026-10-31T23:00:00+08:00", "2026-11-01T02:00:00+08:00", "10月31日 23:00–11月1日 02:00"],
  ["year boundary", "2026-12-31T23:00:00+08:00", "2027-01-01T02:00:00+08:00", "12月31日 23:00–1月1日 02:00"],
]) {
  test(`dashboard and conflict hints show the reservation range across ${name}`, (t) => {
    t.mock.timers.enable({ apis: ["Date"], now: new Date("2026-10-17T12:00:00+08:00").getTime() });
    const { DashboardView } = loadComponent("components/views/DashboardView.tsx");
    const pending = { ...reservation, startTime, endTime, conflictsWith: [{ id: 101, startTime, endTime, status: "APPROVED", requesterName: "占用人" }] };
    const html = renderToStaticMarkup(React.createElement(DashboardView, {
      user, stats: null, reservations: page([pending]),
      workorders: page([]), equipment: page(equipment), busy: false, canApprove: true,
      onViewChange() {}, onOpenCreate() {}, onDecide() {}, onRefresh() {},
    }));
    assert.ok(html.includes(expected), `Missing full reservation range: ${expected}`);
    assert.ok(html.includes(">通过</button>"), "Test must exercise the approval card");
    assert.ok(html.includes(`与 #101 占用人 ${expected} 冲突`), "Dashboard conflict must include both dates");
    const { ReservationsView } = loadComponent("components/views/ReservationsView.tsx");
    const list = renderToStaticMarkup(React.createElement(ReservationsView, {
      user, reservations: page([pending]), scheduleReservations: [], equipment: page(equipment),
      filter: "", canApprove: true, onFilterChange() {}, onLoad() {}, onCreate() {}, onDecide() {}, onCancel() {}, onComplete() {},
    }));
    assert.ok(list.includes(`与 #101 占用人 ${expected} 冲突`), "List conflict must include both dates");
  });
}

// ---- Trend sparklines on the stat cards ----

const trendDays = Array.from({ length: 14 }, (_, index) => `2026-10-${String(4 + index).padStart(2, "0")}`);
const trendsFixture = {
  days: trendDays,
  // previous 7 days: 5, last 7 days: 6 -> up 20%
  reservationsCreated: [1, 1, 1, 1, 1, 0, 0, 0, 1, 0, 2, 0, 3, 0],
  reservationsScheduled: Array(14).fill(0),
  // previous 0, last 2 -> "new"
  workOrdersCreated: [0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1],
  workOrdersResolved: Array(14).fill(1),
  paymentDiscrepanciesCreated: null,
};

test("week-over-week splits the 14 days and names the change", () => {
  const { weekOverWeek } = loadComponent("lib/trends.ts");
  const created = weekOverWeek(trendsFixture, "reservationsCreated");
  assert.deepEqual(created.bars, [0, 1, 0, 2, 0, 3, 0]);
  assert.deepEqual(created.days, trendDays.slice(7));
  assert.equal(created.current, 6);
  assert.equal(created.previous, 5);
  assert.deepEqual(created.change, { kind: "up", percent: 20 });
  assert.deepEqual(weekOverWeek(trendsFixture, "workOrdersCreated").change, { kind: "new" });
  assert.deepEqual(weekOverWeek(trendsFixture, "workOrdersResolved").change, { kind: "flat" });
  assert.deepEqual(weekOverWeek(trendsFixture, "reservationsScheduled").change, { kind: "flat" });
  const fewer = { ...trendsFixture, reservationsCreated: [2, 2, 2, 2, 2, 0, 0, 0, 0, 0, 0, 0, 0, 3] };
  assert.deepEqual(weekOverWeek(fewer, "reservationsCreated").change, { kind: "down", percent: 70 });
  // Missing data never produces a half-drawn chart.
  assert.equal(weekOverWeek(trendsFixture, "paymentDiscrepanciesCreated"), null);
  assert.equal(weekOverWeek(null, "reservationsCreated"), null);
  assert.equal(weekOverWeek({ ...trendsFixture, reservationsCreated: [1, 2, 3] }, "reservationsCreated"), null);
});

test("stat cards draw the last 7 days and the change when trends are present", () => {
  const { DashboardView } = loadComponent("components/views/DashboardView.tsx");
  const render = (trends) => renderToStaticMarkup(React.createElement(DashboardView, {
    user, stats: null, trends, reservations: page([]), workorders: page([]), equipment: page(equipment),
    busy: false, canApprove: true, onViewChange() {}, onOpenCreate() {}, onDecide() {}, onRefresh() {},
  }));
  const html = render(trendsFixture);
  // Teacher: pending (submitted), today's schedule, fault reports; available equipment is a snapshot.
  assert.equal(html.match(/class="stat-trend"/g)?.length, 3);
  assert.equal(html.match(/class="spark-bar"|class="spark-empty"/g)?.length, 21);
  assert.ok(html.includes("近7天提交 <b>6</b>"));
  assert.ok(html.includes(">20%</span>"));
  assert.ok(html.includes(">新增</span>"));
  assert.ok(html.includes("<title>10月16日 3 条</title>"));
  assert.ok(!render(null).includes("stat-trend"), "Without trends the cards only show their numbers");
});

for (const [name, trendsResponse, expectedTrends] of [
  ["a failed trends request leaves the cards without charts", () => Promise.reject(new Error("boom")), 0],
  ["trends load alongside the dashboard", () => Promise.resolve(trendsFixture), 3],
]) {
  test(`dashboard: ${name}`, async (t) => {
    const dom = new JSDOM("<div id='root'></div>", { url: "http://localhost/" });
    const globals = { window: dom.window, document: dom.window.document, IS_REACT_ACT_ENVIRONMENT: true };
    const previous = Object.fromEntries(Object.keys(globals).map((key) => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
    for (const [key, value] of Object.entries(globals)) {
      Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
    }
    const container = document.getElementById("root");
    const root = createRoot(container);
    t.after(async () => {
      await act(async () => root.unmount());
      dom.window.close();
      for (const key of Object.keys(globals)) {
        if (previous[key]) Object.defineProperty(globalThis, key, previous[key]);
        else delete globalThis[key];
      }
    });

    let listener;
    let trendsRequested = false;
    const { default: Home } = loadComponent("page.tsx", {
      subscribeSession: (next) => { listener = next; return () => {}; },
      clearLegacyAuthStorage() {},
      refreshSession: async () => listener({ accessToken: "test-token", user }),
      api: async (path) => {
        const url = new URL(path, "http://localhost/");
        switch (url.pathname) {
          case "/api/dashboard/stats": return { equipmentTotal: 2, equipmentAvailable: 1, reservationsPending: 4 };
          case "/api/dashboard/trends": trendsRequested = true; return trendsResponse();
          case "/api/reservations": return page([reservation]);
          case "/api/work-orders": return page([]);
          case "/api/equipment": return page(equipment);
          default: throw new Error(`Unexpected API request: ${path}`);
        }
      },
    });

    await act(async () => root.render(React.createElement(Home)));
    await waitFor(() => trendsRequested && container.querySelector(".row-main b")?.textContent === "可用显微镜");
    await act(async () => { await delay(20); });
    assert.equal(container.querySelectorAll(".stat-card").length, 4);
    assert.equal(container.querySelector(".stat-card strong").textContent, "4条");
    assert.equal(container.querySelectorAll(".stat-trend").length, expectedTrends);
    assert.equal(container.querySelector(".toast.error"), null, "A trends failure is not worth an error toast");
  });
}

for (const scenario of ["approval", "newer refresh", "session change"]) {
  test(`slow trends stay independent of ${scenario}`, async (t) => {
    const dom = new JSDOM("<div id='root'></div>", { url: "http://localhost/" });
    const globals = { window: dom.window, document: dom.window.document, IS_REACT_ACT_ENVIRONMENT: true };
    const previous = Object.fromEntries(Object.keys(globals).map((key) => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
    for (const [key, value] of Object.entries(globals)) {
      Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
    }
    const container = document.getElementById("root");
    const root = createRoot(container);
    t.after(async () => {
      await act(async () => root.unmount());
      dom.window.close();
      for (const key of Object.keys(globals)) {
        if (previous[key]) Object.defineProperty(globalThis, key, previous[key]);
        else delete globalThis[key];
      }
    });

    let listener;
    let decisionSucceeded = false;
    let trendRequests = 0;
    let slowSignal;
    let releaseTrends;
    const slowTrends = new Promise((resolve) => { releaseTrends = resolve; });
    const staleTrends = { ...trendsFixture, reservationsCreated: Array(14).fill(9) };
    const { default: Home } = loadComponent("page.tsx", {
      subscribeSession: (next) => { listener = next; return () => {}; },
      clearLegacyAuthStorage() {},
      refreshSession: async () => listener({ accessToken: "test-token", user }),
      api: async (path, _token, init) => {
        const url = new URL(path, "http://localhost/");
        switch (url.pathname) {
          case "/api/dashboard/stats": return { equipmentTotal: 2, equipmentAvailable: 1, reservationsPending: decisionSucceeded ? 1 : 2 };
          case "/api/dashboard/trends":
            trendRequests++;
            if ((scenario === "approval" && decisionSucceeded) || (scenario !== "approval" && trendRequests === 1)) {
              slowSignal = init.signal;
              return slowTrends;
            }
            return trendsFixture;
          case "/api/reservations/1/decision": decisionSucceeded = true; return {};
          case "/api/reservations": return page([
            { ...reservation, status: decisionSucceeded ? "APPROVED" : "PENDING" },
            { ...reservation, id: 2 },
          ]);
          case "/api/work-orders": return page([]);
          case "/api/equipment": return page(equipment);
          default: throw new Error(`Unexpected API request: ${path}`);
        }
      },
    });

    await act(async () => root.render(React.createElement(Home)));
    await waitFor(() => container.querySelectorAll(".decision .solid-button").length === 2);
    if (scenario === "approval") {
      await act(async () => container.querySelector(".decision .solid-button").click());
      await waitFor(() => decisionSucceeded && container.querySelectorAll(".decision .solid-button").length === 1);
      assert.equal(container.querySelector(".decision .solid-button").disabled, false,
        "A pending decorative request must not block the next approval");
      assert.equal(container.querySelector(".loading-bar"), null);
    } else {
      if (scenario === "newer refresh") {
        await act(async () => container.querySelector(".hero-chips button").click());
      } else {
        await act(async () => listener({ accessToken: "next-token", user: { ...user, id: 2, username: "teacher2" } }));
        assert.equal(slowSignal.aborted, true, "Session reset must abort independent trend requests too");
      }
      await waitFor(() => trendRequests === 2 && container.querySelector(".trend-text b")?.textContent === "6");
    }

    // Resolve even after abort: a late network response must still be ignored.
    await act(async () => { releaseTrends(staleTrends); await slowTrends; });
    const expected = scenario === "approval" ? "63" : "6";
    assert.equal(container.querySelector(".trend-text b").textContent, expected);
  });
}

test("the reservation calendar asks only for the 7 days it draws", async (t) => {
  const dom = new JSDOM("<div id='root'></div>", { url: "http://localhost/" });
  const globals = { window: dom.window, document: dom.window.document, IS_REACT_ACT_ENVIRONMENT: true };
  const previous = Object.fromEntries(Object.keys(globals).map((key) => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  for (const [key, value] of Object.entries(globals)) {
    Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
  }
  const container = document.getElementById("root");
  const root = createRoot(container);
  t.after(async () => {
    await act(async () => root.unmount());
    dom.window.close();
    for (const key of Object.keys(globals)) {
      if (previous[key]) Object.defineProperty(globalThis, key, previous[key]);
      else delete globalThis[key];
    }
  });

  let listener;
  const scheduleRequests = [];
  const { default: Home } = loadComponent("page.tsx", {
    subscribeSession: (next) => { listener = next; return () => {}; },
    clearLegacyAuthStorage() {},
    refreshSession: async () => listener({ accessToken: "test-token", user }),
    api: async (path) => {
      const url = new URL(path, "http://localhost/");
      switch (url.pathname) {
        case "/api/dashboard/stats": return {};
        case "/api/dashboard/trends": return null;
        case "/api/work-orders": return page([]);
        case "/api/equipment": return page(equipment);
        case "/api/reservations":
          if (url.searchParams.has("from")) scheduleRequests.push(url.searchParams);
          return page([]);
        default: throw new Error(`Unexpected API request: ${path}`);
      }
    },
  });

  await act(async () => root.render(React.createElement(Home)));
  await waitFor(() => container.querySelector('[data-nav="reservations"]'));
  await act(async () => container.querySelector('[data-nav="reservations"]').click());
  await waitFor(() => scheduleRequests.length === 1);
  const [query] = scheduleRequests;
  const from = new Date(query.get("from"));
  const to = new Date(query.get("to"));
  assert.equal(from.getHours() + from.getMinutes() + from.getSeconds(), 0, "starts at local midnight");
  assert.equal(Math.round((to - from) / 86_400_000), 7);
  assert.equal(query.get("sort"), "startTime,asc");
  await waitFor(() => container.textContent.includes("未来 7 天还没有预约"));
});


for (const failsLaterPage of [false, true]) {
  test(`calendar ${failsLaterPage ? "reports a later-page error instead of publishing a partial week" : "loads every page before equipment filtering"}`, async (t) => {
    t.mock.timers.enable({ apis: ["Date"], now: new Date("2026-10-19T12:00:00+08:00").getTime() });
    const dom = new JSDOM("<div id='root'></div>", { url: "http://localhost/" });
    const globals = { window: dom.window, document: dom.window.document, IS_REACT_ACT_ENVIRONMENT: true };
    const previous = Object.fromEntries(Object.keys(globals).map((key) => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
    for (const [key, value] of Object.entries(globals)) {
      Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
    }
    const container = document.getElementById("root");
    const root = createRoot(container);
    t.after(async () => {
      await act(async () => root.unmount());
      dom.window.close();
      for (const key of Object.keys(globals)) {
        if (previous[key]) Object.defineProperty(globalThis, key, previous[key]);
        else delete globalThis[key];
      }
    });

    const bookings = Array.from({ length: 201 }, (_, index) => ({
      ...reservation, id: index + 1, equipmentId: index === 200 ? 2 : 1, status: "PAID",
      startTime: "2026-10-20T10:00:00+08:00", endTime: "2026-10-20T11:00:00+08:00",
    }));
    let listener;
    const requests = [];
    const { default: Home } = loadComponent("page.tsx", {
      subscribeSession: (next) => { listener = next; return () => {}; },
      clearLegacyAuthStorage() {},
      refreshSession: async () => listener({ accessToken: "test-token", user }),
      api: async (path, token, options) => {
        const url = new URL(path, "http://localhost/");
        switch (url.pathname) {
          case "/api/dashboard/stats": return {};
          case "/api/dashboard/trends": return null;
          case "/api/work-orders": return page([]);
          case "/api/equipment": return page(equipment);
          case "/api/reservations": {
            if (!url.searchParams.has("from")) return page([]);
            const index = Number(url.searchParams.get("page") ?? 0);
            requests.push({ query: url.searchParams, token, signal: options.signal });
            if (failsLaterPage && index === 1) throw new Error("排期第二页加载失败");
            return { content: bookings.slice(index * 100, (index + 1) * 100), page: index, size: 100, totalElements: bookings.length, totalPages: 3 };
          }
          default: throw new Error(`Unexpected API request: ${path}`);
        }
      },
    });

    await act(async () => root.render(React.createElement(Home)));
    await waitFor(() => container.querySelector('[data-nav="reservations"]'));
    await act(async () => container.querySelector('[data-nav="reservations"]').click());
    await waitFor(() => container.querySelector(".week-block") || container.textContent.includes("排期第二页加载失败"));
    if (failsLaterPage) {
      assert.ok(container.textContent.includes("排期第二页加载失败"), "Must expose the error instead of silently using page one");
      assert.equal(container.querySelectorAll(".week-block").length, 0);
      return;
    }
    const select = container.querySelector('[aria-label="按设备筛选排期"]');
    await act(async () => {
      select.value = "2";
      select.dispatchEvent(new window.Event("change", { bubbles: true }));
    });
    assert.equal(container.querySelectorAll(".week-block").length, 1, "Device 2's paid booking is on page three");
    assert.ok(container.querySelector(".week-block").textContent.includes("维修离心机"));
    assert.ok(!container.textContent.includes("这台设备未来 7 天还没有预约"));
    assert.deepEqual(requests.map(({ query }) => Number(query.get("page") ?? 0)), [0, 1, 2]);
    for (const request of requests) {
      assert.equal(request.query.get("from"), requests[0].query.get("from"));
      assert.equal(request.query.get("to"), requests[0].query.get("to"));
      assert.deepEqual(request.query.getAll("sort"), ["startTime,asc", "id,asc"], "Equal start times need stable pagination");
      assert.equal(request.token, "test-token");
      assert.equal(request.signal, requests[0].signal, "Every page belongs to the same cancellable session request");
    }
  });
}
