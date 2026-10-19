import assert from "node:assert/strict";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";
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
const javaRoot = fileURLToPath(new URL("../../src/main/java/com/arthur/labops/", import.meta.url));
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

function javaSources(directory) {
  return readdirSync(directory).flatMap((name) => {
    const path = join(directory, name);
    if (statSync(path).isDirectory()) return javaSources(path);
    return name.endsWith(".java") ? [readFileSync(path, "utf8")] : [];
  });
}

/** Every action the backend can write to the audit log, read from the services themselves. */
function backendAuditActions() {
  const sources = javaSources(javaRoot);
  const actions = new Set();
  for (const source of sources) {
    for (const match of source.matchAll(/auditLogService\s*\.\s*record(?:System)?\(\s*(?:\w+\s*,\s*)?"([A-Z_]+)"\s*,/g)) actions.add(match[1]);
  }
  // Two calls build the action from an enum: "WORK_ORDER_" + target and "RESERVATION_" + decision.
  const joined = sources.join("\n");
  assert.match(joined, /"WORK_ORDER_" \+ target\.name\(\)/, "dynamic work-order action changed; update this test");
  assert.match(joined, /"RESERVATION_" \+ request\.decision\(\)\.name\(\)/, "dynamic reservation action changed; update this test");
  const workOrderStatuses = readFileSync(join(javaRoot, "workorder/WorkOrderStatus.java"), "utf8")
    .match(/enum WorkOrderStatus \{([\s\S]*?)\}/)[1].match(/[A-Z_]+/g);
  // A work order is created as SUBMITTED, never transitioned to it.
  for (const status of workOrderStatuses.filter((status) => status !== "SUBMITTED")) actions.add(`WORK_ORDER_${status}`);
  for (const decision of ["APPROVED", "REJECTED"]) actions.add(`RESERVATION_${decision}`);
  return actions;
}

const page = (content, extra = {}) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1, ...extra });
const log = (id, actorRole, action, extra = {}) => ({
  id, actorUsername: actorRole === "SYSTEM" ? "system" : actorRole.toLowerCase(), actorRole, action,
  // Built in local time so the rendered date and clock match on any CI time zone.
  targetType: "WORK_ORDER", targetId: 2, details: "工单从 ASSIGNED 流转到 IN_PROGRESS", createdAt: new Date(2026, 9, 19, 9, 5, 7).toISOString(), ...extra,
});
const noop = () => {};

test("every audit action the backend records has a Chinese label", () => {
  const { actionLabels } = loadComponent("lib/labels.ts");
  const actions = backendAuditActions();
  assert.ok(actions.size > 20, `scan found only ${actions.size} actions`);
  const missing = [...actions].filter((action) => !actionLabels[action]);
  assert.deepEqual(missing, [], "add these to actionLabels in lib/labels.ts");
});

test("every audit role has a tag style, and the demo cards match the seeded accounts", () => {
  const { actorRoleLabels } = loadComponent("lib/labels.ts");
  const { ROLE_STYLES } = loadComponent("components/roleTag.tsx");
  assert.deepEqual(Object.keys(actorRoleLabels).filter((role) => !ROLE_STYLES[role]), []);

  const { DEMO_ACCOUNTS } = loadComponent("components/LoginView.tsx");
  const seeds = readFileSync(join(javaRoot, "user/DemoUserInitializer.java"), "utf8");
  for (const account of DEMO_ACCOUNTS) {
    assert.ok(seeds.includes(`createIfMissing("${account.username}", "${account.password}"`), `${account.username} is not seeded with that password`);
    assert.ok(seeds.includes(`UserRole.${account.role});`) && ROLE_STYLES[account.role], account.role);
  }
});

