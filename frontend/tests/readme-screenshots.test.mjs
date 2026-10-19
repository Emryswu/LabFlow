import assert from "node:assert/strict";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

// The README screenshot workflow (.github/workflows/readme-screenshots.yml) waits on these
// selectors. It only runs on demand, so without this test a UI change would leave it broken
// until the next retake — which is how the previous one went stale.
const { LOGIN, SESSIONS } = await import(new URL("../../.github/screenshots/shots.mjs", import.meta.url));

const appDirectory = fileURLToPath(new URL("../app/", import.meta.url));
const readmePath = fileURLToPath(new URL("../../README.md", import.meta.url));
const screenshotDirectory = fileURLToPath(new URL("../../docs/screenshots/", import.meta.url));

function sources(directory) {
  return readdirSync(directory).flatMap((name) => {
    const path = join(directory, name);
    if (statSync(path).isDirectory()) return sources(path);
    return name.endsWith(".tsx") ? [readFileSync(path, "utf8")] : [];
  });
}

/** The class names and data attributes a selector depends on. */
function hooks(selector) {
  return [
    ...[...selector.matchAll(/\.([\w-]+)/g)].map((match) => ({ kind: "class", name: match[1] })),
    ...[...selector.matchAll(/\[(data-[\w-]+)/g)].map((match) => ({ kind: "attribute", name: match[1] })),
  ];
}

test("every selector the screenshot run waits on still exists in the app", () => {
  const code = sources(appDirectory).join("\n");
  const selectors = [
    ...Object.values(LOGIN),
    ...SESSIONS.flatMap((session) => session.shots.flatMap((shot) => [shot.ready, shot.nav && `[data-nav="${shot.nav}"]`])),
  ].filter(Boolean);
  const missing = [];
  for (const selector of selectors) {
    for (const hook of hooks(selector)) {
      const found = hook.kind === "class"
        ? new RegExp(`className=[{"\`][^>]*\\b${hook.name}\\b`).test(code)
        : code.includes(`${hook.name}=`);
      if (!found) missing.push(`${selector} → ${hook.name}`);
    }
  }
  assert.deepEqual(missing, [], "update .github/screenshots/shots.mjs to match the UI");
});

test("the shot list covers exactly the screenshots the README shows", () => {
  const readme = readFileSync(readmePath, "utf8");
  const shown = [...readme.matchAll(/docs\/screenshots\/([\w-]+\.png)/g)].map((match) => match[1]).sort();
  const taken = SESSIONS.flatMap((session) => session.shots.map((shot) => shot.file)).sort();
  assert.deepEqual(taken, shown);
  const onDisk = readdirSync(screenshotDirectory).filter((name) => name.endsWith(".png")).sort();
  assert.deepEqual(onDisk, shown, "every file in docs/screenshots is used by the README");
});
