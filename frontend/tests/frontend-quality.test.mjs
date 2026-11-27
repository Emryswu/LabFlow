import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { dirname, resolve } from "node:path";
import test from "node:test";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";
import { runInThisContext } from "node:vm";
import { JSDOM } from "jsdom";
import React, { act } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";

const appDirectory = fileURLToPath(new URL("../app/", import.meta.url));
const require = createRequire(import.meta.url);

function loadComponent(path, apiModule) {
  const cache = new Map();
  function load(filename) {
    if (apiModule && filename === resolve(appDirectory, "lib/api.ts")) return apiModule;
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
    runInThisContext(`(function(require, module, exports) {${output}\n})`, { filename })(
      localRequire,
      loadedModule,
      loadedModule.exports,
    );
    return loadedModule.exports;
  }
  return load(resolve(appDirectory, path));
}

async function loadApiEsm() {
  const source = await readFile(new URL("../app/lib/api.ts", import.meta.url), "utf8");
  const output = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const encoded = Buffer.from(output).toString("base64");
  return import(`data:text/javascript;base64,${encoded}#${Date.now()}-${Math.random()}`);
}

async function mountDom(t) {
  const dom = new JSDOM("<div id='root'></div>", { url: "http://localhost/" });
  const globals = { window: dom.window, document: dom.window.document, IS_REACT_ACT_ENVIRONMENT: true };
  const previous = Object.fromEntries(
    Object.keys(globals).map((key) => [key, Object.getOwnPropertyDescriptor(globalThis, key)]),
  );
  for (const [key, value] of Object.entries(globals)) {
    Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
  }
  const { createRoot } = await import("react-dom/client");
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
  return { container, root, dom };
}

async function waitFor(check) {
  for (let attempt = 0; attempt < 200; attempt += 1) {
    if (check()) return;
    await act(async () => { await delay(5); });
    if (check()) return;
  }
  assert.fail("Timed out waiting for React state update");
}

