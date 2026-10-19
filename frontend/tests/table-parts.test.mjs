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
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true },
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

const page = (content) => ({ content, page: 0, size: 30, totalElements: content.length, totalPages: 1 });
const devices = [
  { id: 1, code: "SEM-201", name: "扫描电镜", category: "显微成像", status: "AVAILABLE", location: "A101" },
  { id: 2, code: "CENT-041", name: "离心机", category: "样品制备", status: "MAINTENANCE", location: "A102" },
];
const technicians = [{ id: 7, username: "tech", displayName: "王师傅" }, { id: 8, username: "tech2", displayName: "李师傅" }];
const order = (id, status, extra = {}) => ({
  id, equipmentId: 1, reporterId: 3, reporterName: "林知夏", title: `故障 ${id}`, description: "无法开机",
  priority: "HIGH", status, assigneeId: null, createdAt: "2026-10-19T09:00:00+08:00", updatedAt: "2026-10-19T10:00:00+08:00", resolvedAt: null, ...extra,
});
const noop = () => {};
const rowOf = (html, attr, id) => html.match(new RegExp(`<tr ${attr}="${id}">[\\s\\S]*?</tr>`))[0];

test("every equipment, reservation and work-order status has a colour", () => {
  const { statusLabels } = loadComponent("lib/labels.ts");
  const { STATE_TONES } = loadComponent("components/table.tsx");
  const priorities = new Set(["LOW", "MEDIUM", "HIGH", "URGENT"]);
  const missing = Object.keys(statusLabels).filter((status) => !priorities.has(status) && !STATE_TONES[status]);
  assert.deepEqual(missing, [], "add new statuses to STATE_TONES in components/table.tsx");
});

function renderOrders(role, orders, props = {}) {
  const { WorkOrdersView } = loadComponent("components/views/WorkOrdersView.tsx");
  const user = { id: 7, username: "u", displayName: "测试", role };
  return renderToStaticMarkup(React.createElement(WorkOrdersView, {
    user, workorders: page(orders), equipment: page(devices), technicians, filter: "",
    canAssign: role === "ADMIN", canClaim: role === "TECHNICIAN", canProcess: role === "ADMIN" || role === "TECHNICIAN",
    onFilterChange: noop, onLoad: noop, onCreate: noop, onTransition: noop, onClaim: noop, ...props,
  }));
}

test("work orders show one forward step per row, by role and status", () => {
  const orders = [
    order(1, "SUBMITTED"),
    order(2, "ASSIGNED", { assigneeId: 7 }),
    order(3, "IN_PROGRESS", { assigneeId: 7 }),
    order(4, "RESOLVED", { assigneeId: 7 }),
    order(5, "ASSIGNED", { assigneeId: 8 }),
  ];
  const tech = renderOrders("TECHNICIAN", orders);
  assert.ok(rowOf(tech, "data-workorder-id", 1).includes('data-action="claim"'));
  assert.ok(rowOf(tech, "data-workorder-id", 2).includes('data-action="start"'));
  assert.ok(rowOf(tech, "data-workorder-id", 3).includes('data-action="resolve"'));
  assert.ok(rowOf(tech, "data-workorder-id", 4).includes('data-action="close"'));
  assert.ok(rowOf(tech, "data-workorder-id", 4).includes('data-action="more"'), "reopen lives in the menu");
  assert.ok(!rowOf(tech, "data-workorder-id", 5).includes("<button"), "someone else's order offers a technician nothing");
  // Technicians get no technician list; their own orders still show their name.
  const own = renderOrders("TECHNICIAN", [order(2, "ASSIGNED", { assigneeId: 7 })], { technicians: [] });
  assert.ok(rowOf(own, "data-workorder-id", 2).includes(">测试<") && !own.includes("维修员 #7"));

  const admin = renderOrders("ADMIN", orders);
  assert.ok(rowOf(admin, "data-workorder-id", 1).includes('data-action="assign"') && rowOf(admin, "data-workorder-id", 1).includes("王师傅"));
  assert.ok(!rowOf(admin, "data-workorder-id", 1).includes('data-action="claim"'));
  assert.ok(rowOf(admin, "data-workorder-id", 5).includes('data-action="start"'), "admins act on any order");
  assert.ok(rowOf(admin, "data-workorder-id", 5).includes('data-action="more"'), "cancel lives in the menu");
});

test("work orders name the device from the lookup and fall back to its id", () => {
  const html = renderOrders("ADMIN", [order(1, "CLOSED"), order(2, "CLOSED", { equipmentId: 99 })]);
  assert.ok(rowOf(html, "data-workorder-id", 1).includes("扫描电镜") && rowOf(html, "data-workorder-id", 1).includes("SEM-201"));
  assert.ok(rowOf(html, "data-workorder-id", 2).includes("设备 #99"));
  assert.ok(rowOf(html, "data-workorder-id", 1).includes('class="state state-idle"'));
  assert.ok(rowOf(html, "data-workorder-id", 1).includes('class="tag tag-high"'));
});

