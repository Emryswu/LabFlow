"use client";

import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ActionModals, type OrderDialogState, PaymentOrderModal } from "./components/ActionModals";
import { AppHeader, WorkspaceHeader } from "./components/AppHeader";
import { LoginView } from "./components/LoginView";
import { Toast } from "./components/ui";
import { type AuditFilter, AuditView, emptyAuditFilter } from "./components/views/AuditView";
import { DashboardView } from "./components/views/DashboardView";
import { EquipmentView } from "./components/views/EquipmentView";
import { ReservationsView } from "./components/views/ReservationsView";
import { WorkOrdersView } from "./components/views/WorkOrdersView";
import { api, clearAuthSession, clearLegacyAuthStorage, loginRequest, logoutRequest, refreshSession, setAuthSession, subscribeSession } from "./lib/api";
import { emptyEquipmentFilter, emptyEquipmentForm, emptyReservationForm, emptyWorkorderForm, parseReservationWindow } from "./lib/forms";
import { errorMessage } from "./lib/labels";
import { EQUIPMENT_LOOKUP, fetchAuditPage, fetchEquipmentBundle, fetchReservationBundle, LOOKUP_FAILED, settle } from "./lib/queries";
import { isViewAllowed } from "./lib/roles";
import type { AuditLog, Equipment, EquipmentFilter, EquipmentForm, ModalKind, PageData, PaymentOrder, Reservation, ReservationForm, Stats, Technician, Trends, UserProfile, View, WorkOrder, WorkOrderForm, WorkOrderStatus } from "./lib/types";
import { useToast } from "./lib/useToast";

