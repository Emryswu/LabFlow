// Logs in as each demo role and captures the README screenshots at 2x.
import { chromium } from "/tmp/shots/node_modules/playwright/index.mjs";

const out = process.argv[2];
const base = "http://127.0.0.1:13000/";
const browser = await chromium.launch();
let failed = 0;
async function step(name, fn) {
  try { await fn(); } catch (error) { failed += 1; console.error(`error in ${name}:`, String(error).split("\n")[0]); }
}

async function session(user, password) {
  const context = await browser.newContext({
    viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2, locale: "zh-CN", timezoneId: "Asia/Shanghai",
  });
  const page = await context.newPage();
  page.on("pageerror", (error) => console.error(`[${user}] page error:`, String(error)));
  await page.goto(base, { waitUntil: "networkidle" });
  if (user) {
    await page.fill('input[autocomplete="username"]', user);
    await page.fill('input[type="password"]', password);
    await page.click("button.primary.wide");
    await page.waitForSelector(".sidebar", { timeout: 60000 });
  }
  return { context, page };
}

async function view(page, nav, readySelector) {
  if (nav) await page.click(`[data-nav="${nav}"]`);
  await page.waitForSelector(readySelector, { timeout: 60000 });
  await page.waitForLoadState("networkidle");
  await page.waitForTimeout(1200);
}

async function shot(page, name) {
  await page.screenshot({ path: `${out}/${name}`, fullPage: true });
  console.log("saved", name);
}

await step("login", async () => {
  const { context, page } = await session(null);
  await page.waitForSelector('input[autocomplete="username"]');
  await page.waitForTimeout(800);
  await page.screenshot({ path: `${out}/01-login.png` });
  console.log("saved 01-login.png");
  await context.close();
});
await step("admin", async () => {
  const { context, page } = await session("admin", "admin123");
  await view(page, null, ".row-item");
  await shot(page, "02-dashboard-admin.png");
  await view(page, "equipment", ".equipment-card");
  await shot(page, "03-equipment.png");
  await view(page, "workorders", "table tbody tr");
  await shot(page, "05-workorders.png");
  await view(page, "audit", ".timeline article");
  await shot(page, "06-audit.png");
  await context.close();
});
await step("teacher", async () => {
  const { context, page } = await session("teacher", "teacher123");
  await view(page, "reservations", ".week-block");
  await shot(page, "04-reservations.png");
  await context.close();
});
await step("student", async () => {
  const { context, page } = await session("student", "student123");
  await view(page, null, ".row-item");
  await shot(page, "07-dashboard-student.png");
  await context.close();
});
await step("technician", async () => {
  const { context, page } = await session("technician", "tech123");
  await view(page, "workorders", "table tbody tr");
  await shot(page, "08-workorders-technician.png");
  await context.close();
});
await browser.close();
process.exit(failed ? 1 : 0);