function toLocalInputValue(date) {
  const pad = (value) => String(value).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

test("labels formatting handles local dates, currency cents, indexing, and invalid date fallbacks", (t) => {
  t.mock.timers.enable({ apis: ["Date"], now: new Date(2026, 9, 17, 12, 0).getTime() });
  const {
    errorMessage,
    formatDate,
    formatDateRange,
    formatRelativeMoment,
    splitDateTime,
    formatCurrencyCents,
    indexById,
  } = loadComponent("lib/labels.ts");

  assert.equal(errorMessage(new Error("自定义错误"), "默认"), "自定义错误");
  assert.equal(errorMessage(new Error(""), "默认"), "默认");
  assert.equal(errorMessage("unexpected", "默认"), "默认");

  const start = new Date(2026, 9, 17, 9, 5, 7).toISOString();
  const end = new Date(2026, 9, 17, 11, 30).toISOString();
  assert.match(formatDate(start), /10[./-]17\s+09:05/);
  assert.equal(formatDate("not-a-date"), "—");
  assert.equal(formatDate(null), "—");

  assert.equal(formatDateRange(start, end), "10月17日 09:05–11:30");
  assert.equal(formatDateRange("not-a-date", end), "—");

  assert.equal(formatRelativeMoment(start), "今天 09:05");
  assert.equal(formatRelativeMoment("not-a-date"), "—");

  assert.deepEqual(splitDateTime(start), { day: "2026-10-17", time: "09:05:07" });
  assert.deepEqual(splitDateTime("invalid"), { day: "—", time: "" });

  assert.equal(formatCurrencyCents(1234), "¥12.34");
  const indexed = indexById([{ id: 3, name: "SEM" }, { id: 8, name: "XRD" }]);
  assert.equal(indexed.get(3)?.name, "SEM");
  assert.equal(indexById(null).size, 0);
});

test("api error parsing handles null or non-JSON bodies safely and formats fallbacks", async () => {
  const api = await loadApiEsm();
  const originalFetch = globalThis.fetch;
  try {
    globalThis.fetch = async () => Response.json(null, { status: 500 });
    await assert.rejects(api.loginRequest("student", "bad"), /登录失败（500）/);

    globalThis.fetch = async () => new Response("gateway error", { status: 502 });
    await assert.rejects(api.api("/api/equipment", "token"), /请求失败（502）/);

    globalThis.fetch = async () => Response.json({ message: "权限不足" }, { status: 403 });
    await assert.rejects(api.api("/api/equipment", "token"), { message: "权限不足" });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("Modal closes on Escape and removes its listener on unmount; Toast sets live region roles", async (t) => {
  const { container, root, dom } = await mountDom(t);
  const { Modal, Toast } = loadComponent("components/ui.tsx");

  let closed = 0;
  await act(async () => {
    root.render(
      React.createElement(Modal, {
        title: "测试弹窗",
        subtitle: "副标题",
        onClose: () => { closed += 1; },
      }, React.createElement("p", null, "内容")),
    );
  });
  await act(async () => {
    document.dispatchEvent(new dom.window.KeyboardEvent("keydown", { key: "Enter" }));
  });
  assert.equal(closed, 0);
  await act(async () => {
    document.dispatchEvent(new dom.window.KeyboardEvent("keydown", { key: "Escape" }));
  });
  assert.equal(closed, 1);

  await act(async () => { root.render(React.createElement("div")); });
  await act(async () => {
    document.dispatchEvent(new dom.window.KeyboardEvent("keydown", { key: "Escape" }));
  });
  assert.equal(closed, 1, "unmounted Modal must not keep Escape listener active");
  assert.equal(container.querySelector('[role="dialog"]'), null);

  const infoHtml = renderToStaticMarkup(React.createElement(Toast, { toast: { message: "已保存" } }));
  const errHtml = renderToStaticMarkup(React.createElement(Toast, { toast: { message: "出错了", error: true } }));
  assert.ok(infoHtml.includes('role="status"') && infoHtml.includes('aria-live="polite"'));
  assert.ok(errHtml.includes('role="alert"') && errHtml.includes('aria-live="assertive"'));
});

test("technician list request aborts on logout so late admin data cannot leak", async (t) => {
  const { container, root } = await mountDom(t);
  let listener;
  let techSignal;
  let resolveTech;
  const techPromise = new Promise((resolve) => { resolveTech = resolve; });

  const { default: Home } = loadComponent("page.tsx", {
    subscribeSession: (next) => { listener = next; return () => {}; },
    clearLegacyAuthStorage() {},
    refreshSession: async () => listener({
      accessToken: "admin-token",
      user: { id: 1, username: "admin", displayName: "管理员", role: "ADMIN" },
    }),
    api: async (path, _token, init) => {
      const url = new URL(path, "http://localhost/");
      switch (url.pathname) {
        case "/api/dashboard/stats": return {};
        case "/api/dashboard/trends": return null;
        case "/api/reservations": return { content: [], page: 0, size: 12, totalElements: 0, totalPages: 0 };
        case "/api/work-orders": return { content: [], page: 0, size: 12, totalElements: 0, totalPages: 0 };
        case "/api/equipment": return { content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 };
        case "/api/users/technicians":
          techSignal = init?.signal;
          return techPromise;
        default: throw new Error(`Unexpected API request: ${path}`);
      }
    },
  });

  await act(async () => root.render(React.createElement(Home)));
  await waitFor(() => techSignal !== undefined);
  assert.equal(techSignal.aborted, false);

  await act(async () => listener(null));
  assert.equal(techSignal.aborted, true, "logout must abort in-flight technician lookup");
  await act(async () => {
    resolveTech([{ id: 7, username: "tech", displayName: "王师傅" }]);
    await techPromise;
  });
  assert.ok(container.querySelector(".login-card"));
});

test("openCreate aborts slow equipment lookup on session switch without opening modal", async (t) => {
  const { container, root } = await mountDom(t);
  let listener;
  let createSignal;
  let resolveEquipment;
  const equipmentPromise = new Promise((resolve) => { resolveEquipment = resolve; });
  let initialLookupDone = false;

  const { default: Home } = loadComponent("page.tsx", {
    subscribeSession: (next) => { listener = next; return () => {}; },
    clearLegacyAuthStorage() {},
    refreshSession: async () => listener({
      accessToken: "student-token",
      user: { id: 3, username: "student", displayName: "学生", role: "STUDENT" },
    }),
    api: async (path, _token, init) => {
      const url = new URL(path, "http://localhost/");
      switch (url.pathname) {
        case "/api/dashboard/stats": return {};
        case "/api/dashboard/trends": return null;
        case "/api/reservations": return { content: [], page: 0, size: 12, totalElements: 0, totalPages: 0 };
        case "/api/work-orders": return { content: [], page: 0, size: 12, totalElements: 0, totalPages: 0 };
        case "/api/equipment":
          if (!url.searchParams.has("sort")) {
            createSignal = init?.signal;
            return equipmentPromise;
          }
          initialLookupDone = true;
          return { content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 };
        default: throw new Error(`Unexpected API request: ${path}`);
      }
    },
  });

  await act(async () => root.render(React.createElement(Home)));
  await waitFor(() => initialLookupDone && container.querySelector(".quick-card"));
  await act(async () => container.querySelector(".quick-card").click());
  await waitFor(() => createSignal !== undefined);
  assert.equal(createSignal.aborted, false);

  await act(async () => listener({
    accessToken: "teacher-token",
    user: { id: 2, username: "teacher", displayName: "教师", role: "TEACHER" },
  }));
  assert.equal(createSignal.aborted, true, "session switch must abort openCreate equipment lookup");

  await act(async () => {
    resolveEquipment({ content: [{ id: 1, code: "EQ-1", name: "设备", category: "显微成像", location: "A101", manufacturer: null, model: null, responsiblePerson: null, purchaseDate: null, description: null, status: "AVAILABLE" }], page: 0, size: 100, totalElements: 1, totalPages: 1 });
    await equipmentPromise;
  });
  assert.equal(container.querySelector('[role="dialog"]'), null, "stale openCreate must not open modal in new session");
});

test("notify clears previous timer and reservation submit rejects reversed time ranges locally", async (t) => {
  const { container, root, dom } = await mountDom(t);
  let listener;
  const mutations = [];
  const clearedTimers = [];
  const originalClearTimeout = dom.window.clearTimeout.bind(dom.window);
  dom.window.clearTimeout = (id) => {
    clearedTimers.push(id);
    return originalClearTimeout(id);
  };

  const { default: Home } = loadComponent("page.tsx", {
    subscribeSession: (next) => { listener = next; return () => {}; },
    clearLegacyAuthStorage() {},
    refreshSession: async () => listener({
      accessToken: "student-token",
      user: { id: 3, username: "student", displayName: "学生", role: "STUDENT" },
    }),
    api: async (path, _token, init) => {
      const url = new URL(path, "http://localhost/");
      if (init?.method && init.method !== "GET") {
        mutations.push(`${init.method} ${url.pathname}`);
        return {};
      }
      switch (url.pathname) {
        case "/api/dashboard/stats": return {};
        case "/api/dashboard/trends": return null;
        case "/api/reservations": return { content: [], page: 0, size: 12, totalElements: 0, totalPages: 0 };
        case "/api/work-orders": return { content: [], page: 0, size: 12, totalElements: 0, totalPages: 0 };
        case "/api/equipment":
          return {
            content: [{ id: 1, code: "EQ-1", name: "显微镜", category: "显微成像", location: "A101", manufacturer: null, model: null, responsiblePerson: null, purchaseDate: null, description: null, status: "AVAILABLE" }],
            page: 0, size: 100, totalElements: 1, totalPages: 1,
          };
        default: throw new Error(`Unexpected API request: ${path}`);
      }
    },
  });

  await act(async () => root.render(React.createElement(Home)));
  await waitFor(() => container.querySelector(".quick-card"));
  await act(async () => container.querySelector(".quick-card").click());
  await waitFor(() => container.querySelector('[role="dialog"]'));

  const modal = container.querySelector('[role="dialog"]');
  const select = modal.querySelector("select");
  const textarea = modal.querySelector("textarea");
  const [startInput, endInput] = modal.querySelectorAll('input[type="datetime-local"]');
  const startLocal = toLocalInputValue(new Date(2026, 9, 20, 11, 0));
  const endLocal = toLocalInputValue(new Date(2026, 9, 20, 10, 0));
  const setReactInput = (element, value) => {
    const proto = Object.getPrototypeOf(element);
    const setter = Object.getOwnPropertyDescriptor(proto, "value")?.set;
    setter.call(element, value);
    const eventName = element.tagName === "SELECT" ? "change" : "input";
    element.dispatchEvent(new dom.window.Event(eventName, { bubbles: true }));
  };

  for (const [element, value] of [
    [select, "1"],
    [textarea, "测试用途"],
    [startInput, startLocal],
    [endInput, endLocal],
  ]) {
    await act(async () => {
      setReactInput(element, value);
    });
  }

  const submitBtn = modal.querySelector("button.primary");
  await act(async () => {
    submitBtn.click();
  });
  assert.ok(container.querySelector(".toast.error")?.textContent.includes("结束时间需晚于开始时间"));
  assert.equal(mutations.length, 0, "invalid time range must not call POST /api/reservations");

  // Triggering a second notification clears the previous toast timer instead of racing it.
  const beforeSecond = clearedTimers.length;
  await act(async () => {
    submitBtn.click();
  });
  assert.ok(clearedTimers.length > beforeSecond, "subsequent toast must clear previous timer");
});

test("dashboard pure helpers compute sparkline, ring segments, slices, and reservation windows", () => {
  const { buildSparkline, buildEquipmentRing, deriveDashboardSlices, dayTitle, trendChangeText } = loadComponent("lib/dashboard.ts");
  const { parseReservationWindow } = loadComponent("lib/forms.ts");

  assert.equal(dayTitle("2026-10-17"), "10月17日");
  assert.equal(trendChangeText({ kind: "flat" }), "持平");
  assert.equal(trendChangeText({ kind: "new" }), "新增");
  assert.equal(trendChangeText({ kind: "up", percent: 25 }), "25%");

  const spark = buildSparkline({
    days: ["2026-10-11", "2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15", "2026-10-16", "2026-10-17"],
    bars: [0, 2, 0, 4, 0, 0, 1],
    current: 7,
    previous: 5,
    change: { kind: "up", percent: 40 },
  });
  assert.equal(spark.bars[0].height, 2);
  assert.equal(spark.bars[0].className, "spark-empty");
  assert.equal(spark.bars[3].height, 24);
  assert.equal(spark.bars[3].className, "spark-bar");

  const ring = buildEquipmentRing({
    equipmentTotal: 10, equipmentAvailable: 5, equipmentInUse: 2, equipmentMaintenance: 2, equipmentRetired: 1,
    usersTotal: 0, reservationsPending: 0, reservationsApproved: 0, reservationsToday: 0,
    workOrdersOpen: 0, workOrdersUrgent: 0, paymentDiscrepanciesOpen: 0,
  });
  assert.equal(ring.total, 10);
  assert.deepEqual(ring.segments.map((s) => s.percent), [50, 20, 20, 10]);

  const slices = deriveDashboardSlices(
    [
      { id: 1, equipmentId: 1, requesterId: 1, requesterName: "A", purpose: "p", startTime: new Date(2026, 9, 20, 14).toISOString(), endTime: new Date(2026, 9, 20, 15).toISOString(), status: "APPROVED", createdAt: "", expiresAt: "", paymentDeadline: null },
      { id: 2, equipmentId: 1, requesterId: 1, requesterName: "A", purpose: "p", startTime: new Date(2026, 9, 20, 10).toISOString(), endTime: new Date(2026, 9, 20, 11).toISOString(), status: "PENDING", createdAt: "", expiresAt: "", paymentDeadline: null },
    ],
    [
      { id: 1, equipmentId: 1, equipmentStatus: "AVAILABLE", reporterId: 1, reporterName: "A", title: "t", description: "d", category: "FAULT", priority: "URGENT", status: "SUBMITTED", assigneeId: null, createdAt: "", updatedAt: "", resolvedAt: null },
      { id: 2, equipmentId: 1, equipmentStatus: "RETIRED", reporterId: 1, reporterName: "A", title: "t", description: "d", category: "FAULT", priority: "LOW", status: "SUBMITTED", assigneeId: null, createdAt: "", updatedAt: "", resolvedAt: null },
    ],
  );
  assert.deepEqual(slices.upcomingSchedule.map((r) => r.id), [2, 1]);
  assert.deepEqual(slices.claimPool.map((w) => w.id), [1]);
  assert.deepEqual(slices.urgentOrders.map((w) => w.id), [1]);

  assert.equal(parseReservationWindow("bad", "2026-10-20T10:00").ok, false);
  assert.equal(parseReservationWindow(toLocalInputValue(new Date(2026, 9, 20, 11, 0)), toLocalInputValue(new Date(2026, 9, 20, 10, 0))).ok, false);
  assert.equal(parseReservationWindow(toLocalInputValue(new Date(2026, 9, 20, 10, 0)), toLocalInputValue(new Date(2026, 9, 20, 11, 0))).ok, true);
});