test("audit rows show Chinese roles, readable details, the target and a system avatar", () => {
  const { AuditView, emptyAuditFilter } = loadComponent("components/views/AuditView.tsx");
  const html = renderToStaticMarkup(React.createElement(AuditView, {
    auditLogs: page([
      log(1, "TECHNICIAN", "WORK_ORDER_IN_PROGRESS"),
      log(2, "SYSTEM", "PAYMENT_SUCCEEDED", { targetType: "RESERVATION", targetId: 11, details: "支付成功" }),
      log(3, "SYSTEM", "RECONCILIATION_UNKNOWN_ORDER", { targetType: "PAYMENT", targetId: null, details: "未知订单" }),
    ]),
    filter: emptyAuditFilter, onFilterChange: noop, onSearch: noop, onReset: noop, onPage: noop,
  }));
  const row = (id) => html.match(new RegExp(`<tr data-audit-id="${id}">[\\s\\S]*?</tr>`))[0];
  assert.ok(row(1).includes("开始维修") && row(1).includes('data-role-tag="TECHNICIAN"') && row(1).includes("维修员"));
  assert.ok(row(1).includes("工单从 已派单 流转到 处理中"), "status codes in details are translated");
  assert.ok(row(1).includes("工单 #2") && row(1).includes("2026-10-19") && row(1).includes("09:05:07"));
  assert.ok(row(2).includes("支付成功") && row(2).includes("系统") && row(2).includes("预约 #11"));
  assert.ok(!row(2).includes(">S<"), "system rows get an icon, not a letter avatar");
  assert.ok(row(3).includes(">支付<") && !row(3).includes("#null"));
  assert.ok(!html.includes("TECHNICIAN<") && !html.includes("PAYMENT_SUCCEEDED<"));
});

