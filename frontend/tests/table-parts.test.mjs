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
  category: "FAULT", priority: "HIGH", status, assigneeId: null, createdAt: "2026-10-19T09:00:00+08:00", updatedAt: "2026-10-19T10:00:00+08:00", resolvedAt: null, ...extra,
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
  assert.ok(rowOf(admin, "data-workorder-id", 1).includes('class="state state-warn"><i></i>待派单</span>'));
  assert.ok(rowOf(admin, "data-workorder-id", 1).includes('data-action="assign"') && rowOf(admin, "data-workorder-id", 1).includes("王师傅"));
  assert.ok(!rowOf(admin, "data-workorder-id", 1).includes('data-action="claim"'));
  assert.ok(rowOf(admin, "data-workorder-id", 5).includes('data-action="start"'), "admins act on any order");
  assert.ok(rowOf(admin, "data-workorder-id", 5).includes('data-action="more"'), "cancel lives in the menu");
});

test("payment discrepancies offer admin processing without assignment or technician actions", () => {
  const orders = ["SUBMITTED", "IN_PROGRESS", "RESOLVED", "CLOSED", "CANCELLED"].map((status, index) =>
    order(index + 1, status, { category: "PAYMENT_DISCREPANCY" }));
  const admin = renderOrders("ADMIN", orders, { technicians: [] });
  assert.ok(rowOf(admin, "data-workorder-id", 1).includes('class="state state-warn"><i></i>待处理</span>'));
  assert.ok(admin.includes("差账单由管理员直接处理"));
  assert.ok(rowOf(admin, "data-workorder-id", 1).includes('data-action="start"'));
  assert.ok(rowOf(admin, "data-workorder-id", 2).includes('data-action="resolve"'));
  assert.ok(rowOf(admin, "data-workorder-id", 2).includes('data-action="more"'), "processing discrepancies can be cancelled");
  assert.ok(rowOf(admin, "data-workorder-id", 3).includes('data-action="close"'));
  assert.ok(rowOf(admin, "data-workorder-id", 3).includes('data-action="more"'), "resolved discrepancies can be reopened");
  for (const id of [4, 5]) assert.ok(!rowOf(admin, "data-workorder-id", id).includes("<button"));
  assert.ok(!admin.includes('data-action="assign"') && !admin.includes("选择维修员"));
  assert.ok(!rowOf(admin, "data-workorder-id", 1).includes("待分派"));
  for (const role of ["TECHNICIAN", "TEACHER", "STUDENT"]) {
    const html = renderOrders(role, orders);
    for (const item of orders) assert.ok(!rowOf(html, "data-workorder-id", item.id).includes("<button"));
  }
});