test("reservation rows: decide inline, complete approved ones, cancel your own from the menu", () => {
  const { ReservationsView } = loadComponent("components/views/ReservationsView.tsx");
  const reservation = (id, status, requesterId) => ({
    id, equipmentId: 1, requesterId, requesterName: "林知夏", purpose: `实验 ${id}`, status,
    startTime: "2026-10-20T10:00:00+08:00", endTime: "2026-10-20T11:00:00+08:00", createdAt: "2026-10-19T09:00:00+08:00", expiresAt: "2026-10-20T00:00:00+08:00", conflictsWith: [],
  });
  const html = renderToStaticMarkup(React.createElement(ReservationsView, {
    user: { id: 2, username: "teacher", displayName: "周教授", role: "TEACHER" },
    reservations: page([reservation(1, "PENDING", 3), reservation(2, "APPROVED", 2), reservation(3, "CANCELLED", 2)]),
    scheduleReservations: [], equipment: page(devices), filter: "", canApprove: true,
    onFilterChange: noop, onLoad: noop, onCreate: noop, onDecide: noop, onCancel: noop, onComplete: noop,
  }));
  const row = (id) => rowOf(html, "data-reservation-id", id);
  assert.ok(row(1).includes(">批准<") && row(1).includes(">拒绝<") && !row(1).includes('data-action="more"'));
  assert.ok(row(2).includes('data-action="complete"') && row(2).includes('data-action="more"'), "own approved booking can be cancelled");
  assert.ok(!row(3).includes("<button"), "a cancelled booking has no actions");
  assert.ok(row(1).includes("扫描电镜") && row(1).includes('class="state state-warn"'));
});

async function waitFor(check) {
  for (let attempt = 0; attempt < 200; attempt += 1) {
    if (check()) return;
    await act(async () => { await delay(5); });
    if (check()) return;
  }
  assert.fail("Timed out waiting for React and the requested data");
}

function mountDom(t) {
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
  return { container, root };
}

test("a work-order row menu is portalled outside the scrolling table, even for one row", async (t) => {
  const { container, root } = mountDom(t);
  const { WorkOrdersView } = loadComponent("components/views/WorkOrdersView.tsx");
  const cancelled = [];
  await act(async () => root.render(React.createElement(WorkOrdersView, {
    user: { id: 1, username: "admin", displayName: "管理员", role: "ADMIN" },
    workorders: page([order(1, "ASSIGNED", { assigneeId: 7 })]), equipment: page(devices), technicians, filter: "",
    canAssign: true, canClaim: false, canProcess: true, onFilterChange: noop, onLoad: noop, onCreate: noop,
    onTransition: (item, target) => cancelled.push(`${item.id}:${target}`), onClaim: noop,
  })));
  await act(async () => container.querySelector('[data-action="more"]').click());
  const menu = document.querySelector('[data-menu-for="1"]');
  assert.equal(menu.closest(".table-scroll"), null);
  assert.equal(menu.parentElement, document.body);
  await act(async () => menu.querySelector('[data-menu="cancel"]').click());
  assert.deepEqual(cancelled, ["1:CANCELLED"]);
});

test("work-order device names survive a filtered equipment page", async (t) => {
  const { container } = mountDom(t);
  let listener;
  const { default: Home } = loadComponent("page.tsx", {
    subscribeSession: (next) => { listener = next; return () => {}; },
    clearLegacyAuthStorage() {},
    refreshSession: async () => listener({ accessToken: "t", user: { id: 1, username: "admin", displayName: "管理员", role: "ADMIN" } }),
    api: async (path) => {
      const url = new URL(path, "http://localhost/");
      switch (url.pathname) {
        case "/api/dashboard/stats": return {};
        case "/api/dashboard/trends": return null;
        case "/api/users/technicians": return technicians;
        case "/api/reservations": return page([]);
        case "/api/work-orders": return page([order(1, "CLOSED")]);
        case "/api/equipment/categories": return ["显微成像", "样品制备"];
        case "/api/equipment": {
          const status = url.searchParams.get("status");
          return page(devices.filter((item) => !status || item.status === status));
        }
        default: throw new Error(`Unexpected API request: ${path}`);
      }
    },
  });
  await act(async () => createRoot(container).render(React.createElement(Home)));
  await waitFor(() => container.querySelector('[data-nav="equipment"]'));
  await act(async () => container.querySelector('[data-nav="equipment"]').click());
  await waitFor(() => container.querySelectorAll(".equipment-table tbody tr").length === 2);
  const status = container.querySelector('[data-equipment-filter="status"]');
  await act(async () => { status.value = "MAINTENANCE"; status.dispatchEvent(new window.Event("change", { bubbles: true })); });
  await act(async () => container.querySelector('[data-action="search"]').click());
  await waitFor(() => container.querySelectorAll(".equipment-table tbody tr").length === 1);
  // The work order's device (扫描电镜) is not in the filtered equipment list.
  await act(async () => container.querySelector('[data-nav="workorders"]').click());
  await waitFor(() => container.querySelector('[data-workorder-id="1"]')?.textContent.includes("扫描电镜"));
});
