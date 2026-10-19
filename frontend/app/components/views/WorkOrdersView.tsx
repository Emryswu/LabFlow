import { useState } from "react";
import { formatDate, statusLabels } from "../../lib/labels";
import type { Equipment, PageData, Technician, UserProfile, WorkOrder } from "../../lib/types";
import { Icon } from "../icons";
import { EquipmentCell, menuEntries, MoreMenu, StateBadge } from "../table";
import { Empty } from "../ui";

export function WorkOrdersView({ user, workorders, equipment, technicians, filter, canAssign, canClaim, canProcess, onFilterChange, onLoad, onCreate, onTransition, onClaim }: {
  user: UserProfile;
  workorders: PageData<WorkOrder> | null;
  /** Full, unfiltered name lookup; never the equipment page's filtered list. */
  equipment: PageData<Equipment> | null;
  technicians: Technician[];
  filter: string;
  canAssign: boolean;
  canClaim: boolean;
  canProcess: boolean;
  onFilterChange: (filter: string) => void;
  onLoad: () => void;
  onCreate: () => void;
  onTransition: (item: WorkOrder, targetStatus: string, assigneeId?: number) => void;
  onClaim: (item: WorkOrder) => void;
}) {
  const [selectedAssignees, setSelectedAssignees] = useState<Record<number, string>>({});
  const [menuFor, setMenuFor] = useState<number | null>(null);
  const equipmentById = new Map((equipment?.content ?? []).map((item) => [item.id, item]));
  // The technician list is only loaded for admins, so a technician's own orders would otherwise read "维修员 #id".
  const technicianName = (id: number | null) => (id === user.id ? user.displayName : undefined)
    ?? technicians.find((item) => item.id === id)?.displayName
    ?? (id ? `维修员 #${id}` : "待分派");
  const isOwn = (item: WorkOrder) => item.assigneeId === user.id;
  const canActOn = (item: WorkOrder) => canAssign || (canProcess && isOwn(item));

  /** The one forward step for this row, shown as the solid button; everything else goes in the menu. */
  const primaryStep = (item: WorkOrder): { key: string; label: string; onClick: () => void } | null => {
    if (canClaim && item.status === "SUBMITTED") return { key: "claim", label: "接单", onClick: () => onClaim(item) };
    if (!canActOn(item)) return null;
    if (item.status === "ASSIGNED") return { key: "start", label: "开始处理", onClick: () => onTransition(item, "IN_PROGRESS") };
    if (item.status === "IN_PROGRESS") return { key: "resolve", label: "标记解决", onClick: () => onTransition(item, "RESOLVED") };
    if (item.status === "RESOLVED") return { key: "close", label: "关闭", onClick: () => onTransition(item, "CLOSED") };
    return null;
  };

  return <div className="view-stack">
    <section className="toolbar">
      <select value={filter} onChange={(e) => onFilterChange(e.target.value)}>
        <option value="">全部状态</option>
        {(canAssign || canClaim) && <option value="SUBMITTED">待派单</option>}
        <option value="ASSIGNED">已派单</option>
        <option value="IN_PROGRESS">处理中</option>
        <option value="RESOLVED">已解决</option>
        <option value="CLOSED">已关闭</option>
      </select>
      <button className="secondary" onClick={onLoad}>筛选</button>
      <div className="spacer"></div>
      <button className="primary" onClick={onCreate}><Icon name="plus" size={16} />故障报修</button>
    </section>
    <section className="panel table-panel">
      <header>
        <h3>{canAssign ? "工单队列" : "维修工单"}</h3>
        <small>
          {canAssign
            ? "管理员可派给任意维修员；维修员可主动接单"
            : "可接未分配工单，并处理分配给你的工单"}
          · 共 {workorders?.totalElements ?? 0} 条
        </small>
      </header>
      {Boolean(workorders?.content.length) && <div className="table-scroll"><table className="list-table">
        <thead><tr><th>故障描述</th><th>设备</th><th>处理人员</th><th>状态</th><th>更新时间</th><th>操作</th></tr></thead>
        <tbody>{workorders?.content.map((item) => {
          const selected = selectedAssignees[item.id] || String(technicians[0]?.id || "");
          const primary = primaryStep(item);
          const menu = menuEntries(
            canActOn(item) && item.status === "RESOLVED" && { key: "reopen", label: "重新打开", onClick: () => onTransition(item, "IN_PROGRESS") },
            canAssign && (item.status === "SUBMITTED" || item.status === "ASSIGNED")
              && { key: "cancel", label: "取消工单", danger: true, onClick: () => onTransition(item, "CANCELLED") },
          );
          return <tr key={item.id} data-workorder-id={item.id}>
            <td><div className="fault-cell">
              <b>{item.title}<span className={`tag tag-${item.priority.toLowerCase()}`}>{statusLabels[item.priority] ?? item.priority}</span></b>
              <small>{item.reporterName} · {item.description}</small>
            </div></td>
            <td><EquipmentCell equipment={equipmentById.get(item.equipmentId)} equipmentId={item.equipmentId} /></td>
            <td className={item.assigneeId ? "" : "muted"}>{technicianName(item.assigneeId)}</td>
            <td><StateBadge status={item.status} /></td>
            <td className="nowrap">{formatDate(item.updatedAt)}</td>
            <td><div className="list-actions">
              {canAssign && item.status === "SUBMITTED" && <div className="assign-control">
                <select aria-label={`为工单 ${item.id} 选择维修员`} value={selected} onChange={(e) => setSelectedAssignees({ ...selectedAssignees, [item.id]: e.target.value })}>
                  {technicians.map((technician) => <option key={technician.id} value={technician.id}>{technician.displayName}</option>)}
                </select>
                <button type="button" className="solid-button" data-action="assign" disabled={!selected} onClick={() => onTransition(item, "ASSIGNED", Number(selected))}>派单</button>
              </div>}
              {primary && <button type="button" className="solid-button" data-action={primary.key} onClick={primary.onClick}>{primary.label}</button>}
              {menu.length > 0 && <MoreMenu id={item.id} open={menuFor === item.id} label={`工单 ${item.title} 更多操作`} entries={menu}
                onToggle={(open) => setMenuFor(open ? item.id : null)} />}
            </div></td>
          </tr>;
        })}</tbody>
      </table></div>}
      {!workorders?.content.length && <Empty text={canAssign ? "没有符合条件的故障工单" : "暂无可接或分配给你的工单"} />}
    </section>
  </div>;
}
