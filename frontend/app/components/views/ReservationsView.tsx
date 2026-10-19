import { useState } from "react";
import { EquipmentCell, menuEntries, MoreMenu, StateBadge } from "../table";
import { conflictSummary } from "../../lib/conflicts";
import { formatDate } from "../../lib/labels";
import { buildWeekSchedule, type ScheduleBlock } from "../../lib/schedule";
import type { Equipment, PageData, Reservation, UserProfile } from "../../lib/types";
import { Empty } from "../ui";
import { Icon } from "../icons";

/** Tall hours when the day is short; a full 24-hour day shrinks so the week stays on screen. */
const hourHeight = (hours: number) => Math.max(28, Math.min(44, Math.round(600 / hours)));

const clock = (minutes: number) => `${String(Math.floor(minutes / 60)).padStart(2, "0")}:${String(minutes % 60).padStart(2, "0")}`;

const kindLabels: Record<ScheduleBlock["kind"], string> = { pending: "待审批", occupied: "已占用", done: "已完成" };

function ReservationSchedule({ schedule, equipment, now }: { schedule: Reservation[] | null; equipment: Equipment[]; now: Date }) {
  const [equipmentId, setEquipmentId] = useState("");
  const all = schedule ?? [];
  const visible = equipmentId ? all.filter((item) => String(item.equipmentId) === equipmentId) : all;
  const week = buildWeekSchedule(visible, now);
  const [fromHour, toHour] = week.hours;
  const hourPx = hourHeight(toHour - fromHour);
  const height = (toHour - fromHour) * hourPx;
  const equipmentById = new Map(equipment.map((item) => [item.id, item]));
  const nowMinute = now.getHours() * 60 + now.getMinutes();
  const top = (minute: number) => ((Math.max(minute, fromHour * 60) - fromHour * 60) / 60) * hourPx;

  return <section className="panel schedule-panel">
    <header>
      <h3><Icon name="calendar" size={20} />未来 7 天排期</h3>
      <div className="schedule-tools">
        <span className="legend"><i className="legend-occupied" />已占用</span>
        <span className="legend"><i className="legend-pending" />待审批</span>
        <span className="legend"><i className="legend-conflict" />有冲突</span>
        <select aria-label="按设备筛选排期" value={equipmentId} onChange={(event) => setEquipmentId(event.target.value)}>
          <option value="">全部设备</option>
          {equipment.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
        </select>
      </div>
    </header>
    {week.blocks.length === 0
      ? <Empty text={equipmentId ? "这台设备未来 7 天还没有预约" : "未来 7 天还没有预约"} />
      : <div className="schedule-scroll">
        <div className="week" style={{ ["--week-height" as string]: `${height}px`, ["--hour-px" as string]: `${hourPx}px` }}>
          <div className="week-corner" />
          {week.days.map((day, index) => <div key={day.toISOString()} className={`week-head ${index === 0 ? "today" : ""}`}>
            <b>{index === 0 ? "今天" : new Intl.DateTimeFormat("zh-CN", { weekday: "short" }).format(day)}</b>
            <span>{day.getMonth() + 1}/{day.getDate()}</span>
          </div>)}
          <div className="week-hours" aria-hidden="true">
            {Array.from({ length: toHour - fromHour }, (_, index) => <span key={index} style={{ top: index * hourPx }}>{clock((fromHour + index) * 60)}</span>)}
          </div>
          {week.days.map((day, dayIndex) => <div key={day.toISOString()} className={`week-day ${dayIndex === 0 ? "today" : ""}`}>
            {dayIndex === 0 && nowMinute >= fromHour * 60 && nowMinute < toHour * 60
              && <i className="now-line" style={{ top: top(nowMinute) }} aria-label="当前时间" />}
            {week.blocks.filter((block) => block.dayIndex === dayIndex).map((block) => {
              const item = block.reservation;
              const device = equipmentById.get(item.equipmentId);
              const conflict = conflictSummary(item);
              const time = `${block.continuesBefore ? "…" : ""}${clock(block.startMinute)}–${clock(block.endMinute)}${block.continuesAfter ? "…" : ""}`;
              const title = [`${time} ${device?.name ?? `设备 #${item.equipmentId}`}`, `${kindLabels[block.kind]} · ${item.requesterName}`, item.purpose, conflict?.detail]
                .filter(Boolean).join("\n");
              return <div
                key={`${item.id}-${dayIndex}`}
                className={`week-block block-${block.kind} ${conflict ? "block-conflict" : ""}`}
                style={{
                  top: top(block.startMinute),
                  height: Math.max(top(block.endMinute) - top(block.startMinute), 18),
                  left: `calc(${(block.lane / block.lanes) * 100}% + 2px)`,
                  width: `calc(${100 / block.lanes}% - 4px)`,
                }}
                title={title}
              >
                <strong>{conflict && <Icon name="alert" size={12} />}{time}</strong>
                <span>{device?.name ?? `设备 #${item.equipmentId}`}</span>
                <small>{kindLabels[block.kind]} · {item.requesterName}</small>
              </div>;
            })}
          </div>)}
        </div>
      </div>}
  </section>;
}

export function ReservationsView({ user, reservations, scheduleReservations, equipment, filter, canApprove, onFilterChange, onLoad, onCreate, onDecide, onCancel, onComplete }: {
  user: UserProfile;
  reservations: PageData<Reservation> | null;
  scheduleReservations: Reservation[] | null;
  equipment: PageData<Equipment> | null;
  filter: string;
  canApprove: boolean;
  onFilterChange: (filter: string) => void;
  onLoad: () => void;
  onCreate: () => void;
  onDecide: (id: number, decision: "APPROVED" | "REJECTED") => void;
  onCancel: (id: number) => void;
  onComplete: (id: number) => void;
}) {
  const [menuFor, setMenuFor] = useState<number | null>(null);
  const equipmentList = equipment?.content ?? [];
  const equipmentById = new Map(equipmentList.map((item) => [item.id, item]));
  return <div className="view-stack">
    <section className="toolbar">
      <select value={filter} onChange={(e) => onFilterChange(e.target.value)}>
        <option value="">全部状态</option><option value="PENDING">待审批</option><option value="APPROVED">已批准</option>
        <option value="COMPLETED">已完成</option><option value="CANCELLED">已取消</option><option value="EXPIRED">已过期</option>
      </select>
      <button className="secondary" onClick={onLoad}>筛选</button>
      <div className="spacer"></div>
      <button className="primary" onClick={onCreate}><Icon name="plus" size={16} />发起预约</button>
    </section>
    <ReservationSchedule schedule={scheduleReservations} equipment={equipmentList} now={new Date()} />
    <section className="panel table-panel">
      <header><h3>预约记录</h3><small>共 {reservations?.totalElements ?? 0} 条</small></header>
      {Boolean(reservations?.content.length) && <div className="table-scroll"><table className="list-table">
        <thead><tr><th>设备</th><th>用途与申请人</th><th>使用时段</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>{reservations?.content.map((item) => {
          const conflict = conflictSummary(item);
          const menu = menuEntries(
            item.requesterId === user.id && ["PENDING", "APPROVED"].includes(item.status)
              && { key: "cancel", label: "取消预约", danger: true, onClick: () => onCancel(item.id) },
          );
          return <tr key={item.id} data-reservation-id={item.id}>
            <td><EquipmentCell equipment={equipmentById.get(item.equipmentId)} equipmentId={item.equipmentId} /></td>
            <td><div className="fault-cell"><b>{item.purpose}</b><small>{item.requesterName} · #{item.id}</small></div></td>
            <td className="nowrap"><b>{formatDate(item.startTime)}</b><small className="block">至 {formatDate(item.endTime)}</small></td>
            <td>
              <StateBadge status={item.status} />
              {conflict && <small className="conflict-note" title={conflict.detail}><Icon name="alert" size={13} />{conflict.text}</small>}
            </td>
            <td><div className="list-actions">
              {canApprove && item.status === "PENDING" && <>
                <button
                  type="button"
                  className="approve solid-button"
                  disabled={Boolean(conflict)}
                  title={conflict?.detail}
                  onClick={() => onDecide(item.id, "APPROVED")}
                >批准</button>
                <button type="button" className="text-link" onClick={() => onDecide(item.id, "REJECTED")}>拒绝</button>
              </>}
              {canApprove && (item.status === "APPROVED" || item.status === "PAID")
                && <button type="button" className="solid-button" data-action="complete" onClick={() => onComplete(item.id)}>完成</button>}
              {menu.length > 0 && <MoreMenu id={item.id} open={menuFor === item.id} label={`预约 #${item.id} 更多操作`} entries={menu}
                onToggle={(open) => setMenuFor(open ? item.id : null)} />}
            </div></td>
          </tr>;
        })}</tbody>
      </table></div>}
      {!reservations?.content.length && <Empty text="没有符合条件的预约" />}
    </section>
  </div>;
}
