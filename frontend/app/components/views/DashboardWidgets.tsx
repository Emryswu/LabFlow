import type { ReactNode } from "react";
import { conflictSummary } from "../../lib/conflicts";
import {
  buildEquipmentRing,
  buildSparkline,
  CHART_HEIGHT,
  dayTitle,
  equipmentNameOf,
  RING_CIRCUMFERENCE,
  RING_RADIUS,
  type Tone,
  trendChangeText,
  BAR_WIDTH,
} from "../../lib/dashboard";
import { formatDateRange, formatRelativeMoment, relativeDayLabel, statusLabels } from "../../lib/labels";
import type { WeekOverWeek } from "../../lib/trends";
import type { Equipment, Reservation, Stats, WorkOrder } from "../../lib/types";
import { categoryIcon, Icon, type IconName } from "../icons";
import { Empty, StatusBadge } from "../ui";

export type StatTrendProps = { data: WeekOverWeek; caption: string };

export function StatCard({ icon, tone, label, value, unit, note, trend, onOpen }: {
  icon: IconName;
  tone: Tone;
  label: string;
  value: number;
  unit: string;
  note: string;
  trend?: StatTrendProps;
  onOpen: () => void;
}) {
  return <article className="stat-card">
    <span className={`stat-icon tone-${tone}`}><Icon name={icon} size={24} /></span>
    <div>
      <small>{label}</small>
      <strong>{value.toLocaleString("zh-CN")}<em>{unit}</em></strong>
      <p>{note}</p>
    </div>
    {/* Spans the full card width: next to the number it gets cut off on mid-size screens. */}
    {trend && <StatTrend {...trend} unit={unit} />}
    <button type="button" className="stat-open" onClick={onOpen} aria-label={`查看${label}`}><Icon name="chevron" size={18} /></button>
  </article>;
}

/** Last 7 days as bars, plus the change against the 7 days before. */
function StatTrend({ data, caption, unit }: StatTrendProps & { unit: string }) {
  const { width, bars } = buildSparkline(data);
  const { change } = data;
  const summary = `${caption} ${data.current} ${unit}，前7天 ${data.previous} ${unit}`;
  return <div className="stat-trend">
    <svg className="spark" width={width} height={CHART_HEIGHT} viewBox={`0 0 ${width} ${CHART_HEIGHT}`} role="img" aria-label={summary}>
      {bars.map((bar) => (
        <rect
          key={bar.day}
          className={bar.className}
          x={bar.x}
          y={bar.y}
          width={BAR_WIDTH}
          height={bar.height}
          rx={bar.rx}
        ><title>{`${dayTitle(bar.day)} ${bar.count} ${unit}`}</title></rect>
      ))}
    </svg>
    <span className="trend-text">
      {caption} <b>{data.current}</b>
      <span className={`trend-change trend-${change.kind}`} title={`较前7天（${data.previous} ${unit}）`}>
        {(change.kind === "up" || change.kind === "down") && <Icon name={change.kind === "up" ? "arrowUp" : "arrowDown"} size={12} />}
        {trendChangeText(change)}
      </span>
    </span>
  </div>;
}

export function Panel({ icon, title, action, onAction, children }: {
  icon: IconName;
  title: string;
  action?: string;
  onAction?: () => void;
  children: ReactNode;
}) {
  return <article className="panel">
    <header>
      <h3><Icon name={icon} size={20} />{title}</h3>
      {action && onAction && <button type="button" onClick={onAction}>{action}<Icon name="chevron" size={15} /></button>}
    </header>
    <div className="panel-body">{children}</div>
  </article>;
}

function EquipThumb({ equipment }: { equipment?: Equipment }) {
  return <span className="equip-thumb" title={equipment?.category}><Icon name={categoryIcon(equipment?.category)} size={24} /></span>;
}

export function ReservationRow({ item, equipment, action }: { item: Reservation; equipment?: Equipment; action: ReactNode }) {
  const conflict = conflictSummary(item);
  return <div className="row-item">
    <EquipThumb equipment={equipment} />
    <div className="row-main">
      <b>{equipment?.name ?? `设备 #${item.equipmentId}`}</b>
      <small>{equipment ? `${equipment.code} · ` : ""}{item.purpose}</small>
      {conflict && <small className="conflict-note" title={conflict.detail}><Icon name="alert" size={12} />{conflict.text}</small>}
    </div>
    <div className="row-meta">
      <span>{formatDateRange(item.startTime, item.endTime, relativeDayLabel)}</span>
      <small>{item.requesterName}</small>
    </div>
    <div className="row-action">{action}</div>
  </div>;
}

