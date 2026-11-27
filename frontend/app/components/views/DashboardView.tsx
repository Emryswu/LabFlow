import { conflictSummary } from "../../lib/conflicts";
import { deriveDashboardSlices } from "../../lib/dashboard";
import { indexById, roleLabels } from "../../lib/labels";
import { dashboardCopy } from "../../lib/roles";
import { type TrendSeries, weekOverWeek } from "../../lib/trends";
import type { Equipment, ModalKind, PageData, Reservation, Stats, Trends, UserProfile, View, WorkOrder } from "../../lib/types";
import { Icon, type IconName } from "../icons";
import { Empty, StatusBadge } from "../ui";
import {
  ActivityFeed,
  EquipmentStatusPanel,
  Panel,
  PromoCard,
  ReservationRow,
  StatCard,
  type StatTrendProps,
  WorkOrderRow,
} from "./DashboardWidgets";

export function DashboardView({
  user,
  stats,
  trends = null,
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
  /** Optional: without it the cards just show their numbers. */
  trends?: Trends | null;
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
  const trend = (series: TrendSeries, caption: string): StatTrendProps | undefined => {
    const data = weekOverWeek(trends, series);
    return data ? { data, caption } : undefined;
  };
  const reservationList = reservations?.content ?? [];
  const workorderList = workorders?.content ?? [];
  const equipmentById = indexById(equipment?.content);
  const slices = deriveDashboardSlices(reservationList, workorderList);

  const reservationRow = (item: Reservation, withDecision: boolean) => <ReservationRow
    key={item.id}
    item={item}
    equipment={equipmentById.get(item.equipmentId)}
    action={withDecision && canApprove && item.status === "PENDING"
      ? <div className="decision">
        <button type="button" className="text-link" disabled={busy} onClick={() => onDecide(item.id, "REJECTED")}>拒绝</button>
        <button
          type="button"
          className="solid-button"
          disabled={busy || Boolean(conflictSummary(item))}
          title={conflictSummary(item)?.detail}
          onClick={() => onDecide(item.id, "APPROVED")}
        >通过</button>
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
        <StatCard icon="calendar" tone="orange" label="我的待审批" value={stats?.myReservationsPending ?? slices.myPending.length} unit="条" note="跟踪审批进度" trend={trend("reservationsCreated", "近7天提交")} onOpen={() => onViewChange("reservations")} />
        <StatCard icon="clock" tone="amber" label="进行中预约" value={stats?.myReservationsActive ?? slices.myActiveReservations.length} unit="条" note="含待批、已批与待付/已付" trend={trend("reservationsScheduled", "近7天排期")} onOpen={() => onViewChange("reservations")} />
        <StatCard icon="wrench" tone="coral" label="我的报修" value={stats?.myWorkOrdersTotal ?? workorders?.totalElements ?? workorderList.length} unit="条" note="故障进度可查" trend={trend("workOrdersCreated", "近7天报修")} onOpen={() => onViewChange("workorders")} />
        <StatCard icon="microscope" tone="green" label="可预约设备" value={stats?.equipmentAvailable ?? 0} unit="台" note={`共 ${stats?.equipmentTotal ?? 0} 台在册`} onOpen={() => onViewChange("equipment")} />
      </>}
      {user.role === "TEACHER" && <>
        <StatCard icon="calendar" tone="orange" label="待审批预约" value={stats?.reservationsPending ?? slices.pendingApprovals.length} unit="条" note="优先处理申请" trend={trend("reservationsCreated", "近7天提交")} onOpen={() => onViewChange("reservations")} />
        <StatCard icon="clock" tone="amber" label="今日相关排期" value={stats?.reservationsToday ?? 0} unit="条" note={`已排期 ${stats?.reservationsApproved ?? 0} 条`} trend={trend("reservationsScheduled", "近7天排期")} onOpen={() => onViewChange("reservations")} />
        <StatCard icon="microscope" tone="green" label="可预约设备" value={stats?.equipmentAvailable ?? 0} unit="台" note={`维护中 ${stats?.equipmentMaintenance ?? 0} 台`} onOpen={() => onViewChange("equipment")} />
        <StatCard icon="wrench" tone="coral" label="故障动态" value={stats?.workOrdersOpen ?? 0} unit="条" note={`紧急 ${stats?.workOrdersUrgent ?? 0} 条`} trend={trend("workOrdersCreated", "近7天报修")} onOpen={() => onViewChange("workorders")} />
      </>}
      {user.role === "TECHNICIAN" && <>
        <StatCard icon="inbox" tone="coral" label="待接单池" value={stats?.workOrdersClaimable ?? slices.claimPool.length} unit="条" note="可主动接单" trend={trend("workOrdersCreated", "近7天报修")} onOpen={() => onViewChange("workorders")} />
        <StatCard icon="wrench" tone="amber" label="我的处理中" value={stats?.myWorkOrdersInProgress ?? slices.myInProgress.length} unit="条" note="派给自己的在办单" trend={trend("workOrdersResolved", "近7天解决")} onOpen={() => onViewChange("workorders")} />
        <StatCard icon="microscope" tone="graphite" label="维护中设备" value={stats?.equipmentMaintenance ?? 0} unit="台" note={`共 ${stats?.equipmentTotal ?? 0} 台在册`} onOpen={() => onViewChange("equipment")} />
        <StatCard icon="alert" tone="orange" label="紧急/高优" value={stats?.technicianPriorityWorkOrders ?? slices.urgentOrders.length} unit="条" note="优先恢复可用" onOpen={() => onViewChange("workorders")} />
      </>}
      {user.role === "ADMIN" && <>
        <StatCard icon="clipboard" tone="graphite" label="设备在册" value={stats?.equipmentTotal ?? 0} unit="台" note={`可预约 ${stats?.equipmentAvailable ?? 0} 台`} onOpen={() => onViewChange("equipment")} />
        <StatCard icon="calendar" tone="orange" label="待审批预约" value={stats?.reservationsPending ?? 0} unit="条" note={`已排期 ${stats?.reservationsApproved ?? 0} 条`} trend={trend("reservationsCreated", "近7天提交")} onOpen={() => onViewChange("reservations")} />
        <StatCard icon="wrench" tone="coral" label="开放工单" value={stats?.workOrdersOpen ?? 0} unit="条" note={`紧急 ${stats?.workOrdersUrgent ?? 0} 条`} trend={trend("workOrdersCreated", "近7天新建")} onOpen={() => onViewChange("workorders")} />
        <StatCard icon="receipt" tone="amber" label="待处理差账" value={stats?.paymentDiscrepanciesOpen ?? 0} unit="笔" note="对账自动生成，人工核对" trend={trend("paymentDiscrepanciesCreated", "近7天新增")} onOpen={() => onViewChange("workorders")} />
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

      {(user.role === "TEACHER" || user.role === "ADMIN") && (
        <Panel icon="calendar" title="待审批预约" action="全部预约" onAction={() => onViewChange("reservations")}>
          {slices.pendingApprovals.slice(0, 3).map((item) => reservationRow(item, true))}
          {!slices.pendingApprovals.length && <Empty text="当前没有待审批预约，排期保持畅通" />}
        </Panel>
      )}

      {user.role === "TEACHER" && (
        <Panel icon="clock" title="近期排期" action="打开排期" onAction={() => onViewChange("reservations")}>
          {slices.upcomingSchedule.map((item) => reservationRow(item, false))}
          {!slices.upcomingSchedule.length && <Empty text="近期暂无占用排期" />}
        </Panel>
      )}

      {user.role === "TECHNICIAN" && <>
        <Panel icon="inbox" title="待接单池" action="去接单" onAction={() => onViewChange("workorders")}>
          {slices.claimPool.slice(0, 3).map(workorderRow)}
          {!slices.claimPool.length && <Empty text="待接单池为空，暂无未分配故障" />}
        </Panel>
        <Panel icon="wrench" title="我的处理中工单" action="打开工单" onAction={() => onViewChange("workorders")}>
          {slices.myInProgress.slice(0, 3).map(workorderRow)}
          {!slices.myInProgress.length && <Empty text="你还没有处理中的工单，可从待接单池认领" />}
        </Panel>
      </>}

      {user.role === "ADMIN" && (
        <Panel icon="wrench" title="开放工单" action="全部工单" onAction={() => onViewChange("workorders")}>
          {slices.openOrders.slice(0, 3).map(workorderRow)}
          {!slices.openOrders.length && <Empty text="没有开放中的工单，设备运行平稳" />}
        </Panel>
      )}
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

function QuickCard({ icon, title, hint, onClick }: { icon: IconName; title: string; hint: string; onClick: () => void }) {
  return <button type="button" className="quick-card" onClick={onClick}>
    <span><Icon name={icon} size={26} /></span>
    <b>{title}</b>
    <small>{hint}</small>
  </button>;
}
