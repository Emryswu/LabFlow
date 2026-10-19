/**
 * Shared line-icon set: 24px grid, 1.8 stroke, round caps, currentColor.
 * Every page uses these instead of text glyphs so the visual language stays consistent.
 */
const paths = {
  calendar: <><rect x="3.5" y="5" width="17" height="15.5" rx="2.5" /><path d="M8 3v4M16 3v4M3.5 10h17M8 14h2M12 14h2M8 17h2" /></>,
  wrench: <path d="M14.5 4.2a4.8 4.8 0 0 0-5 6.4L3.8 16.3a1.9 1.9 0 0 0 2.7 2.7l5.7-5.7a4.8 4.8 0 0 0 6.4-5l-2.9 2.9-2.6-.6-.6-2.6z" />,
  box: <><path d="M12 3 20 7.5v9L12 21l-8-4.5v-9z" /><path d="M4 7.5 12 12l8-4.5M12 12v9" /></>,
  clipboard: <><rect x="5" y="4.5" width="14" height="16.5" rx="2.5" /><path d="M9 3h6v3H9zM8.5 11h7M8.5 14.5h7M8.5 18h4" /></>,
  receipt: <><path d="M6 3h12v18l-2-1.4-2 1.4-2-1.4-2 1.4-2-1.4L6 21z" /><path d="M9 8h6M9 11.5h6M9 15h3.5" /></>,
  shield: <><path d="M12 3 19 6v5.5c0 4.4-3 7.8-7 9.5-4-1.7-7-5.1-7-9.5V6z" /><path d="m9 12 2 2 4-4" /></>,
  clock: <><circle cx="12" cy="12" r="8.5" /><path d="M12 7.5V12l3 2" /></>,
  bolt: <path d="M13 3 5 13.5h6L10 21l8-10.5h-6z" />,
  chevron: <path d="m9.5 6 6 6-6 6" />,
  more: <><circle cx="12" cy="5.5" r=".9" /><circle cx="12" cy="12" r=".9" /><circle cx="12" cy="18.5" r=".9" /></>,
  arrowUp: <path d="M12 19V5M6 11l6-6 6 6" />,
  arrowDown: <path d="M12 5v14M6 13l6 6 6-6" />,
  pie: <><path d="M12 3.5a8.5 8.5 0 1 0 8.5 8.5H12z" /><path d="M15 3.9A8.5 8.5 0 0 1 20.1 9H15z" /></>,
  list: <path d="M9 6.5h11M9 12h11M9 17.5h11M4.5 6.5h.01M4.5 12h.01M4.5 17.5h.01" />,
  logout: <><path d="M14 4.5h3.5a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H14" /><path d="M10 8l-4 4 4 4M6 12h9" /></>,
  refresh: <><path d="M19.5 11a7.5 7.5 0 0 0-13.4-4.2L4.5 8.5" /><path d="M4.5 4v4.5H9M4.5 13a7.5 7.5 0 0 0 13.4 4.2l1.6-1.7" /><path d="M19.5 20v-4.5H15" /></>,
  alert: <><path d="M12 4 21 19.5H3z" /><path d="M12 10v4M12 17h.01" /></>,
  audit: <><path d="M6 3.5h9l3.5 3.5v13.5H6z" /><path d="M14.5 3.5V7h4M9 12h6M9 15.5h6" /></>,
  check: <path d="m5 12.5 4.5 4.5L19 7.5" />,
  plus: <path d="M12 5v14M5 12h14" />,
  search: <><circle cx="11" cy="11" r="6.5" /><path d="m16 16 4 4" /></>,
  user: <><circle cx="12" cy="8.5" r="3.8" /><path d="M4.5 20a7.5 7.5 0 0 1 15 0" /></>,
  inbox: <><path d="M4 13.5 6.5 5h11l2.5 8.5V19H4z" /><path d="M4 13.5h4.5l1.5 2.5h4l1.5-2.5H20" /></>,
  microscope: <><path d="M6 20.5h12M9 17.5h6" /><path d="m9 3.5 3.5 2-3.5 6-3.5-2z" /><path d="M10.5 9.5a5.5 5.5 0 0 1 5 8" /><path d="M8 12.5 6.5 15" /></>,
  flask: <><path d="M9.5 3.5h5M10.5 3.5v5L5 18.5A1.8 1.8 0 0 0 6.6 21h10.8a1.8 1.8 0 0 0 1.6-2.5l-5.5-10v-5" /><path d="M7.5 15h9" /></>,
  gem: <><path d="M6.5 4h11L21 9l-9 11.5L3 9z" /><path d="M3 9h18M9.5 4 8 9l4 11.5L16 9l-1.5-5" /></>,
  dna: <><path d="M7 3c0 6 10 6 10 12s-10 6-10 6" /><path d="M17 3c0 6-10 6-10 12M17 21v0" /><path d="M8.5 7h7M8.5 17h7M10 12h4" /></>,
  waves: <path d="M3 8c2.2-2 3.8-2 6 0s3.8 2 6 0 3.8-2 6 0M3 13c2.2-2 3.8-2 6 0s3.8 2 6 0 3.8-2 6 0M3 18c2.2-2 3.8-2 6 0s3.8 2 6 0 3.8-2 6 0" />,
  centrifuge: <><circle cx="12" cy="12" r="8.5" /><circle cx="12" cy="12" r="1.8" /><path d="M12 6.5v.01M17 14.5v.01M7 14.5v.01" /></>,
} as const;

export type IconName = keyof typeof paths;

export function Icon({ name, size = 20, className }: { name: IconName; size?: number; className?: string }) {
  return <svg
    className={className ? `icon ${className}` : "icon"}
    width={size}
    height={size}
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth={1.8}
    strokeLinecap="round"
    strokeLinejoin="round"
    aria-hidden="true"
    focusable="false"
  >{paths[name]}</svg>;
}

export type CategoryTone = "orange" | "amber" | "coral" | "green" | "blue" | "violet" | "graphite";

/**
 * One table drives a category's icon, its tag colour and the category filter's
 * options, so adding a category here updates all three at once.
 * Equipment photos are not stored, so each category gets a recognisable line icon instead.
 */
const CATEGORY_STYLES: Record<string, { icon: IconName; tone: CategoryTone }> = {
  显微成像: { icon: "microscope", tone: "blue" },
  分析检测: { icon: "flask", tone: "orange" },
  结构分析: { icon: "gem", tone: "violet" },
  生命科学: { icon: "dna", tone: "green" },
  光谱分析: { icon: "waves", tone: "amber" },
  样品制备: { icon: "centrifuge", tone: "coral" },
};

/** Categories with their own icon and colour; free-text categories fall back to a neutral style. */
export const KNOWN_CATEGORIES = Object.keys(CATEGORY_STYLES);

export function categoryIcon(category: string | null | undefined): IconName {
  return (category && CATEGORY_STYLES[category]?.icon) || "box";
}

export function categoryTone(category: string | null | undefined): CategoryTone {
  return (category && CATEGORY_STYLES[category]?.tone) || "graphite";
}
