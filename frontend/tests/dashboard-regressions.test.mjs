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
  test(`dashboard approval shows the reservation range across ${name}`, (t) => {
    t.mock.timers.enable({ apis: ["Date"], now: new Date("2026-10-17T12:00:00+08:00").getTime() });
    const { DashboardView } = loadComponent("components/views/DashboardView.tsx");
    const html = renderToStaticMarkup(React.createElement(DashboardView, {
      user, stats: null, reservations: page([{ ...reservation, startTime, endTime }]),
      workorders: page([]), equipment: page(equipment), busy: false, canApprove: true,
      onViewChange() {}, onOpenCreate() {}, onDecide() {}, onRefresh() {},
    }));
    assert.ok(html.includes(expected), `Missing full reservation range: ${expected}`);
    assert.ok(html.includes(">通过</button>"), "Test must exercise the approval card");
  });
}
