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

const device = (id, name, category, status, extra = {}) => ({
  id, code: `DEV-${id}`, name, category, status, location: `A10${id}`,
  manufacturer: "ZEISS", model: "M-1", responsiblePerson: "周教授", purchaseDate: "2022-09-15", description: "测试设备", ...extra,
});
const fleet = [
  device(1, "可用显微镜", "显微成像", "AVAILABLE"),
  device(2, "使用中色谱仪", "分析检测", "IN_USE"),
  device(3, "维修离心机", "样品制备", "MAINTENANCE"),
  device(4, "退役光谱仪", "光谱分析", "RETIRED"),
  device(5, "自定义仪器", "自制装置", "AVAILABLE", { manufacturer: null, model: null, responsiblePerson: null }),
];
const page = (content, total = content.length) => ({ content, page: 0, size: 30, totalElements: total, totalPages: 1 });
const emptyFilter = { keyword: "", status: "", category: "" };
const noop = () => {};

function renderView(props = {}) {
  const { EquipmentView } = loadComponent("components/views/EquipmentView.tsx");
  return renderToStaticMarkup(React.createElement(EquipmentView, {
    equipment: page(fleet), filter: emptyFilter, canManage: true, canReserve: true,
    onFilterChange: noop, onLoad: noop, onReset: noop, onCreate: noop, onReserve: noop, onReport: noop, onRetire: noop, onRestore: noop,
    ...props,
  }));
}

const rowOf = (html, id) => html.match(new RegExp(`<tr data-equipment-id="${id}">[\\s\\S]*?</tr>`))[0];

test("equipment renders as a table with category tags and state dots", () => {
  const html = renderView();
  assert.ok(html.includes('class="equipment-table"'));
  assert.equal(html.match(/<tr data-equipment-id=/g).length, 5);
  assert.ok(!html.includes("equipment-card"), "the old card grid is gone");
  assert.ok(rowOf(html, 1).includes('class="cat-tag tone-blue">显微成像'));
  assert.ok(rowOf(html, 1).includes('class="state state-ok"'));
  assert.ok(rowOf(html, 3).includes('class="state state-warn"'));
  // A free-text category keeps a neutral tag and stays selectable in the filter.
  assert.ok(rowOf(html, 5).includes('class="cat-tag tone-graphite">自制装置'));
  assert.ok(html.includes('<option value="自制装置">自制装置</option>'));
  assert.ok(rowOf(html, 5).includes("厂商型号待补充") && rowOf(html, 5).includes("待分配"));
  assert.ok(html.includes("新增设备"));
});

test("booking follows isReservableStatus: in-use devices stay bookable, maintenance and retired do not", () => {
  const html = renderView();
  const reserve = (id) => rowOf(html, id).match(/<button[^>]*data-action="reserve"[^>]*>/)[0];
  assert.ok(!reserve(1).includes("disabled"));
  assert.ok(!reserve(2).includes("disabled"), "IN_USE devices can be booked for another slot");
  assert.ok(reserve(3).includes('disabled=""') && reserve(3).includes("维护中"));
  assert.ok(reserve(4).includes('disabled=""') && reserve(4).includes("已退役"));
});

test("roles decide which actions exist", () => {
  const technician = renderView({ canReserve: false, canManage: false });
  assert.ok(!technician.includes('data-action="reserve"'), "technicians cannot book");
  assert.ok(!technician.includes("新增设备"));
  // Retired devices offer nothing to a non-manager, so there is no empty menu either.
  assert.ok(!rowOf(technician, 4).includes('data-action="more"'));
  assert.ok(rowOf(technician, 3).includes('data-action="more"'), "anyone can report a fault");
});

test("a page that is not the full result says so", () => {
  assert.ok(renderView({ equipment: page(fleet, 42) }).includes("当前显示最近添加的 5 台"));
  assert.ok(!renderView().includes("table-note"));
  assert.ok(renderView({ equipment: page([]) }).includes("没有符合条件的设备"));
});

async function waitFor(check) {
  for (let attempt = 0; attempt < 200; attempt += 1) {
    if (check()) return;
    await act(async () => { await delay(5); });
    if (check()) return;
  }
  assert.fail("Timed out waiting for React and the requested data");
}

/** Mounts the real page signed in as `role` and opens the equipment view. */
async function openEquipment(t, role) {
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
  const equipmentQueries = [];
  const mutations = [];
  const { default: Home } = loadComponent("page.tsx", {
    subscribeSession: (next) => { listener = next; return () => {}; },
    clearLegacyAuthStorage() {},
    refreshSession: async () => listener({ accessToken: "test-token", user: { id: 1, username: role.toLowerCase(), displayName: "测试用户", role } }),
    api: async (path, _token, init) => {
      const url = new URL(path, "http://localhost/");
      if (init?.method && init.method !== "GET") { mutations.push(`${init.method} ${url.pathname}`); return {}; }
      switch (url.pathname) {
        case "/api/dashboard/stats": return {};
        case "/api/dashboard/trends": return null;
        case "/api/reservations": return page([]);
        case "/api/work-orders": return page([]);
        case "/api/equipment": {
          if (url.searchParams.get("size") === "30") equipmentQueries.push(url.searchParams);
          const status = url.searchParams.get("status");
          const category = url.searchParams.get("category");
          return page(fleet.filter((item) => (!status || item.status === status) && (!category || item.category === category)));
        }
        default: throw new Error(`Unexpected API request: ${path}`);
      }
    },
  });

  await act(async () => root.render(React.createElement(Home)));
  await waitFor(() => container.querySelector('[data-nav="equipment"]'));
  await act(async () => container.querySelector('[data-nav="equipment"]').click());
  await waitFor(() => container.querySelectorAll(".equipment-table tbody tr").length === fleet.length);
  return { container, equipmentQueries, mutations };
}