export function WorkOrderRow({ item, equipment }: { item: WorkOrder; equipment?: Equipment }) {
  return <div className="row-item">
    <EquipThumb equipment={equipment} />
    <div className="row-main">
      <b>{equipment?.name ?? item.title}<span className={`tag tag-${item.priority.toLowerCase()}`}>{statusLabels[item.priority] ?? item.priority}</span></b>
      <small>{item.title}</small>
      <small className="row-sub">{item.reporterName} · {formatRelativeMoment(item.createdAt)}</small>
    </div>
    <div className="row-action"><StatusBadge value={item.status} /></div>
  </div>;
}

export function EquipmentStatusPanel({ stats, onOpen }: { stats: Stats | null; onOpen: () => void }) {
  const { total, segments } = buildEquipmentRing(stats);
  return <Panel icon="pie" title="设备运行状态" action="查看全部设备" onAction={onOpen}>
    <div className="status-chart">
      <svg viewBox="0 0 140 140" role="img" aria-label={`设备共 ${total} 台`}>
        <circle cx="70" cy="70" r={RING_RADIUS} fill="none" stroke="var(--track)" strokeWidth="18" />
        {total > 0 && segments.map((segment) => (
          <circle
            key={segment.key}
            cx="70"
            cy="70"
            r={RING_RADIUS}
            fill="none"
            stroke={segment.color}
            strokeWidth="18"
            strokeDasharray={`${segment.length} ${RING_CIRCUMFERENCE - segment.length}`}
            strokeDashoffset={-segment.offset}
            transform="rotate(-90 70 70)"
          />
        ))}
        <text x="70" y="68" textAnchor="middle" className="chart-total">{total.toLocaleString("zh-CN")}</text>
        <text x="70" y="88" textAnchor="middle" className="chart-caption">设备总数</text>
      </svg>
      <ul>
        {segments.map((segment) => <li key={segment.key}>
          <i style={{ background: segment.color }} />
          <span>{segment.label}</span>
          <b>{segment.value}</b>
          <small>{segment.percent}%</small>
        </li>)}
      </ul>
    </div>
  </Panel>;
}

type Activity = { key: string; at: string; icon: IconName; tone: Tone; kind: string; text: ReactNode };

export function ActivityFeed({ reservations, workorders, equipmentById }: {
  reservations: Reservation[];
  workorders: WorkOrder[];
  equipmentById: Map<number, Equipment>;
}) {
  const items: Activity[] = [
    ...reservations.map((item): Activity => ({
      key: `r-${item.id}`,
      at: item.createdAt,
      icon: "calendar",
      tone: item.status === "PENDING" ? "orange" : "blue",
      kind: "预约",
      text: <>{item.requesterName} 预约了 <b>{equipmentNameOf(equipmentById, item.equipmentId)}</b>，{statusLabels[item.status] ?? item.status}</>,
    })),
    ...workorders.map((item): Activity => ({
      key: `w-${item.id}`,
      at: item.createdAt,
      icon: "wrench",
      tone: item.priority === "URGENT" ? "coral" : "amber",
      kind: "工单",
      text: <>{item.reporterName} 报修 <b>{equipmentNameOf(equipmentById, item.equipmentId)}</b>：{item.title}</>,
    })),
  ].sort((a, b) => new Date(b.at).getTime() - new Date(a.at).getTime()).slice(0, 5);

  if (!items.length) return <Empty text="暂无近期动态" />;
  return <ol className="activity">
    {items.map((item) => <li key={item.key}>
      <span className={`activity-icon tone-${item.tone}`}><Icon name={item.icon} size={16} /></span>
      <em>{item.kind}</em>
      <p>{item.text}</p>
      <time dateTime={item.at}>{formatRelativeMoment(item.at)}</time>
    </li>)}
  </ol>;
}

export function PromoCard({ stats }: { stats: Stats | null }) {
  const total = stats?.equipmentTotal ?? 0;
  const inService = total - (stats?.equipmentRetired ?? 0);
  const reservable = inService > 0 ? Math.round(((stats?.equipmentAvailable ?? 0) / inService) * 100) : 0;
  return <article className="promo-card">
    <h3>更可靠的设备运行<br />支撑更卓越的科研创新</h3>
    <p>高效 · 安全 · 规范 · 可持续</p>
    <dl>
      <div><dt>在册设备</dt><dd>{total.toLocaleString("zh-CN")}</dd></div>
      <div><dt>今日排期</dt><dd>{stats?.reservationsToday ?? 0}</dd></div>
      <div><dt>当前可预约率</dt><dd>{reservable}%</dd></div>
    </dl>
  </article>;
}
