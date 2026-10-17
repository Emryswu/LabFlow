import type { ReactNode } from "react";
import { roleLabels, statusLabels } from "../../lib/labels";
import { dashboardCopy } from "../../lib/roles";
import type { Equipment, ModalKind, PageData, Reservation, Stats, UserProfile, View, WorkOrder } from "../../lib/types";
import { categoryIcon, Icon, type IconName } from "../icons";
import { Empty, StatusBadge } from "../ui";

type Tone = "orange" | "amber" | "coral" | "green" | "blue" | "graphite";

const OPEN_WORK_ORDER = new Set(["SUBMITTED", "ASSIGNED", "IN_PROGRESS", "RESOLVED"]);

export function DashboardView({
  user,
  stats,
  reservations,
  workorders,
  equipment,
  busy,
  canApprove,
  onViewChange,
  onOpenCreate,
  onDecide,
  onRefresh,
}: {
  user: UserProfile;
  stats: Stats | null;
  reservations: PageData<Reservation> | null;
  workorders: PageData<WorkOrder> | null;
  equipment: PageData<Equipment> | null;
  busy: boolean;
  canApprove: boolean;
  onViewChange: (view: View) => void;
  onOpenCreate: (kind: ModalKind) => void;
  onDecide: (id: number, decision: "APPROVED" | "REJECTED") => void;
  onRefresh: () => void;
}) {
  const copy = dashboardCopy(user.role, user.displayName);
  const reservationList = reservations?.content ?? [];
  const workorderList = workorders?.content ?? [];
  const equipmentById = new Map((equipment?.content ?? []).map((item) => [item.id, item]));

  const myPending = reservationList.filter((item) => item.status === "PENDING");
  const myActiveReservations = reservationList.filter((item) =>
    item.status === "PENDING" || item.status === "APPROVED");
  const pendingApprovals = reservationList.filter((item) => item.status === "PENDING");
  const upcomingSchedule = [...reservationList]
    .filter((item) => item.status === "PENDING" || item.status === "APPROVED")
    .sort((a, b) => new Date(a.startTime).getTime() - new Date(b.startTime).getTime())
    .slice(0, 4);
  const claimPool = workorderList.filter((item) => item.status === "SUBMITTED");
  const myInProgress = workorderList.filter((item) =>
    item.status === "ASSIGNED" || item.status === "IN_PROGRESS" || item.status === "RESOLVED");
  const urgentOrders = workorderList.filter((item) =>
    item.priority === "URGENT" || item.priority === "HIGH");
  const openOrders = workorderList.filter((item) => OPEN_WORK_ORDER.has(item.status));

  const reservationRow = (item: Reservation, withDecision: boolean) => <ReservationRow
    key={item.id}
    item={item}
    equipment={equipmentById.get(item.equipmentId)}
    action={withDecision && canApprove && item.status === "PENDING"
      ? <div className="decision">
        <button type="button" className="ghost-button" disabled={busy} onClick={() => onDecide(item.id, "REJECTED")}>拒绝</button>
        <button type="button" className="solid-button" disabled={busy} onClick={() => onDecide(item.id, "APPROVED")}>通过</button>
      </div>
      : <StatusBadge value={item.status} />}
  />;
  const workorderRow = (item: WorkOrder) => <WorkOrderRow key={item.id} item={item} equipment={equipmentById.get(item.equipmentId)} />;

  return <div className="view-stack dash" data-dashboard-role={user.role}>
    <section className="hero">
      <div className="hero-copy">
        <span className="hero-eyebrow">LABFLOW · {copy.kicker}</span>
        <h2>{copy.heading}</h2>
        <p>{copy.subtitle}</p>
        <div className="hero-chips">
          <span><Icon name="user" size={16} />{user.displayName} · {roleLabels[user.role]}</span>
          <span><Icon name="calendar" size={16} />{new Intl.DateTimeFormat("zh-CN", { year: "numeric", month: "long", day: "numeric", weekday: "long" }).format(new Date())}</span>
          <button type="button" onClick={onRefresh} title="刷新数据"><i className="live-dot" />数据已同步<Icon name="refresh" size={15} /></button>
        </div>
      </div>
      <p className="hero-motto" aria-hidden="true">
        科研向前，<br />从更高效的实验室开始。
        <small>BETTER LABS<br />BRIGHTER DISCOVERIES</small>
      </p>
    </section>

    <section className="stat-grid" aria-label="角色指标">
      {user.role === "STUDENT" && <>
        <StatCard icon="calendar" tone="orange" label="我的待审批" value={myPending.length} unit="条" note="跟踪审批进度" onOpen={() => onViewChange("reservations")} />
        <StatCard icon="clock" tone="amber" label="进行中预约" value={myActiveReservations.length} unit="条" note="含待批与已批准" onOpen={() => onViewChange("reservations")} />
        <StatCard icon="wrench" tone="coral" label="我的报修" value={workorders?.totalElements ?? workorderList.length} unit="条" note="故障进度可查" onOpen={() => onViewChange("workorders")} />
        <StatCard icon="microscope" tone="green" label="可预约设备" value={stats?.equipmentAvailable ?? 0} unit="台" note={`共 ${stats?.equipmentTotal ?? 0} 台在册`} onOpen={() => onViewChange("equipment")} />
      </>}
      {user.role === "TEACHER" && <>
        <StatCard icon="calendar" tone="orange" label="待审批预约" value={stats?.reservationsPending ?? pendingApprovals.length} unit="条" note="优先处理申请" onOpen={() => onViewChange("reservations")} />
        <StatCard icon="clock" tone="amber" label="今日相关排期" value={stats?.reservationsToday ?? 0} unit="条" note={`已排期 ${stats?.reservationsApproved ?? 0} 条`} onOpen={() => onViewChange("reservations")} />
        <StatCard icon="microscope" tone="green" label="可预约设备" value={stats?.equipmentAvailable ?? 0} unit="台" note={`维护中 ${stats?.equipmentMaintenance ?? 0} 台`} onOpen={() => onViewChange("equipment")} />
        <StatCard icon="wrench" tone="coral" label="故障动态" value={stats?.workOrdersOpen ?? 0} unit="条" note={`紧急 ${stats?.workOrdersUrgent ?? 0} 条`} onOpen={() => onViewChange("workorders")} />
      </>}
      {user.role === "TECHNICIAN" && <>
        <StatCard icon="inbox" tone="coral" label="待接单池" value={claimPool.length} unit="条" note="可主动接单" onOpen={() => onViewChange("workorders")} />
        <StatCard icon="wrench" tone="amber" label="我的处理中" value={myInProgress.length} unit="条" note="派给自己的在办单" onOpen={() => onViewChange("workorders")} />
        <StatCard icon="microscope" tone="graphite" label="维护中设备" value={stats?.equipmentMaintenance ?? 0} unit="台" note={`共 ${stats?.equipmentTotal ?? 0} 台在册`} onOpen={() => onViewChange("equipment")} />
        <StatCard icon="alert" tone="orange" label="紧急/高优" value={urgentOrders.length || (stats?.workOrdersUrgent ?? 0)} unit="条" note="优先恢复可用" onOpen={() => onViewChange("workorders")} />
      </>}
      {user.role === "ADMIN" && <>
        <StatCard icon="clipboard" tone="graphite" label="设备在册" value={stats?.equipmentTotal ?? 0} unit="台" note={`可预约 ${stats?.equipmentAvailable ?? 0} 台`} onOpen={() => onViewChange("equipment")} />
        <StatCard icon="calendar" tone="orange" label="待审批预约" value={stats?.reservationsPending ?? 0} unit="条" note={`已排期 ${stats?.reservationsApproved ?? 0} 条`} onOpen={() => onViewChange("reservations")} />
        <StatCard icon="wrench" tone="coral" label="开放工单" value={stats?.workOrdersOpen ?? 0} unit="条" note={`紧急 ${stats?.workOrdersUrgent ?? 0} 条`} onOpen={() => onViewChange("workorders")} />
        <StatCard icon="receipt" tone="amber" label="待处理差账" value={stats?.paymentDiscrepanciesOpen ?? 0} unit="笔" note="对账自动生成，人工核对" onOpen={() => onViewChange("workorders")} />
      </>}
    </section>

    <section className="dashboard-grid">
      <EquipmentStatusPanel stats={stats} onOpen={() => onViewChange("equipment")} />

      {user.role === "STUDENT" && <>
        <Panel icon="calendar" title="我的预约" action="查看全部" onAction={() => onViewChange("reservations")}>
          {reservationList.slice(0, 4).map((item) => reservationRow(item, false))}
          {!reservationList.length && <Empty text="你还没有预约，点击「快速预约」发起第一条" />}
        </Panel>
        <Panel icon="wrench" title="我的报修" action="查看全部" onAction={() => onViewChange("workorders")}>
          {workorderList.slice(0, 4).map(workorderRow)}
          {!workorderList.length && <Empty text="暂无报修记录，设备异常时可立即上报" />}
        </Panel>
      </>}

      {user.role === "TEACHER" && <>
        <Panel icon="calendar" title="待审批预约" action="全部预约" onAction={() => onViewChange("reservations")}>
          {pendingApprovals.slice(0, 3).map((item) => reservationRow(item, true))}
          {!pendingApprovals.length && <Empty text="当前没有待审批预约，排期保持畅通" />}
        </Panel>
        <Panel icon="clock" title="近期排期" action="打开排期" onAction={() => onViewChange("reservations")}>
          {upcomingSchedule.map((item) => reservationRow(item, false))}
          {!upcomingSchedule.length && <Empty text="近期暂无占用排期" />}
        </Panel>
      </>}

      {user.role === "TECHNICIAN" && <>
        <Panel icon="inbox" title="待接单池" action="去接单" onAction={() => onViewChange("workorders")}>
          {claimPool.slice(0, 3).map(workorderRow)}
          {!claimPool.length && <Empty text="待接单池为空，暂无未分配故障" />}
        </Panel>
        <Panel icon="wrench" title="我的处理中工单" action="打开工单" onAction={() => onViewChange("workorders")}>
          {myInProgress.slice(0, 3).map(workorderRow)}
          {!myInProgress.length && <Empty text="你还没有处理中的工单，可从待接单池认领" />}
        </Panel>
      </>}

      {user.role === "ADMIN" && <>
        <Panel icon="calendar" title="待审批预约" action="全部预约" onAction={() => onViewChange("reservations")}>
          {pendingApprovals.slice(0, 3).map((item) => reservationRow(item, true))}
          {!pendingApprovals.length && <Empty text="当前没有待审批预约，排期保持畅通" />}
        </Panel>
        <Panel icon="wrench" title="开放工单" action="全部工单" onAction={() => onViewChange("workorders")}>
          {openOrders.slice(0, 3).map(workorderRow)}
          {!openOrders.length && <Empty text="没有开放中的工单，设备运行平稳" />}
        </Panel>
      </>}
    </section>

    <section className="dashboard-grid lower">
      <Panel icon="clock" title="近期动态" action={user.role === "ADMIN" ? "打开审计" : undefined} onAction={() => onViewChange("audit")}>
        <ActivityFeed reservations={reservationList} workorders={workorderList} equipmentById={equipmentById} />
      </Panel>

      <Panel icon="bolt" title="快捷操作">
        <div className="quick-actions" aria-label="快捷操作">
          {user.role === "STUDENT" && <>
            <QuickCard icon="calendar" title="快速预约" hint="选择设备与时段提交申请" onClick={() => onOpenCreate("reservation")} />
            <QuickCard icon="wrench" title="故障报修" hint="设备异常时立即上报" onClick={() => onOpenCreate("workorder")} />
            <QuickCard icon="list" title="我的预约" hint="查看进度与取消" onClick={() => onViewChange("reservations")} />
          </>}
          {user.role === "TEACHER" && <>
            <QuickCard icon="calendar" title="去审批" hint="处理待审批预约队列" onClick={() => onViewChange("reservations")} />
            <QuickCard icon="microscope" title="设备管理" hint="建档、筛选与退役" onClick={() => onViewChange("equipment")} />
            <QuickCard icon="box" title="设备入库" hint="录入实验室资产" onClick={() => onOpenCreate("equipment")} />
          </>}
          {user.role === "TECHNICIAN" && <>
            <QuickCard icon="inbox" title="待接单池" hint="认领未分配故障单" onClick={() => onViewChange("workorders")} />
            <QuickCard icon="wrench" title="我的处理中" hint="开始维修与闭环" onClick={() => onViewChange("workorders")} />
            <QuickCard icon="microscope" title="维修状态" hint="查看维护中设备" onClick={() => onViewChange("equipment")} />
          </>}
          {user.role === "ADMIN" && <>
            <QuickCard icon="wrench" title="派单入口" hint="分配维修员处理故障" onClick={() => onViewChange("workorders")} />
            <QuickCard icon="alert" title="异常情况" hint="开放工单与紧急故障" onClick={() => onViewChange("workorders")} />
            <QuickCard icon="audit" title="审计日志" hint="关键操作留痕查询" onClick={() => onViewChange("audit")} />
          </>}
        </div>
      </Panel>

      <PromoCard stats={stats} />
    </section>
  </div>;
}