const row = (container, id) => container.querySelector(`[data-equipment-id="${id}"]`);
async function change(select, value) {
  await act(async () => {
    select.value = value;
    select.dispatchEvent(new window.Event("change", { bubbles: true }));
  });
}

test("category filter is sent on search and reset clears every filter", async (t) => {
  const { container, equipmentQueries } = await openEquipment(t, "TEACHER");
  await change(container.querySelector('[data-equipment-filter="category"]'), "显微成像");
  await change(container.querySelector('[data-equipment-filter="status"]'), "AVAILABLE");
  assert.equal(equipmentQueries.length, 1, "changing a filter does not search by itself");
  await act(async () => container.querySelector('[data-action="search"]').click());
  await waitFor(() => container.querySelectorAll(".equipment-table tbody tr").length === 1);
  assert.equal(equipmentQueries.at(-1).get("category"), "显微成像");
  assert.equal(equipmentQueries.at(-1).get("status"), "AVAILABLE");

  await act(async () => container.querySelector('[data-action="reset"]').click());
  await waitFor(() => container.querySelectorAll(".equipment-table tbody tr").length === fleet.length);
  const last = equipmentQueries.at(-1);
  assert.ok(!last.has("category") && !last.has("status") && !last.has("keyword"));
  assert.equal(container.querySelector('[data-equipment-filter="category"]').value, "");
  assert.equal(container.querySelector('[data-equipment-filter="status"]').value, "");
});

test("booking from a row opens the reservation form with that device chosen", async (t) => {
  const { container } = await openEquipment(t, "STUDENT");
  await act(async () => row(container, 2).querySelector('[data-action="reserve"]').click());
  await waitFor(() => container.querySelector(".modal"));
  const select = container.querySelector(".modal select");
  assert.equal(select.value, "2");
  assert.ok(select.selectedOptions[0].textContent.includes("使用中色谱仪"));
});

test("the row menu reports a fault with the device chosen; only managers can retire or restore", async (t) => {
  const { container } = await openEquipment(t, "STUDENT");
  assert.equal(row(container, 4).querySelector('[data-action="more"]'), null, "a retired device offers a student nothing");
  await act(async () => row(container, 3).querySelector('[data-action="more"]').click());
  const entries = [...row(container, 3).querySelectorAll("[data-menu]")].map((node) => node.dataset.menu);
  assert.deepEqual(entries, ["report"]);
  await act(async () => row(container, 3).querySelector('[data-menu="report"]').click());
  await waitFor(() => container.querySelector(".modal"));
  assert.equal(container.querySelector(".modal select").value, "3");
});

test("managers retire available devices and restore retired ones from the menu", async (t) => {
  const { container, mutations } = await openEquipment(t, "ADMIN");
  await act(async () => row(container, 1).querySelector('[data-action="more"]').click());
  assert.deepEqual([...row(container, 1).querySelectorAll("[data-menu]")].map((node) => node.dataset.menu), ["report", "retire"]);
  await act(async () => row(container, 1).querySelector('[data-menu="retire"]').click());
  await waitFor(() => mutations.length === 1);
  assert.deepEqual(mutations, ["PATCH /api/equipment/1/retire"]);
  assert.equal(row(container, 1).querySelector("[data-menu]"), null, "choosing an entry closes the menu");

  await act(async () => row(container, 4).querySelector('[data-action="more"]').click());
  assert.deepEqual([...row(container, 4).querySelectorAll("[data-menu]")].map((node) => node.dataset.menu), ["restore"]);
  // Clicking elsewhere closes the menu without acting.
  await act(async () => document.body.dispatchEvent(new window.MouseEvent("mousedown", { bubbles: true })));
  assert.equal(row(container, 4).querySelector("[data-menu]"), null);
  assert.equal(mutations.length, 1);
});

test("technicians see the list and can report faults but cannot book", async (t) => {
  const { container } = await openEquipment(t, "TECHNICIAN");
  assert.equal(container.querySelector('[data-action="reserve"]'), null);
  assert.equal(container.querySelector('[data-action="create"]'), null);
  await act(async () => row(container, 1).querySelector('[data-action="view"]').click());
  await waitFor(() => container.querySelector(".equipment-detail"));
  const detail = container.querySelector(".equipment-detail").textContent;
  assert.ok(detail.includes("ZEISS") && detail.includes("2022-09-15") && detail.includes("故障报修"));
  assert.ok(!detail.includes("预约这台设备"));
});
