import { useEffect, useLayoutEffect, useRef } from "react";
import { createPortal } from "react-dom";
import { statusLabels } from "../lib/labels";
import type { Equipment } from "../lib/types";
import { categoryIcon, categoryTone, Icon } from "./icons";

/**
 * Building blocks shared by the list tables (equipment, reservations, work orders),
 * so every table gets the same status dots, device cell and row menu.
 */

export type StateTone = "ok" | "info" | "warn" | "danger" | "idle";

/**
 * Colour of every equipment, reservation and work-order status. A status missing
 * here renders grey; tests/table-parts.test.mjs fails if one is missing.
 */
export const STATE_TONES: Record<string, StateTone> = {
  // equipment
  AVAILABLE: "ok", IN_USE: "info", MAINTENANCE: "warn", RETIRED: "idle",
  // reservations
  PENDING: "warn", APPROVED: "info", AWAITING_PAYMENT: "warn", PAID: "info", REFUNDING: "warn",
  COMPLETED: "ok", REJECTED: "danger", CANCELLED: "idle", EXPIRED: "idle",
  // work orders
  SUBMITTED: "warn", ASSIGNED: "info", IN_PROGRESS: "info", RESOLVED: "ok", CLOSED: "idle",
};

export function StateBadge({ status }: { status: string }) {
  return <span className={`state state-${STATE_TONES[status] ?? "idle"}`}><i />{statusLabels[status] ?? status}</span>;
}

export function CategoryTag({ category }: { category: string }) {
  return <span className={`cat-tag tone-${categoryTone(category)}`}>{category}</span>;
}

/** Device icon, name and code; falls back to the id when the device is not in the lookup. */
export function EquipmentCell({ equipment, equipmentId }: { equipment?: Equipment; equipmentId: number }) {
  return <div className="equipment-name">
    <span className="equip-thumb" title={equipment?.category}><Icon name={categoryIcon(equipment?.category)} size={22} /></span>
    <div><b>{equipment?.name ?? `设备 #${equipmentId}`}</b><small className="mono">{equipment?.code ?? "—"}</small></div>
  </div>;
}

export type MenuEntry = { key: string; label: string; danger?: boolean; onClick: () => void };

/** Builds a row menu from conditional entries: `cond && { ... }` items that are false are dropped. */
export function menuEntries(...entries: (MenuEntry | false | null | undefined)[]): MenuEntry[] {
  return entries.filter((entry): entry is MenuEntry => Boolean(entry));
}

const MENU_GAP = 6;

/**
 * The table sits in a sideways-scrolling container, and overflow-x: auto clips
 * vertical overflow too. The list is therefore portalled to <body> and placed
 * with fixed coordinates next to its button, so no row count can clip it.
 * It opens upwards only when the viewport has no room below.
 */
export function MoreMenu({ id, open, label, entries, onToggle }: { id: number; open: boolean; label: string; entries: MenuEntry[]; onToggle: (open: boolean) => void }) {
  const button = useRef<HTMLButtonElement>(null);
  const list = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    if (!open) return;
    // Fixed coordinates: re-place on scroll and resize so the list stays next to its button.
    const place = () => {
      const menu = list.current;
      const anchor = button.current?.getBoundingClientRect();
      if (!menu || !anchor) return;
      const fitsBelow = anchor.bottom + MENU_GAP + menu.offsetHeight <= window.innerHeight;
      menu.style.top = `${fitsBelow ? anchor.bottom + MENU_GAP : Math.max(MENU_GAP, anchor.top - MENU_GAP - menu.offsetHeight)}px`;
      menu.style.left = `${Math.max(MENU_GAP, anchor.right - menu.offsetWidth)}px`;
      menu.style.visibility = "visible";
    };
    place();
    window.addEventListener("resize", place);
    window.addEventListener("scroll", place, true);
    return () => {
      window.removeEventListener("resize", place);
      window.removeEventListener("scroll", place, true);
    };
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const close = () => onToggle(false);
    const onOutside = (event: MouseEvent) => {
      const target = event.target as Node;
      if (!button.current?.contains(target) && !list.current?.contains(target)) close();
    };
    const onEscape = (event: KeyboardEvent) => { if (event.key === "Escape") close(); };
    document.addEventListener("mousedown", onOutside);
    document.addEventListener("keydown", onEscape);
    return () => {
      document.removeEventListener("mousedown", onOutside);
      document.removeEventListener("keydown", onEscape);
    };
  }, [open, onToggle]);

  return <div className="more-menu">
    <button ref={button} type="button" className="icon-button" data-action="more" aria-label={label} aria-haspopup="menu" aria-expanded={open} onClick={() => onToggle(!open)}>
      <Icon name="more" size={18} />
    </button>
    {open && createPortal(<div ref={list} className="more-menu-list" role="menu" aria-label={label} data-menu-for={id}>
      {entries.map((entry) => <button key={entry.key} type="button" role="menuitem" data-menu={entry.key} className={entry.danger ? "danger" : ""}
        onClick={() => { onToggle(false); entry.onClick(); }}>{entry.label}</button>)}
    </div>, document.body)}
  </div>;
}