test("audit filter options do not depend on the rows on screen", () => {
  const { AuditView, emptyAuditFilter } = loadComponent("components/views/AuditView.tsx");
  const { actionLabels, actorRoleLabels } = loadComponent("lib/labels.ts");
  const html = renderToStaticMarkup(React.createElement(AuditView, {
    auditLogs: page([log(1, "TEACHER", "RESERVATION_APPROVED")]),
    filter: emptyAuditFilter, onFilterChange: noop, onSearch: noop, onReset: noop, onPage: noop,
  }));
  for (const action of Object.keys(actionLabels)) assert.ok(html.includes(`value="${action}"`), action);
  for (const role of Object.keys(actorRoleLabels)) assert.ok(html.includes(`value="${role}"`), role);
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

function choose(select, value) {
  select.value = value;
  select.dispatchEvent(new window.Event("change", { bubbles: true }));
}

test("audit search sends the filters, paging keeps the applied filter, reset clears it", async (t) => {
  const { container, root } = mountDom(t);
  let listener;
  const auditRequests = [];
  const { default: Home } = loadComponent("page.tsx", {
    subscribeSession: (next) => { listener = next; return () => {}; },
    clearLegacyAuthStorage() {},
    refreshSession: async () => listener({ accessToken: "t", user: { id: 1, username: "admin", displayName: "管理员", role: "ADMIN" } }),
    api: async (path) => {
      const url = new URL(path, "http://localhost/");
      switch (url.pathname) {
        case "/api/dashboard/stats": return {};
        case "/api/dashboard/trends": return null;
        case "/api/users/technicians": return [];
        case "/api/reservations": case "/api/work-orders": case "/api/equipment": return page([]);
        case "/api/audit-logs": {
          auditRequests.push(url.searchParams);
          const current = Number(url.searchParams.get("page"));
          return page([log(100 + current, "TEACHER", "RESERVATION_APPROVED")], { page: current, totalElements: 45, totalPages: 3 });
        }
        default: throw new Error(`Unexpected API request: ${path}`);
      }
    },
  });
  await act(async () => root.render(React.createElement(Home)));
  await waitFor(() => container.querySelector('[data-nav="audit"]'));
  await act(async () => container.querySelector('[data-nav="audit"]').click());
  await waitFor(() => container.querySelector('[data-audit-id="100"]'));
  assert.equal(auditRequests.at(-1).get("actorRole"), null);

  await act(async () => choose(container.querySelector('[data-audit-filter="role"]'), "TEACHER"));
  await act(async () => container.querySelector('[data-action="search"]').click());
  await waitFor(() => auditRequests.at(-1).get("actorRole") === "TEACHER");
  assert.equal(auditRequests.at(-1).get("page"), "0");

  // An edit that was never searched must not leak into paging.
  await act(async () => choose(container.querySelector('[data-audit-filter="action"]'), "EQUIPMENT_CREATED"));
  await act(async () => container.querySelector('[data-action="next-page"]').click());
  await waitFor(() => container.querySelector('[data-audit-id="101"]'));
  assert.equal(auditRequests.at(-1).get("page"), "1");
  assert.equal(auditRequests.at(-1).get("actorRole"), "TEACHER");
  assert.equal(auditRequests.at(-1).get("action"), null);
  assert.ok(container.textContent.includes("第 2 / 3 页"));

  await act(async () => container.querySelector('[data-action="reset"]').click());
  await waitFor(() => container.querySelector('[data-audit-id="100"]'));
  assert.equal(auditRequests.at(-1).get("actorRole"), null);
  assert.equal(auditRequests.at(-1).get("page"), "0");
  assert.equal(container.querySelector('[data-audit-filter="role"]').value, "");
  assert.equal(container.querySelector('[data-action="prev-page"]').disabled, true);
});

test("a slower response for an earlier audit page does not overwrite the latest one", async (t) => {
  const { container, root } = mountDom(t);
  let listener;
  let releaseFirstPage;
  const { default: Home } = loadComponent("page.tsx", {
    subscribeSession: (next) => { listener = next; return () => {}; },
    clearLegacyAuthStorage() {},
    refreshSession: async () => listener({ accessToken: "t", user: { id: 1, username: "admin", displayName: "管理员", role: "ADMIN" } }),
    api: async (path) => {
      const url = new URL(path, "http://localhost/");
      if (url.pathname !== "/api/audit-logs") {
        return url.pathname === "/api/users/technicians" ? [] : url.pathname === "/api/dashboard/trends" ? null : page([]);
      }
      const current = Number(url.searchParams.get("page"));
      const body = page([log(100 + current, "ADMIN", "EQUIPMENT_CREATED")], { page: current, totalElements: 45, totalPages: 3 });
      // The second request for page 0 (after "previous") is held back until page 2 has landed.
      if (current === 0 && releaseFirstPage === null) return new Promise((done) => { releaseFirstPage = () => done(body); });
      return body;
    },
  });
  await act(async () => root.render(React.createElement(Home)));
  await waitFor(() => container.querySelector('[data-nav="audit"]'));
  await act(async () => container.querySelector('[data-nav="audit"]').click());
  await waitFor(() => container.querySelector('[data-audit-id="100"]'));
  await act(async () => container.querySelector('[data-action="next-page"]').click());
  await waitFor(() => container.querySelector('[data-audit-id="101"]'));
  releaseFirstPage = null;
  await act(async () => container.querySelector('[data-action="prev-page"]').click());
  await act(async () => container.querySelector('[data-action="next-page"]').click());
  await waitFor(() => container.querySelector('[data-audit-id="102"]') && releaseFirstPage);
  await act(async () => releaseFirstPage());
  await act(async () => { await delay(20); });
  assert.ok(container.querySelector('[data-audit-id="102"]'), "the late page-0 response was applied over page 2");
});

test("a demo card fills the login form with that seeded account", async (t) => {
  const { container, root } = mountDom(t);
  const { default: Home } = loadComponent("page.tsx", {
    subscribeSession: () => () => {},
    clearLegacyAuthStorage() {},
    refreshSession: async () => {},
    api: async (path) => { throw new Error(`Unexpected API request: ${path}`); },
  });
  await act(async () => root.render(React.createElement(Home)));
  await waitFor(() => container.querySelector('[data-demo="TECHNICIAN"]'));
  await act(async () => container.querySelector('[data-demo="TECHNICIAN"]').click());
  assert.equal(container.querySelector('input[autocomplete="username"]').value, "technician");
  assert.equal(container.querySelector('input[autocomplete="current-password"]').value, "tech123");
  assert.equal(container.querySelector('[data-demo="TECHNICIAN"]').getAttribute("aria-pressed"), "true");
  assert.equal(container.querySelector('[data-demo="STUDENT"]').getAttribute("aria-pressed"), "false");
});
