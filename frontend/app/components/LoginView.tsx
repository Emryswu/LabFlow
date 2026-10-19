import type { FormEvent } from "react";
import type { ToastState } from "../lib/types";
import { Icon, type IconName } from "./icons";
import { roleStyle } from "./roleTag";
import { Brand, Toast } from "./ui";

type LoginState = { username: string; password: string };

/** Seeded demo accounts (DemoUserInitializer); a card fills the form so the visitor can sign in at once. */
export const DEMO_ACCOUNTS = [
  { role: "STUDENT", label: "学生", note: "查看设备，提交预约与报修", username: "student", password: "student123" },
  { role: "TEACHER", label: "教师", note: "审批预约，管理实验设备", username: "teacher", password: "teacher123" },
  { role: "TECHNICIAN", label: "维修员", note: "接单处理，跟踪工单进度", username: "technician", password: "tech123" },
  { role: "ADMIN", label: "管理员", note: "全局管理、支付对账与审计", username: "admin", password: "admin123" },
] as const;

const STORY_POINTS: { icon: IconName; text: string }[] = [
  { icon: "box", text: "设备状态一眼看清" },
  { icon: "calendar", text: "预约冲突实时拦截" },
  { icon: "wrench", text: "故障进度全程追踪" },
];

export function LoginView({ login, busy, toast, onLoginChange, onSubmit, onDemo }: {
  login: LoginState;
  busy: boolean;
  toast: ToastState;
  onLoginChange: (next: LoginState) => void;
  onSubmit: (event: FormEvent) => void;
  onDemo: (username: string, password: string) => void;
}) {
  return <main className="login-shell">
    <header className="login-bar"><Brand light /></header>
    <div className="login-stage">
      <section className="login-story">
        <i className="hero-rule" aria-hidden="true" />
        <h1>让实验室运营更高效</h1>
        <p>连接设备、预约、维修与支付对账，让高校实验室每天都按计划运转。</p>
        <ul className="story-points">
          {STORY_POINTS.map((point) => <li key={point.text}><span><Icon name={point.icon} size={18} /></span>{point.text}</li>)}
        </ul>
      </section>
      <section className="login-panel">
        <form className="login-card" onSubmit={onSubmit}>
          <h2>登录 LabFlow</h2>
          <label className="icon-field">
            <Icon name="user" size={19} />
            <input aria-label="账号" placeholder="账号" value={login.username} onChange={(e) => onLoginChange({ ...login, username: e.target.value })} autoComplete="username" required />
          </label>
          <label className="icon-field">
            <Icon name="lock" size={19} />
            <input aria-label="密码" placeholder="密码" type="password" value={login.password} onChange={(e) => onLoginChange({ ...login, password: e.target.value })} autoComplete="current-password" required />
          </label>
          <button className="solid-button login-submit" disabled={busy}>{busy ? "正在验证…" : "登录"}</button>
          <div className="demo-divider"><span>快速体验</span></div>
          <div className="demo-grid">
            {DEMO_ACCOUNTS.map((account) => {
              const style = roleStyle(account.role);
              const selected = login.username === account.username;
              return <button type="button" key={account.role} data-demo={account.role} aria-pressed={selected} className={selected ? "selected" : ""}
                onClick={() => onDemo(account.username, account.password)}>
                <span className={`demo-icon tone-${style.tone}`}><Icon name={style.icon} size={20} /></span>
                <span className="demo-copy"><b>{account.label}</b><small>{account.note}</small></span>
                <Icon name="chevron" size={16} className="demo-arrow" />
              </button>;
            })}
          </div>
          <p className="demo-hint">点选角色会填好演示账号，再点「登录」即可进入</p>
        </form>
      </section>
    </div>
    <Toast toast={toast} />
  </main>;
}
