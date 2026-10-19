// Logs in as each demo role and captures the README screenshots: 1440 wide, 2x, Asia/Shanghai.
// Usage: node take.mjs <output dir> <frontend url>; playwright is installed by the workflow.
import { chromium } from "playwright";
import { LOGIN, SESSIONS } from "./shots.mjs";

const [out, base = "http://127.0.0.1:13000/"] = process.argv.slice(2);
const browser = await chromium.launch();
let failed = 0;

for (const session of SESSIONS) {
  const context = await browser.newContext({
    viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2, locale: "zh-CN", timezoneId: "Asia/Shanghai",
  });
  const page = await context.newPage();
  page.on("pageerror", (error) => console.error(`[${session.user ?? "login"}] page error:`, String(error)));
  try {
    await page.goto(base, { waitUntil: "networkidle" });
    await page.waitForSelector(LOGIN.ready, { timeout: 60000 });
    if (session.user) {
      await page.fill(LOGIN.username, session.user);
      await page.fill(LOGIN.password, session.password);
      await page.click(LOGIN.submit);
      await page.waitForSelector(LOGIN.signedIn, { timeout: 60000 });
    }
    for (const shot of session.shots) {
      if (shot.nav) await page.click(`[data-nav="${shot.nav}"]`);
      await page.waitForSelector(shot.ready, { timeout: 60000 });
      await page.waitForLoadState("networkidle");
      await page.waitForTimeout(1200);
      // Park the pointer in a corner so no hover state ends up in the picture.
      await page.mouse.move(2, 898);
      await page.screenshot({ path: `${out}/${shot.file}`, fullPage: shot.fullPage ?? true });
      console.log("saved", shot.file);
    }
  } catch (error) {
    failed += 1;
    console.error(`error in ${session.user ?? "login"}:`, String(error).split("\n")[0]);
  }
  await context.close();
}

await browser.close();
process.exit(failed ? 1 : 0);
