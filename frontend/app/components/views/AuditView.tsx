import {
  type ActionDomain, actionDomain, actionDomainLabels, actionLabels, actorRoleLabels, readableDetails, targetTypeLabels,
} from "../../lib/labels";
import type { AuditLog, PageData } from "../../lib/types";
import { type CategoryTone, Icon, type IconName } from "../icons";
import { RoleTag } from "../roleTag";
import { Empty } from "../ui";

export type AuditFilter = { actorRole: string; action: string };
export const emptyAuditFilter: AuditFilter = { actorRole: "", action: "" };

const DOMAIN_STYLES: Record<ActionDomain, { icon: IconName; tone: CategoryTone }> = {
  equipment: { icon: "box", tone: "violet" },
  reservation: { icon: "calendar", tone: "orange" },
  workorder: { icon: "wrench", tone: "green" },
  payment: { icon: "receipt", tone: "blue" },
};

/**
 * Filter options come from the label tables, never from the rows on screen, so an
 * action stays selectable after filtering or paging (see AGENTS.md).
 */
const ACTION_GROUPS = (Object.keys(actionDomainLabels) as ActionDomain[]).map((domain) => ({
  domain,
  actions: Object.keys(actionLabels).filter((action) => actionDomain(action) === domain),
}));

function splitDateTime(value: string) {
  const date = new Date(value);
  const pad = (n: number) => String(n).padStart(2, "0");
  return {
    day: `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`,
    time: `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`,
  };
}

export function AuditView({ auditLogs, filter, onFilterChange, onSearch, onReset, onPage }: {
  auditLogs: PageData<AuditLog> | null;
  filter: AuditFilter;
  onFilterChange: (filter: AuditFilter) => void;
  onSearch: () => void;
  onReset: () => void;
  onPage: (page: number) => void;
}) {
  const page = auditLogs?.page ?? 0;
  const totalPages = auditLogs?.totalPages ?? 0;
  return <div className="view-stack">
    <section className="hero page-hero">
      <div className="hero-copy">
        <i className="hero-rule" aria-hidden="true" />
        <h2>操作审计</h2>
        <p>记录系统关键操作，便于追踪、复核与合规管理。</p>
      </div>
      <p className="hero-motto" aria-hidden="true">
        可追溯、可审计，<br />让实验室运行更规范。
        <small>BETTER LABS<br />BRIGHTER DISCOVERIES</small>
      </p>
    </section>

    <section className="panel table-panel audit-panel">
      <form className="audit-filters" onSubmit={(event) => { event.preventDefault(); onSearch(); }}>
        <label>角色筛选
          <select data-audit-filter="role" value={filter.actorRole} onChange={(e) => onFilterChange({ ...filter, actorRole: e.target.value })}>
            <option value="">全部角色</option>
            {Object.entries(actorRoleLabels).map(([role, label]) => <option key={role} value={role}>{label}</option>)}
          </select>
        </label>
        <label>操作类型
          <select data-audit-filter="action" value={filter.action} onChange={(e) => onFilterChange({ ...filter, action: e.target.value })}>
            <option value="">全部操作类型</option>
            {ACTION_GROUPS.map((group) => <optgroup key={group.domain} label={actionDomainLabels[group.domain]}>
              {group.actions.map((action) => <option key={action} value={action}>{actionLabels[action]}</option>)}
            </optgroup>)}
          </select>
        </label>
        <div className="spacer" />
        <button type="submit" className="solid-button" data-action="search">查询</button>
        <button type="button" className="ghost-button" data-action="reset" onClick={onReset}>重置</button>
      </form>

      {Boolean(auditLogs?.content.length) && <div className="table-scroll"><table className="list-table audit-table">
        <thead><tr><th>操作时间</th><th>操作</th><th>角色</th><th>操作详情</th><th>操作人</th></tr></thead>
        <tbody>{auditLogs?.content.map((item) => {
          const when = splitDateTime(item.createdAt);
          const style = DOMAIN_STYLES[actionDomain(item.action)];
          const target = targetTypeLabels[item.targetType] ?? item.targetType;
          return <tr key={item.id} data-audit-id={item.id}>
            <td className="audit-time"><i aria-hidden="true" /><b>{when.day}</b><small>{when.time}</small></td>
            <td><div className="audit-action">
              <span className={`action-icon tone-${style.tone}`}><Icon name={style.icon} size={20} /></span>
              <b>{actionLabels[item.action] ?? item.action}</b>
            </div></td>
            <td><RoleTag role={item.actorRole} /></td>
            <td><div className="fault-cell audit-detail">
              <b>{readableDetails(item.details)}</b>
              <small>{target}{item.targetId != null ? ` #${item.targetId}` : ""}</small>
            </div></td>
            <td><div className="audit-actor">
              <span className="avatar" aria-hidden="true">{item.actorRole === "SYSTEM" ? <Icon name="bolt" size={16} /> : item.actorUsername.slice(0, 1).toUpperCase()}</span>
              <b>{item.actorUsername}</b>
            </div></td>
          </tr>;
        })}</tbody>
      </table></div>}
      {!auditLogs?.content.length && <Empty text="没有符合条件的审计记录" />}
      {Boolean(auditLogs?.totalElements) && <footer className="pager">
        <small>共 {auditLogs?.totalElements} 条 · 第 {page + 1} / {Math.max(totalPages, 1)} 页</small>
        <button type="button" className="ghost-button" data-action="prev-page" disabled={page <= 0} onClick={() => onPage(page - 1)}>上一页</button>
        <button type="button" className="ghost-button" data-action="next-page" disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>下一页</button>
      </footer>}
    </section>
  </div>;
}