function StatCard({ icon, tone, label, value, unit, note, onOpen }: {
  icon: IconName; tone: Tone; label: string; value: number; unit: string; note: string; onOpen: () => void;
}) {
  return <article className="stat-card">
    <span className={`stat-icon tone-${tone}`}><Icon name={icon} size={24} /></span>
    <div>
      <small>{label}</small>
      <strong>{value.toLocaleString("zh-CN")}<em>{unit}</em></strong>
      <p>{note}</p>
    </div>
    <button type="button" className="stat-open" onClick={onOpen} aria-label={`查看${label}`}><Icon name="chevron" size={18} /></button>
  </article>;
}

function Panel({ icon, title, action, onAction, children }: {
  icon: IconName; title: string; action?: string; onAction?: () => void; children: ReactNode;
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

function ReservationRow({ item, equipment, action }: { item: Reservation; equipment?: Equipment; action: ReactNode }) {
  return <div className="row-item">
    <EquipThumb equipment={equipment} />
    <div className="row-main">
      <b>{equipment?.name ?? `设备 #${item.equipmentId}`}</b>
      <small>{equipment ? `${equipment.code} · ` : ""}{item.purpose}</small>
    </div>
    <div className="row-meta">
      <span>{formatRange(item.startTime, item.endTime)}</span>
      <small>{item.requesterName}</small>
    </div>
    <div className="row-action">{action}</div>
  </div>;
}

function WorkOrderRow({ item, equipment }: { item: WorkOrder; equipment?: Equipment }) {
  return <div className="row-item">
    <EquipThumb equipment={equipment} />
    <div className="row-main">
      <b>{equipment?.name ?? item.title}<span className={`tag tag-${item.priority.toLowerCase()}`}>{statusLabels[item.priority] ?? item.priority}</span></b>
      <small>{item.title}</small>
      <small className="row-sub">{item.reporterName} · {formatMoment(item.createdAt)}</small>
    </div>
    <div className="row-action"><StatusBadge value={item.status} /></div>
  </div>;
}

function EquipmentStatusPanel({ stats, onOpen }: { stats: Stats | null; onOpen: () => void }) {
  const total = stats?.equipmentTotal ?? 0;
  const segments = [
    { key: "available", label: "可预约", value: stats?.equipmentAvailable ?? 0, color: "var(--ok)" },
    { key: "in-use", label: "使用中", value: stats?.equipmentInUse ?? 0, color: "var(--info)" },
    { key: "maintenance", label: "维护中", value: stats?.equipmentMaintenance ?? 0, color: "var(--orange)" },
    { key: "retired", label: "已退役", value: stats?.equipmentRetired ?? 0, color: "var(--idle)" },
  ];
  const radius = 52;
  const circumference = 2 * Math.PI * radius;
  let offset = 0;
  return <Panel icon="pie" title="设备运行状态" action="查看全部设备" onAction={onOpen}>
    <div className="status-chart">
      <svg viewBox="0 0 140 140" role="img" aria-label={`设备共 ${total} 台`}>
        <circle cx="70" cy="70" r={radius} fill="none" stroke="var(--track)" strokeWidth="18" />
        {total > 0 && segments.map((segment) => {
          const length = (segment.value / total) * circumference;
          const dash = <circle key={segment.key} cx="70" cy="70" r={radius} fill="none" stroke={segment.color} strokeWidth="18"
            strokeDasharray={`${length} ${circumference - length}`} strokeDashoffset={-offset} transform="rotate(-90 70 70)" />;
          offset += length;
          return dash;
        })}
        <text x="70" y="68" textAnchor="middle" className="chart-total">{total.toLocaleString("zh-CN")}</text>
        <text x="70" y="88" textAnchor="middle" className="chart-caption">设备总数</text>
      </svg>
      <ul>
        {segments.map((segment) => <li key={segment.key}>
          <i style={{ background: segment.color }} />
          <span>{segment.label}</span>
          <b>{segment.value}</b>
          <small>{total ? Math.round((segment.value / total) * 100) : 0}%</small>
        </li>)}
      </ul>
    </div>
  </Panel>;
}

type Activity = { key: string; at: string; icon: IconName; tone: Tone; kind: string; text: ReactNode };

function ActivityFeed({ reservations, workorders, equipmentById }: {
  reservations: Reservation[]; workorders: WorkOrder[]; equipmentById: Map<number, Equipment>;
}) {
  const nameOf = (id: number) => equipmentById.get(id)?.name ?? `设备 #${id}`;
  const items: Activity[] = [
    ...reservations.map((item): Activity => ({
      key: `r-${item.id}`,
      at: item.createdAt,
      icon: "calendar",
      tone: item.status === "PENDING" ? "orange" : "blue",
      kind: "预约",
      text: <>{item.requesterName} 预约了 <b>{nameOf(item.equipmentId)}</b>，{statusLabels[item.status] ?? item.status}</>,
    })),
    ...workorders.map((item): Activity => ({
      key: `w-${item.id}`,
      at: item.createdAt,
      icon: "wrench",
      tone: item.priority === "URGENT" ? "coral" : "amber",
      kind: "工单",
      text: <>{item.reporterName} 报修 <b>{nameOf(item.equipmentId)}</b>：{item.title}</>,
    })),
  ].sort((a, b) => new Date(b.at).getTime() - new Date(a.at).getTime()).slice(0, 5);

  if (!items.length) return <Empty text="暂无近期动态" />;
  return <ol className="activity">
    {items.map((item) => <li key={item.key}>
      <span className={`activity-icon tone-${item.tone}`}><Icon name={item.icon} size={16} /></span>
      <em>{item.kind}</em>
      <p>{item.text}</p>
      <time dateTime={item.at}>{formatMoment(item.at)}</time>
    </li>)}
  </ol>;
}

function QuickCard({ icon, title, hint, onClick }: { icon: IconName; title: string; hint: string; onClick: () => void }) {
  return <button type="button" className="quick-card" onClick={onClick}>
    <span><Icon name={icon} size={26} /></span>
    <b>{title}</b>
    <small>{hint}</small>
  </button>;
}

function PromoCard({ stats }: { stats: Stats | null }) {
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

function dayLabel(date: Date) {
  const today = new Date();
  const startOf = (value: Date) => new Date(value.getFullYear(), value.getMonth(), value.getDate()).getTime();
  const diff = Math.round((startOf(date) - startOf(today)) / 86_400_000);
  if (diff === 0) return "今天";
  if (diff === 1) return "明天";
  if (diff === -1) return "昨天";
  return `${date.getMonth() + 1}月${date.getDate()}日`;
}

const clock = (date: Date) => `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;

function formatRange(start: string, end: string) {
  const from = new Date(start);
  const to = new Date(end);
  return `${dayLabel(from)} ${clock(from)}–${clock(to)}`;
}

function formatMoment(value: string) {
  const date = new Date(value);
  return `${dayLabel(date)} ${clock(date)}`;
}
