import type { ReactNode } from "react";
import { statusLabels } from "../lib/labels";
import type { ToastState } from "../lib/types";
import { Icon } from "./icons";

export function Brand({ light = false }: { light?: boolean }) {
  return <div className={`brand ${light ? "light" : ""}`}>
    <span className="brand-mark" aria-hidden="true">
      <svg viewBox="0 0 32 32" width="32" height="32">
        <path d="M9 3h10l8 6v12l-8 8H9l-6-6V9z" fill="var(--orange)" />
        <path d="M12 9v14h9" fill="none" stroke="white" strokeWidth="3.2" strokeLinecap="round" strokeLinejoin="round" />
        <circle cx="21" cy="11" r="2.2" fill="white" />
      </svg>
    </span>
    <div><b>LabFlow</b><small>高校实验室运营平台</small></div>
  </div>;
}

export function StatusBadge({ value }: { value: string }) {
  return <span className={`status status-${value.toLowerCase()}`}>{statusLabels[value] ?? value}</span>;
}

export function Modal({ title, subtitle, onClose, children }: { title: string; subtitle: string; onClose: () => void; children: ReactNode }) {
  return <div className="modal-backdrop" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
    <section className="modal" role="dialog" aria-modal="true" aria-label={title}>
      <header>
        <div><h2>{title}</h2><p>{subtitle}</p></div>
        <button type="button" className="icon-button" onClick={onClose} aria-label="关闭"><Icon name="close" size={18} /></button>
      </header>
      {children}
    </section>
  </div>;
}

export function Empty({ text }: { text: string }) {
  return <div className="empty"><span><Icon name="inbox" size={22} /></span><p>{text}</p></div>;
}

export function Toast({ toast }: { toast: ToastState }) {
  if (!toast) return null;
  return <div className={`toast ${toast.error ? "error" : ""}`}>
    <span>{toast.error ? "!" : "✓"}</span>{toast.message}
  </div>;
}

