import assert from "node:assert/strict";
import { request } from "playwright";

// Use the real API to prepare two priced bookings, then pay through the actual React page.
export async function prepareChargedReservations(page, base) {
  const api = await request.newContext({ baseURL: base });
  try {
    const call = async (path, method, token, data) => {
      const response = await api.fetch(`/api/backend/api${path}`, {
        method, headers: token ? { Authorization: `Bearer ${token}` } : {}, data,
      });
      assert.ok(response.ok(), `${method} ${path}: ${response.status()} ${await response.text()}`);
      return response.json();
    };
    const login = (username, password) => call("/auth/login", "POST", null, { username, password });
    const teacher = await login("teacher", "teacher123");
    const student = await login("student", "student123");
    const equipment = await call("/equipment", "POST", teacher.accessToken, {
      code: "SEM-202", name: "扫描电子显微镜（计费）", category: "显微成像", location: "材料楼 A209",
      hourlyPriceCents: 12000,
    });
    const create = async (hour, purpose) => {
      const start = new Date();
      start.setDate(start.getDate() + 1);
      start.setHours(hour, 0, 0, 0);
      const reservation = await call("/reservations", "POST", student.accessToken, {
        equipmentId: equipment.id, purpose, startTime: start.toISOString(),
        endTime: new Date(start.getTime() + 3600000).toISOString(),
      });
      const approved = await call(`/reservations/${reservation.id}/decision`, "PATCH", teacher.accessToken, { decision: "APPROVED" });
      assert.equal(approved.status, "AWAITING_PAYMENT");
      return reservation.id;
    };
    const paidId = await create(14, "复合材料断面成分分析");
    const awaitingId = await create(9, "纳米材料表面形貌观察");
    await page.click('[data-nav="reservations"]');
    const paidRow = page.locator(`[data-reservation-id="${paidId}"]`);
    await paidRow.locator('[data-action="pay"]').click();
    const dialog = page.getByRole("dialog", { name: "预约付款" });
    await dialog.getByText("应付金额：¥120.00", { exact: true }).waitFor();
    await dialog.locator('[data-action="confirm-pay"]').click();
    await dialog.waitFor({ state: "detached" });
    // Dispatch and callbacks are asynchronous; refresh only after the real API reports settlement.
    let settled = false;
    for (let attempt = 0; attempt < 100; attempt += 1) {
      const paid = await call("/reservations?status=PAID&size=100", "GET", student.accessToken);
      settled = paid.content.some((reservation) => reservation.id === paidId);
      if (settled) break;
      await page.waitForTimeout(100);
    }
    assert.ok(settled, "the payment callback must settle the reservation before capture");
    await page.getByRole("button", { name: "刷新", exact: true }).click();
    await paidRow.getByText("已支付", { exact: true }).waitFor();
    await page.locator(`[data-reservation-id="${awaitingId}"] [data-action="pay"]`).waitFor();
    await page.locator(".toast").waitFor({ state: "detached" });
    await page.locator(".table-panel").scrollIntoViewIfNeeded();
    await paidRow.locator('[data-action="more"]').click();
    await page.locator(`[data-menu-for="${paidId}"] [data-menu="cancel"]`).click();
    await page.getByRole("dialog", { name: "取消已支付预约" }).getByText(/全额退款 ¥120\.00/).waitFor();
  } finally {
    await api.dispose();
  }
}
