import { readFileSync } from "node:fs";
import { JSDOM } from "jsdom";

/**
 * jsdom's getComputedStyle ignores selector specificity (a later `.solid-button` beats an
 * earlier `.card .solid-button`), so it cannot catch a shared style overriding a local one.
 * This resolves one property the way a browser does: matching rules, then !important,
 * specificity and source order. Media queries are limited to (max-width)/(min-width) and
 * (prefers-*), which is all globals.css uses.
 */
const stylesheet = new URL("../app/globals.css", import.meta.url);

export function loadStyles() {
  const css = readFileSync(stylesheet, "utf8").replace(/@import[^;]+;/g, "");
  return new JSDOM(`<style>${css}</style>`).window.document.styleSheets[0].cssRules;
}

function specificity(selector) {
  const bare = selector.replace(/::?(before|after|placeholder|selection)\b/g, "").replace(/\([^)]*\)/g, "");
  const ids = (bare.match(/#[\w-]+/g) ?? []).length;
  const classes = (bare.match(/\.[\w-]+|\[[^\]]*\]|:(?!:)[\w-]+/g) ?? []).length;
  const types = (bare.replace(/#[\w-]+|\.[\w-]+|\[[^\]]*\]|:[\w-]+/g, " ").match(/(^|[\s>+~])[a-z][\w-]*/gi) ?? []).length;
  return ids * 10000 + classes * 100 + types;
}

function mediaApplies(text, width) {
  return text.split(/\band\b/).every((part) => {
    const max = part.match(/max-width:\s*(\d+)px/);
    if (max) return width <= Number(max[1]);
    const min = part.match(/min-width:\s*(\d+)px/);
    if (min) return width >= Number(min[1]);
    return !/prefers-/.test(part);
  });
}

export function cascadedValue(element, property, { rules = loadStyles(), width = 1440 } = {}) {
  let best = null;
  let order = 0;
  const visit = (list) => {
    for (const rule of list) {
      if (rule.media) {
        if (mediaApplies(rule.media.mediaText, width)) visit(rule.cssRules);
        continue;
      }
      if (!rule.selectorText) continue;
      order += 1;
      const value = rule.style.getPropertyValue(property);
      if (!value) continue;
      const important = rule.style.getPropertyPriority(property) === "important";
      for (const selector of rule.selectorText.split(",").map((part) => part.trim())) {
        let matches = false;
        try { matches = element.matches(selector.replace(/::?(before|after)\b/g, "")); } catch { continue; }
        if (!matches || /::?(before|after)\b/.test(selector)) continue;
        const rank = [important ? 1 : 0, specificity(selector), order];
        if (!best || rank[0] > best.rank[0] || (rank[0] === best.rank[0] && (rank[1] > best.rank[1] || (rank[1] === best.rank[1] && rank[2] >= best.rank[2])))) {
          best = { rank, value, selector };
        }
      }
    }
  };
  visit(rules);
  return best?.value ?? "";
}