test("an admin can advance, reopen and cancel discrepancies through the page without an assignee", async (t) => {
  const { container, root } = mountDom(t);
  let listener;
  let status = "SUBMITTED";
  const transitions = [];
  const { default: Home } = loadComponent("page.tsx", {
    subscribeSession: (next) => { listener = next; return noop; },
    clearLegacyAuthStorage: noop,
    refreshSession: async () => listener({ accessToken: "t", user: { id: 1, username: "admin", displayName: "管理员", role: "ADMIN" } }),
    api: async (path, _token, init) => {
      const url = new URL(path, "http://localhost/");
      switch (url.pathname) {
        case "/api/dashboard/stats": return {};
        case "/api/dashboard/trends": return null;
        case "/api/equipment": return page(devices);
        case "/api/users/technicians": return [];
        case "/api/reservations": return page([]);
        case "/api/work-orders": return page([order(42, status, { category: "PAYMENT_DISCREPANCY" })]);
        case "/api/work-orders/42/status": {
          assert.equal(init.method, "PATCH");
          const body = JSON.parse(init.body);
          assert.deepEqual(Object.keys(body), ["targetStatus"]);
          transitions.push(body.targetStatus);
          status = body.targetStatus;
          return {};
        }
        default: throw new Error(`Unexpected API request: ${path}`);
      }
    },
  });
  await act(async () => root.render(React.createElement(Home)));
  await waitFor(() => container.querySelector('[data-nav="workorders"]'));
  await act(async () => container.querySelector('[data-nav="workorders"]').click());
  for (const [action, target, menu] of [
    ["start", "IN_PROGRESS"], ["resolve", "RESOLVED"], ["reopen", "IN_PROGRESS", true],
    ["resolve", "RESOLVED"], ["close", "CLOSED"],
  ]) {
    await waitFor(() => container.querySelector(`[data-action="${menu ? "more" : action}"]`));
    if (menu) await act(async () => container.querySelector('[data-action="more"]').click());
    await act(async () => document.querySelector(`[data-${menu ? "menu" : "action"}="${action}"]`).click());
    await waitFor(() => container.querySelector('[data-workorder-id="42"] .state')?.textContent === ({ IN_PROGRESS: "处理中", RESOLVED: "已解决", CLOSED: "已关闭" })[target]);
  }
  for (const from of ["SUBMITTED", "IN_PROGRESS"]) {
    status = from;
    await act(async () => container.querySelector(".toolbar .secondary").click());
    await waitFor(() => container.querySelector('[data-action="more"]'));
    await act(async () => container.querySelector('[data-action="more"]').click());
    await act(async () => document.querySelector('[data-menu="cancel"]').click());
    await waitFor(() => container.querySelector('[data-workorder-id="42"] .state')?.textContent === "已取消");
  }
  assert.deepEqual(transitions, ["IN_PROGRESS", "RESOLVED", "IN_PROGRESS", "RESOLVED", "CLOSED", "CANCELLED", "CANCELLED"]);
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

const chargedReservation = (status, extra = {}) => ({
  id: 42, equipmentId: 1, requesterId: 3, requesterName: "林知夏", purpose: "收费实验", status,
  startTime: new Date(2026, 10, 2, 10).toISOString(), endTime: new Date(2026, 10, 2, 11).toISOString(),
  paymentDeadline: new Date(2026, 10, 1, 14).toISOString(), ...extra,
});
const paymentOrder = { orderNo: "LF00000042", reservationId: 42, amountCents: 1234, paidCents: 0, refundedCents: 0, status: "AWAITING_PAYMENT" };

test("charged reservation actions and filters follow ownership and lifecycle", () => {
  const { ReservationsView } = loadComponent("components/views/ReservationsView.tsx");
  for (const role of ["STUDENT", "TEACHER", "ADMIN"]) {
    const rows = ["AWAITING_PAYMENT", "PAID", "REFUNDING", "CANCELLED", "EXPIRED", "COMPLETED", "REJECTED"]
      .map((status, index) => chargedReservation(status, { id: index + 1 }));
    rows.push(chargedReservation("AWAITING_PAYMENT", { id: 8, requesterId: 9 }));
    const html = renderToStaticMarkup(React.createElement(ReservationsView, {
      user: { id: 3, username: "u", displayName: "测试", role }, reservations: page(rows),
      scheduleReservations: [], equipment: page(devices), filter: "", canApprove: role !== "STUDENT", busy: false,
      onFilterChange: noop, onLoad: noop, onCreate: noop, onDecide: noop, onPay: noop, onCancel: noop, onComplete: noop,
    }));
    assert.ok(rowOf(html, "data-reservation-id", 1).includes('data-action="pay"'), `${role} can pay their own order`);
    for (const id of [1, 2]) assert.ok(rowOf(html, "data-reservation-id", id).includes('data-action="more"'));
    for (const id of [3, 4, 5, 6, 7, 8]) assert.ok(!rowOf(html, "data-reservation-id", id).includes("<button"));
    for (const status of ["AWAITING_PAYMENT", "PAID", "REFUNDING"]) assert.ok(html.includes(`<option value="${status}">`));
  }
});

async function mountChargedReservation(t, initialStatus, paymentApi) {
  const { container, root } = mountDom(t);
  let listener;
  let reservation = chargedReservation(initialStatus);
  const requests = [];
  const { default: Home } = loadComponent("page.tsx", {
    subscribeSession: (next) => { listener = next; return noop; }, clearLegacyAuthStorage: noop,
    refreshSession: async () => listener({ accessToken: "student-token", user: { id: 3, username: "student", displayName: "林知夏", role: "STUDENT" } }),
    api: async (path, token, init = {}) => {
      requests.push({ path, token, init });
      const url = new URL(path, "http://localhost/");
      switch (url.pathname) {
        case "/api/dashboard/stats": return {};
        case "/api/dashboard/trends": return null;
        case "/api/equipment": return page(devices);
        case "/api/work-orders": return page([]);
        case "/api/reservations": return page([reservation]);
        default: return paymentApi(path, init, (status) => { reservation = { ...reservation, status }; });
      }
    },
  });
  await act(async () => root.render(React.createElement(Home)));
  await waitFor(() => container.querySelector('[data-nav="reservations"]'));
  await act(async () => container.querySelector('[data-nav="reservations"]').click());
  await waitFor(() => container.querySelector('[data-reservation-id="42"]'));
  return { container, requests };
}

test("the page confirms the order amount, pays, then cancels into refunding until the callback arrives", async (t) => {
  let finishRefund;
  let order = paymentOrder;
  const { container, requests } = await mountChargedReservation(t, "AWAITING_PAYMENT", async (path, init, setStatus) => {
    if (path === "/api/payments/orders/LF00000042") return order;
    if (path === "/api/payments/orders/LF00000042/pay") {
      assert.equal(init.method, "POST");
      setStatus("PAID");
      order = { ...paymentOrder, paidCents: 1234, status: "PAID" };
      return order;
    }
    assert.equal(path, "/api/reservations/42/cancel");
    assert.equal(init.method, "PATCH");
    setStatus("REFUNDING");
    finishRefund = () => setStatus("CANCELLED");
    return chargedReservation("REFUNDING");
  });
  await act(async () => container.querySelector('[data-action="pay"]').click());
  await waitFor(() => container.querySelector('[role="dialog"]'));
  const dialog = container.querySelector('[role="dialog"]');
  assert.match(dialog.textContent, /12\.34/);
  assert.ok(!requests.some(({ path }) => path.endsWith("/pay")), "opening the dialog never charges");
  await act(async () => dialog.querySelector('[data-action="confirm-pay"]').click());
  await waitFor(() => container.querySelector('[data-reservation-id="42"]')?.textContent.includes("已支付"));
  assert.equal(container.querySelector('[role="dialog"]'), null);
  await act(async () => container.querySelector('[data-action="more"]').click());
  await act(async () => document.querySelector('[data-menu="cancel"]').click());
  await waitFor(() => container.querySelector('[data-action="confirm-refund"]'));
  await act(async () => container.querySelector('[data-action="confirm-refund"]').click());
  await waitFor(() => container.querySelector('[data-reservation-id="42"]')?.textContent.includes("退款中"));
  assert.ok(container.querySelector(".toast").textContent.includes("退款处理中"));
  assert.equal(container.querySelector('[data-action="more"]'), null);
  finishRefund();
  await act(async () => [...container.querySelectorAll("button")].find((button) => button.textContent === "筛选").click());
  await waitFor(() => container.querySelector('[data-reservation-id="42"]')?.textContent.includes("已取消"));
  assert.ok(requests.every(({ token }) => token === "student-token"));
});

test("paid cancellation requires confirmation of the full refund amount and dismissing the dialog sends no cancellation", async (t) => {
  const { container, requests } = await mountChargedReservation(t, "PAID", async (path, init, setStatus) => {
    if (path === "/api/payments/orders/LF00000042") return { ...paymentOrder, paidCents: 1234, status: "PAID" };
    assert.equal(path, "/api/reservations/42/cancel");
    assert.equal(init.method, "PATCH");
    setStatus("REFUNDING");
    return chargedReservation("REFUNDING");
  });
  const cancellations = () => requests.filter(({ path }) => path.endsWith("/cancel"));
  const openConfirmation = async () => {
    await act(async () => container.querySelector('[data-action="more"]').click());
    await act(async () => document.querySelector('[data-menu="cancel"]').click());
    assert.equal(cancellations().length, 0, "clicking cancel must not send the cancellation request");
    await waitFor(() => container.querySelector('[data-action="confirm-refund"]'));
    const dialog = container.querySelector('[role="dialog"]');
    assert.match(dialog.textContent, /全额退款.*12\.34/);
    assert.equal(cancellations().length, 0, "loading and displaying the amount does not cancel");
    return dialog;
  };
  let dialog = await openConfirmation();
  await act(async () => [...dialog.querySelectorAll("button")].find((button) => button.textContent === "暂不取消").click());
  assert.equal(container.querySelector('[role="dialog"]'), null);
  assert.equal(cancellations().length, 0);
  dialog = await openConfirmation();
  await act(async () => dialog.querySelector('[aria-label="关闭"]').click());
  assert.equal(container.querySelector('[role="dialog"]'), null);
  assert.equal(cancellations().length, 0);
  dialog = await openConfirmation();
  await act(async () => dialog.querySelector('[data-action="confirm-refund"]').click());
  await waitFor(() => container.querySelector('[data-reservation-id="42"]')?.textContent.includes("退款中"));
  assert.equal(cancellations().length, 1);
  assert.equal(container.querySelector('[role="dialog"]'), null);
});

test("an unpaid charged reservation can be cancelled without initiating payment", async (t) => {
  const { container, requests } = await mountChargedReservation(t, "AWAITING_PAYMENT", async (path, init, setStatus) => {
    assert.equal(path, "/api/reservations/42/cancel");
    assert.equal(init.method, "PATCH");
    setStatus("CANCELLED");
    return chargedReservation("CANCELLED");
  });
  await act(async () => container.querySelector('[data-action="more"]').click());
  await act(async () => document.querySelector('[data-menu="cancel"]').click());
  await waitFor(() => container.querySelector('[data-reservation-id="42"]')?.textContent.includes("已取消"));
  assert.ok(!requests.some(({ path }) => path.startsWith("/api/payments")));
});

for (const failure of ["lookup", "pay"]) {
  test(`payment ${failure} errors are visible and never report success`, async (t) => {
    const { container } = await mountChargedReservation(t, "AWAITING_PAYMENT", async (path) => {
      if (failure === "pay" && path === "/api/payments/orders/LF00000042") return paymentOrder;
      throw new Error("支付窗口已过期");
    });
    await act(async () => container.querySelector('[data-action="pay"]').click());
    if (failure === "pay") {
      await waitFor(() => container.querySelector('[data-action="confirm-pay"]'));
      await act(async () => container.querySelector('[data-action="confirm-pay"]').click());
    }
    await waitFor(() => container.querySelector(".toast.error"));
    assert.ok(container.querySelector(".toast.error").textContent.includes("支付窗口已过期"));
    assert.ok(container.querySelector('[data-reservation-id="42"]').textContent.includes("待支付"));
    assert.equal(Boolean(container.querySelector('[role="dialog"]')), failure === "pay");
  });
}

test("a delayed payment stays pending and disables another confirmation while the request is in flight", async (t) => {
  let resolvePay;
  const { container } = await mountChargedReservation(t, "AWAITING_PAYMENT", async (path) => {
    if (path === "/api/payments/orders/LF00000042") return paymentOrder;
    assert.equal(path, "/api/payments/orders/LF00000042/pay");
    return new Promise((resolve) => { resolvePay = () => resolve(paymentOrder); });
  });
  await act(async () => container.querySelector('[data-action="pay"]').click());
  await waitFor(() => container.querySelector('[data-action="confirm-pay"]'));
  await act(async () => container.querySelector('[data-action="confirm-pay"]').click());
  assert.equal(container.querySelector('[data-action="confirm-pay"]').disabled, true);
  assert.equal(container.querySelector('[data-action="pay"]').disabled, true);
  await act(async () => resolvePay());
  await waitFor(() => !container.querySelector('[role="dialog"]'));
  assert.ok(container.querySelector('[data-reservation-id="42"]').textContent.includes("待支付"));
  assert.ok(container.querySelector(".toast").textContent.includes("支付请求已提交"));
});

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
  const { container, root } = mountDom(t);
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
  await act(async () => root.render(React.createElement(Home)));
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

// The lookup only decorates rows; its failure must not hide rows that did load.
for (const [view, rowSelector, rows] of [
  ["workorders", "[data-workorder-id]", 1],
  ["reservations", "[data-reservation-id]", 1],
  ["dashboard", ".row-item", 2],
]) {
  test(`${view}: a failed equipment lookup still shows the rows, with id fallbacks and a notice`, async (t) => {
    const { container, root } = mountDom(t);
    let listener;
    const reservation = {
      id: 5, equipmentId: 1, requesterId: 1, requesterName: "林知夏", purpose: "实验", status: "PENDING",
      startTime: "2026-10-20T10:00:00+08:00", endTime: "2026-10-20T11:00:00+08:00", createdAt: "2026-10-19T09:00:00+08:00", expiresAt: "2026-10-20T00:00:00+08:00", conflictsWith: [],
    };
    const { default: Home } = loadComponent("page.tsx", {
      subscribeSession: (next) => { listener = next; return () => {}; },
      clearLegacyAuthStorage() {},
      refreshSession: async () => listener({ accessToken: "t", user: { id: 9, username: "admin", displayName: "管理员", role: "ADMIN" } }),
      api: async (path) => {
        const url = new URL(path, "http://localhost/");
        switch (url.pathname) {
          case "/api/dashboard/stats": return {};
          case "/api/dashboard/trends": return null;
          case "/api/users/technicians": return technicians;
          case "/api/reservations": return page(url.searchParams.has("from") ? [] : [reservation]);
          case "/api/work-orders": return page([order(1, "SUBMITTED")]);
          case "/api/equipment": throw new Error("名录接口 500");
          default: throw new Error(`Unexpected API request: ${path}`);
        }
      },
    });
    await act(async () => root.render(React.createElement(Home)));
    await waitFor(() => container.querySelector('[data-nav="dashboard"]'));
    if (view !== "dashboard") await act(async () => container.querySelector(`[data-nav="${view}"]`).click());
    await waitFor(() => container.querySelectorAll(rowSelector).length === rows && container.querySelector(".toast.error"));
    assert.ok(container.textContent.includes("设备 #1"), "rows fall back to the device id");
    assert.ok(container.querySelector(".toast.error").textContent.includes("设备名录加载失败"));
    assert.ok(!container.querySelector(".toast.error").textContent.includes("500"), "the notice names the lookup, not the raw error");
  });
}