export default function Home() {
  const [accessToken, setAccessToken] = useState("");
  const [user, setUser] = useState<UserProfile | null>(null);
  const [view, setView] = useState<View>("dashboard");
  const [busy, setBusy] = useState(false);
  const [pageLoading, setPageLoading] = useState(false);
  const { toast, notify, clearToast } = useToast();
  const [login, setLogin] = useState({ username: "student", password: "student123" });

  const [stats, setStats] = useState<Stats | null>(null);
  const [trends, setTrends] = useState<Trends | null>(null);
  const [equipment, setEquipment] = useState<PageData<Equipment> | null>(null);
  const [dashboardEquipment, setDashboardEquipment] = useState<PageData<Equipment> | null>(null);
  // Category filter options, loaded apart from the filtered, paged list so neither can hide one.
  const [equipmentCategories, setEquipmentCategories] = useState<string[] | null>(null);
  const [reservations, setReservations] = useState<PageData<Reservation> | null>(null);
  const [scheduleReservations, setScheduleReservations] = useState<Reservation[] | null>(null);
  const [workorders, setWorkorders] = useState<PageData<WorkOrder> | null>(null);
  const [auditLogs, setAuditLogs] = useState<PageData<AuditLog> | null>(null);
  const [technicians, setTechnicians] = useState<Technician[]>([]);

  const [modal, setModal] = useState<ModalKind | null>(null);
  const [orderDialog, setOrderDialog] = useState<OrderDialogState | null>(null);
  const [equipmentFilter, setEquipmentFilter] = useState<EquipmentFilter>(emptyEquipmentFilter);
  const [reservationFilter, setReservationFilter] = useState("");
  const [workorderFilter, setWorkorderFilter] = useState("");
  const [auditFilter, setAuditFilter] = useState<AuditFilter>(emptyAuditFilter);
  const [equipmentForm, setEquipmentForm] = useState<EquipmentForm>(emptyEquipmentForm);
  const [reservationForm, setReservationForm] = useState<ReservationForm>(emptyReservationForm);
  const [workorderForm, setWorkorderForm] = useState<WorkOrderForm>(emptyWorkorderForm);

  const appliedEquipmentFilter = useRef(equipmentFilter);
  // Paging keeps the filter that was last applied, not edits that have not been searched yet.
  const appliedAuditQuery = useRef({ filter: emptyAuditFilter, page: 0 });
  const auditRequestSequence = useRef(0);
  const equipmentRequestSequence = useRef(0);
  const trendsRequestSequence = useRef(0);
  const sessionGeneration = useRef(0);
  const sessionPrincipal = useRef("");
  const activeSessionRequests = useRef(new Set<AbortController>());
  const activeView = useMemo<View>(() => user && isViewAllowed(user.role, view) ? view : "dashboard", [user, view]);

  const beginSessionRequest = useCallback(() => {
    const generation = sessionGeneration.current;
    const controller = new AbortController();
    activeSessionRequests.current.add(controller);
    return {
      controller,
      isCurrent: () => generation === sessionGeneration.current && !controller.signal.aborted,
      finish: () => activeSessionRequests.current.delete(controller),
    };
  }, []);

  // A session change must not leave the previous user's rows on screen, and must not
  // let their in-flight responses land in the new session. Bumping the generation
  // invalidates late responses; aborting the controllers stops the requests themselves.
  const resetUserScopedState = useCallback(() => {
    sessionGeneration.current += 1;
    for (const controller of activeSessionRequests.current) controller.abort();
    activeSessionRequests.current.clear();
    equipmentRequestSequence.current += 1;
    appliedEquipmentFilter.current = emptyEquipmentFilter;
    auditRequestSequence.current += 1;
    appliedAuditQuery.current = { filter: emptyAuditFilter, page: 0 };
    setStats(null);
    setTrends(null);
    setEquipment(null);
    setDashboardEquipment(null);
    setEquipmentCategories(null);
    setReservations(null);
    setScheduleReservations(null);
    setWorkorders(null);
    setAuditLogs(null);
    setTechnicians([]);
    setModal(null);
    setOrderDialog(null);
    setBusy(false);
    setPageLoading(false);
    setEquipmentFilter(emptyEquipmentFilter);
    setReservationFilter("");
    setWorkorderFilter("");
    setAuditFilter(emptyAuditFilter);
    setEquipmentForm(emptyEquipmentForm);
    setReservationForm(emptyReservationForm);
    setWorkorderForm(emptyWorkorderForm);
  }, []);

  // Own lifecycle: optional charts must not keep page loading or approval busy.
  const loadTrends = useCallback(async (token: string) => {
    const requestSequence = ++trendsRequestSequence.current;
    const req = beginSessionRequest();
    try {
      const data = await api<Trends>("/api/dashboard/trends", token, { signal: req.controller.signal }).catch(() => null);
      if (req.isCurrent() && requestSequence === trendsRequestSequence.current) setTrends(data);
    } finally {
      req.finish();
    }
  }, [beginSessionRequest]);

  const load = useCallback(async (target: View, token = accessToken) => {
    if (!token) return;
    const generation = sessionGeneration.current;
    const controller = new AbortController();
    activeSessionRequests.current.add(controller);
    const isCurrentSessionRequest = () =>
      generation === sessionGeneration.current && !controller.signal.aborted;
    // Publishes a separately settled lookup after the page's own rows are already on screen.
    const applyLookup = async (
      lookup: Promise<{ ok: true; data: PageData<Equipment> } | { ok: false }>,
      apply: (data: PageData<Equipment>) => void,
    ) => {
      const result = await lookup;
      if (!isCurrentSessionRequest()) return;
      if (result.ok) apply(result.data);
      else notify(LOOKUP_FAILED, true);
    };
    setPageLoading(true);
    try {
      if (target === "dashboard") {
        void loadTrends(token);
        // Only used to show equipment names and category icons next to reservations / work orders.
        const lookup = settle(api<PageData<Equipment>>(EQUIPMENT_LOOKUP, token, { signal: controller.signal }));
        const [statsData, reservationData, workorderData] = await Promise.all([
          api<Stats>("/api/dashboard/stats", token, { signal: controller.signal }),
          api<PageData<Reservation>>("/api/reservations?size=12&sort=createdAt,desc", token, { signal: controller.signal }),
          api<PageData<WorkOrder>>("/api/work-orders?size=12&sort=createdAt,desc", token, { signal: controller.signal }),
        ]);
        if (!isCurrentSessionRequest()) return;
        setStats(statsData);
        setReservations(reservationData);
        setWorkorders(workorderData);
        await applyLookup(lookup, setDashboardEquipment);
      } else if (target === "equipment") {
        const requestSequence = ++equipmentRequestSequence.current;
        const filter = appliedEquipmentFilter.current;
        const [equipmentData, categories] = await fetchEquipmentBundle(token, filter, controller.signal);
        if (isCurrentSessionRequest() && requestSequence === equipmentRequestSequence.current) {
          setEquipment(equipmentData);
          setEquipmentCategories(categories);
        }
      } else if (target === "reservations") {
        const lookup = settle(api<PageData<Equipment>>(EQUIPMENT_LOOKUP, token, { signal: controller.signal }));
        const [reservationData, scheduleData] = await fetchReservationBundle(token, reservationFilter, controller.signal);
        if (!isCurrentSessionRequest()) return;
        setReservations(reservationData);
        setScheduleReservations(scheduleData);
        await applyLookup(lookup, setEquipment);
      } else if (target === "workorders") {
        const query = new URLSearchParams({ size: "30", sort: "createdAt,desc" });
        if (workorderFilter) query.set("status", workorderFilter);
        // Name lookup for the device column; the equipment page's list is filtered, so it is not reused.
        const lookup = settle(api<PageData<Equipment>>(EQUIPMENT_LOOKUP, token, { signal: controller.signal }));
        const workorderData = await api<PageData<WorkOrder>>(`/api/work-orders?${query}`, token, { signal: controller.signal });
        if (isCurrentSessionRequest()) setWorkorders(workorderData);
        await applyLookup(lookup, setDashboardEquipment);
      } else if (target === "audit") {
        const requestSequence = ++auditRequestSequence.current;
        const { filter, page } = appliedAuditQuery.current;
        const auditData = await fetchAuditPage(token, filter, page, controller.signal);
        // A slower response for an earlier page or filter must not overwrite the latest one.
        if (isCurrentSessionRequest() && requestSequence === auditRequestSequence.current) setAuditLogs(auditData);
      }
    } catch (error) {
      if (isCurrentSessionRequest()) notify(errorMessage(error, "加载失败"), true);
    } finally {
      activeSessionRequests.current.delete(controller);
      if (isCurrentSessionRequest()) setPageLoading(false);
    }
  }, [accessToken, reservationFilter, workorderFilter, notify, loadTrends]);

  // Keep React accessToken in sync when api() silently refreshes the HttpOnly-cookie session.
  // This is the single place that resets user-scoped state on logout or a principal change.
  // Refresh only clears the session on 204 (no refresh cookie) or 401 (invalid token).
  useEffect(() => {
    return subscribeSession((session) => {
      if (!session) {
        sessionPrincipal.current = "";
        resetUserScopedState();
        clearToast();
        setAccessToken("");
        setUser(null);
        return;
      }
      const nextPrincipal = `${session.user.id}:${session.user.username}:${session.user.role}`;
      if (sessionPrincipal.current !== nextPrincipal) {
        resetUserScopedState();
        clearToast();
        sessionPrincipal.current = nextPrincipal;
      }
      setAccessToken(session.accessToken);
      setUser(session.user);
    });
  }, [resetUserScopedState, clearToast]);

  useEffect(() => {
    clearLegacyAuthStorage();
    void refreshSession();
  }, []);

  useEffect(() => {
    if (!user || !accessToken) return;
    const loadTimer = window.setTimeout(() => void load(activeView, accessToken), 0);
    return () => window.clearTimeout(loadTimer);
  }, [user, accessToken, activeView, load]);

  const canManageEquipment = user?.role === "ADMIN" || user?.role === "TEACHER";
  // Mirrors SecurityConfig: POST /api/reservations is open to students, teachers and admins.
  const canReserve = user?.role === "STUDENT" || user?.role === "TEACHER" || user?.role === "ADMIN";
  const canApprove = user?.role === "ADMIN" || user?.role === "TEACHER";
  const canAssignWorkOrders = user?.role === "ADMIN";
  const canClaimWorkOrders = user?.role === "TECHNICIAN";
  const canProcessWorkOrders = user?.role === "ADMIN" || user?.role === "TECHNICIAN";

  useEffect(() => {
    if (!accessToken || !canAssignWorkOrders) return;
    const controller = new AbortController();
    api<Technician[]>("/api/users/technicians", accessToken, { signal: controller.signal })
      .then((data) => { if (!controller.signal.aborted) setTechnicians(data); })
      .catch(() => { if (!controller.signal.aborted) setTechnicians([]); });
    return () => controller.abort();
  }, [accessToken, canAssignWorkOrders]);

  async function submitLogin(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      const result = await loginRequest(login.username.trim(), login.password);
      setAuthSession(result);
      setAccessToken(result.accessToken);
      setUser(result.user);
      setView("dashboard");
    } catch (error) {
      notify(errorMessage(error, "登录失败"), true);
    } finally {
      setBusy(false);
    }
  }

  async function logout() {
    // clearAuthSession() notifies the session subscriber, which owns the state reset.
    clearAuthSession();
    await logoutRequest();
  }

  // Returns whether the mutation succeeded *and* still belongs to the current session,
  // so callers only discard form contents on a real success.
  async function mutate<T = unknown>(path: string, init: RequestInit, success: string | ((result: T) => string)): Promise<boolean> {
    const req = beginSessionRequest();
    setBusy(true);
    try {
      const result = await api<T>(path, accessToken, { ...init, signal: req.controller.signal });
      if (!req.isCurrent()) return false;
      notify(typeof success === "function" ? success(result) : success);
      setModal(null);
      await load(activeView);
      return req.isCurrent();
    } catch (error) {
      if (req.isCurrent()) notify(errorMessage(error, "操作失败"), true);
      return false;
    } finally {
      req.finish();
      if (req.isCurrent()) setBusy(false);
    }
  }

  async function submitEquipment(event: FormEvent) {
    event.preventDefault();
    if (await mutate("/api/equipment", { method: "POST", body: JSON.stringify({ ...equipmentForm, purchaseDate: equipmentForm.purchaseDate || null }) }, "设备已创建")) {
      setEquipmentForm(emptyEquipmentForm);
    }
  }

  async function submitReservation(event: FormEvent) {
    event.preventDefault();
    const parsed = parseReservationWindow(reservationForm.startTime, reservationForm.endTime);
    if (!parsed.ok) {
      notify(parsed.message, true);
      return;
    }
    const body = { ...reservationForm, equipmentId: Number(reservationForm.equipmentId), startTime: parsed.startIso, endTime: parsed.endIso };
    if (await mutate("/api/reservations", { method: "POST", body: JSON.stringify(body) }, "预约已提交，等待教师审批")) {
      setReservationForm(emptyReservationForm);
    }
  }

  async function submitWorkorder(event: FormEvent) {
    event.preventDefault();
    if (await mutate("/api/work-orders", { method: "POST", body: JSON.stringify({ ...workorderForm, equipmentId: Number(workorderForm.equipmentId) }) }, "故障工单已提交")) {
      setWorkorderForm(emptyWorkorderForm);
    }
  }

  async function openOrder(id: number, kind: "pay" | "refund") {
    const req = beginSessionRequest();
    setBusy(true);
    try {
      // PaymentService.orderNoFor gives each reservation one stable LF + 8-digit order number.
      const order = await api<PaymentOrder>(`/api/payments/orders/LF${String(id).padStart(8, "0")}`, accessToken, { signal: req.controller.signal });
      if (req.isCurrent()) setOrderDialog({ kind, order });
    } catch (error) {
      if (req.isCurrent()) notify(errorMessage(error, "支付订单加载失败"), true);
    } finally {
      req.finish();
      if (req.isCurrent()) setBusy(false);
    }
  }

  async function payOrder(order: PaymentOrder) {
    if (await mutate<PaymentOrder>(`/api/payments/orders/${order.orderNo}/pay`, { method: "POST" },
      (result) => result.status === "PAID" ? "支付成功" : "支付请求已提交，请刷新查看结果")) {
      setOrderDialog(null);
    }
  }

  async function cancelReservation(id: number) {
    if (await mutate<Reservation>(`/api/reservations/${id}/cancel`, { method: "PATCH" },
      (result) => result.status === "REFUNDING" ? "预约已取消，退款处理中" : "预约已取消")) {
      setOrderDialog(null);
    }
  }

  const decideReservation = (id: number, decision: "APPROVED" | "REJECTED") =>
    mutate(`/api/reservations/${id}/decision`, { method: "PATCH", body: JSON.stringify({ decision }) }, decision === "APPROVED" ? "预约已批准" : "预约已拒绝");

  const transitionWorkorder = (item: WorkOrder, targetStatus: WorkOrderStatus, assigneeId?: number) => {
    if (targetStatus === "ASSIGNED" && !assigneeId) return;
    void mutate(`/api/work-orders/${item.id}/status`, { method: "PATCH", body: JSON.stringify({ targetStatus, assigneeId }) }, targetStatus === "ASSIGNED" ? "派单成功" : "工单状态已更新");
  };

  async function openCreate(kind: ModalKind) {
    if ((kind === "reservation" || kind === "workorder") && (!equipment || !equipment.content.length)) {
      const req = beginSessionRequest();
      try {
        const list = await api<PageData<Equipment>>("/api/equipment?size=100", accessToken, { signal: req.controller.signal });
        if (!req.isCurrent()) return;
        setEquipment(list);
      } catch (error) {
        if (req.isCurrent()) notify(errorMessage(error, "设备加载失败"), true);
        return;
      } finally {
        req.finish();
      }
    }
    setModal(kind);
  }

  function applyEquipmentFilter() {
    appliedEquipmentFilter.current = equipmentFilter;
    void load("equipment");
  }

  function resetEquipmentFilter() {
    setEquipmentFilter(emptyEquipmentFilter);
    appliedEquipmentFilter.current = emptyEquipmentFilter;
    void load("equipment");
  }

  const applyAuditFilter = () => { appliedAuditQuery.current = { filter: auditFilter, page: 0 }; void load("audit"); };
  const resetAuditFilter = () => { setAuditFilter(emptyAuditFilter); appliedAuditQuery.current = { filter: emptyAuditFilter, page: 0 }; void load("audit"); };
  const goToAuditPage = (page: number) => { appliedAuditQuery.current = { ...appliedAuditQuery.current, page }; void load("audit"); };

  if (!user) return <LoginView login={login} busy={busy} toast={toast} onLoginChange={setLogin} onSubmit={submitLogin} onDemo={(username, password) => setLogin({ username, password })} />;

  return <main className="app-shell">
    <AppHeader user={user} view={activeView} onViewChange={setView} onLogout={logout} />
    <section className="workspace">
      {/* The dashboard and audit heroes carry their own titles. */}
      {activeView !== "dashboard" && activeView !== "audit" && <WorkspaceHeader user={user} view={activeView} onRefresh={() => void load(activeView)} />}
      {pageLoading && <div className="loading-bar"><span></span></div>}
      {activeView === "dashboard" && <DashboardView user={user} stats={stats} trends={trends} reservations={reservations} workorders={workorders} equipment={dashboardEquipment} busy={busy} canApprove={canApprove} onViewChange={setView} onOpenCreate={(kind) => void openCreate(kind)} onDecide={(id, decision) => void decideReservation(id, decision)} onRefresh={() => void load("dashboard")} />}
      {activeView === "equipment" && <EquipmentView equipment={equipment} categories={equipmentCategories} filter={equipmentFilter} canManage={canManageEquipment} canReserve={canReserve} onFilterChange={setEquipmentFilter} onLoad={applyEquipmentFilter} onReset={resetEquipmentFilter} onCreate={() => void openCreate("equipment")} onReserve={(item) => { setReservationForm({ ...emptyReservationForm, equipmentId: String(item.id) }); void openCreate("reservation"); }} onReport={(item) => { setWorkorderForm({ ...emptyWorkorderForm, equipmentId: String(item.id) }); void openCreate("workorder"); }} onRetire={(item) => void mutate(`/api/equipment/${item.id}/retire`, { method: "PATCH" }, `设备 ${item.code} 已退役`)} onRestore={(item) => void mutate(`/api/equipment/${item.id}/restore`, { method: "PATCH" }, `设备 ${item.code} 已恢复`)} />}
      {activeView === "reservations" && <ReservationsView user={user} reservations={reservations} scheduleReservations={scheduleReservations} equipment={equipment} filter={reservationFilter} canApprove={canApprove} busy={busy} onFilterChange={setReservationFilter} onLoad={() => void load("reservations")} onCreate={() => void openCreate("reservation")} onDecide={(id, decision) => void decideReservation(id, decision)} onPay={(id) => void openOrder(id, "pay")} onCancel={(item) => item.status === "PAID" ? void openOrder(item.id, "refund") : void cancelReservation(item.id)} onComplete={(id) => void mutate(`/api/reservations/${id}/complete`, { method: "PATCH" }, "预约已完成")} />}
      {activeView === "workorders" && <WorkOrdersView user={user} workorders={workorders} equipment={dashboardEquipment} technicians={technicians} filter={workorderFilter} canAssign={canAssignWorkOrders} canClaim={canClaimWorkOrders} canProcess={canProcessWorkOrders} onFilterChange={setWorkorderFilter} onLoad={() => void load("workorders")} onCreate={() => void openCreate("workorder")} onTransition={transitionWorkorder} onClaim={(item) => void mutate(`/api/work-orders/${item.id}/claim`, { method: "PATCH" }, "接单成功")} />}
      {activeView === "audit" && <AuditView auditLogs={auditLogs} filter={auditFilter} onFilterChange={setAuditFilter} onSearch={applyAuditFilter} onReset={resetAuditFilter} onPage={goToAuditPage} />}
    </section>
    <ActionModals modal={modal} busy={busy} equipment={equipment} equipmentForm={equipmentForm} reservationForm={reservationForm} workorderForm={workorderForm} onClose={() => setModal(null)} onEquipmentChange={setEquipmentForm} onReservationChange={setReservationForm} onWorkorderChange={setWorkorderForm} onSubmitEquipment={submitEquipment} onSubmitReservation={submitReservation} onSubmitWorkorder={submitWorkorder} />
    <PaymentOrderModal dialog={orderDialog} busy={busy} onClose={() => setOrderDialog(null)} onPay={(order) => void payOrder(order)} onConfirmCancel={(id) => void cancelReservation(id)} />
    <Toast toast={toast} />
  </main>;
}
