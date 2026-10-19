"use client";

import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ActionModals } from "./components/ActionModals";
import { AppHeader, WorkspaceHeader } from "./components/AppHeader";
import { LoginView } from "./components/LoginView";
import { Toast } from "./components/ui";
import { type AuditFilter, AuditView, emptyAuditFilter } from "./components/views/AuditView";
import { DashboardView } from "./components/views/DashboardView";
import { EquipmentView } from "./components/views/EquipmentView";
import { ReservationsView } from "./components/views/ReservationsView";
import { WorkOrdersView } from "./components/views/WorkOrdersView";
import { api, clearAuthSession, clearLegacyAuthStorage, loginRequest, logoutRequest, refreshSession, setAuthSession, subscribeSession } from "./lib/api";
import { isViewAllowed } from "./lib/roles";
import type { AuditLog, Equipment, EquipmentFilter, EquipmentForm, ModalKind, PageData, Reservation, ReservationForm, Stats, Technician, ToastState, Trends, UserProfile, View, WorkOrder, WorkOrderForm } from "./lib/types";

const emptyEquipmentForm: EquipmentForm = { code: "", name: "", category: "", location: "", manufacturer: "", model: "", responsiblePerson: "", purchaseDate: "", description: "" };
const emptyReservationForm: ReservationForm = { equipmentId: "", purpose: "", startTime: "", endTime: "" };
const emptyWorkorderForm: WorkOrderForm = { equipmentId: "", title: "", description: "", priority: "MEDIUM" };
const emptyEquipmentFilter: EquipmentFilter = { keyword: "", status: "", category: "" };

function scheduleQuery(now = new Date()) {
  const from = new Date(now);
  from.setHours(0, 0, 0, 0);
  const to = new Date(from);
  to.setDate(to.getDate() + 7);
  const query = new URLSearchParams({ from: from.toISOString(), to: to.toISOString(), size: "100", sort: "startTime,asc" });
  query.append("sort", "id,asc");
  return query;
}

const EQUIPMENT_LOOKUP = "/api/equipment?size=100&sort=name,asc";
const LOOKUP_FAILED = "设备名录加载失败，设备列暂时只显示编号";

/**
 * Device names and icons only decorate a page's own rows, so the lookup settles on its own:
 * a failed lookup must never hide rows that did load. Rows fall back to "设备 #id" and the
 * page says why, instead of staying empty or stale.
 */
async function settle<T>(request: Promise<T>): Promise<{ ok: true; data: T } | { ok: false }> {
  try {
    return { ok: true, data: await request };
  } catch {
    return { ok: false };
  }
}

// The calendar filters locally, so publish the week only after every page succeeds.
async function loadSchedule(token: string, signal: AbortSignal): Promise<Reservation[]> {
  const query = scheduleQuery();
  const first = await api<PageData<Reservation>>(`/api/reservations?${query}`, token, { signal });
  const reservations = [...first.content];
  for (let page = 1; page < first.totalPages; page += 1) {
    query.set("page", String(page));
    const next = await api<PageData<Reservation>>(`/api/reservations?${query}`, token, { signal });
    reservations.push(...next.content);
  }
  return reservations;
}

export default function Home() {
  const [accessToken, setAccessToken] = useState("");
  const [user, setUser] = useState<UserProfile | null>(null);
  const [view, setView] = useState<View>("dashboard");
  const [busy, setBusy] = useState(false);
  const [pageLoading, setPageLoading] = useState(false);
  const [toast, setToast] = useState<ToastState>(null);
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
  const [equipmentFilter, setEquipmentFilter] = useState<EquipmentFilter>(emptyEquipmentFilter);
  const [reservationFilter, setReservationFilter] = useState("");
  const [workorderFilter, setWorkorderFilter] = useState("");
  const [auditFilter, setAuditFilter] = useState<AuditFilter>(emptyAuditFilter);
  const appliedEquipmentFilter = useRef(equipmentFilter);
  // Paging keeps the filter that was last applied, not edits that have not been searched yet.
  const appliedAuditQuery = useRef({ filter: emptyAuditFilter, page: 0 });
  const auditRequestSequence = useRef(0);
  const equipmentRequestSequence = useRef(0);
  const trendsRequestSequence = useRef(0);
  const [equipmentForm, setEquipmentForm] = useState<EquipmentForm>(emptyEquipmentForm);
  const [reservationForm, setReservationForm] = useState<ReservationForm>(emptyReservationForm);
  const [workorderForm, setWorkorderForm] = useState<WorkOrderForm>(emptyWorkorderForm);
  const sessionGeneration = useRef(0);
  const sessionPrincipal = useRef("");
  const activeSessionRequests = useRef(new Set<AbortController>());
  const activeView = useMemo<View>(
    () => user && isViewAllowed(user.role, view) ? view : "dashboard",
    [user, view],
  );

  const notify = useCallback((message: string, error = false) => {
    setToast({ message, error });
    window.setTimeout(() => setToast(null), 3200);
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
    const generation = sessionGeneration.current;
    const requestSequence = ++trendsRequestSequence.current;
    const controller = new AbortController();
    activeSessionRequests.current.add(controller);
    try {
      const data = await api<Trends>("/api/dashboard/trends", token, { signal: controller.signal })
        .catch(() => null);
      if (generation === sessionGeneration.current && !controller.signal.aborted
        && requestSequence === trendsRequestSequence.current) {
        setTrends(data);
      }
    } finally {
      activeSessionRequests.current.delete(controller);
    }
  }, []);

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
        const query = new URLSearchParams({ size: "30", sort: "createdAt,desc" });
        if (filter.keyword) query.set("keyword", filter.keyword);
        if (filter.status) query.set("status", filter.status);
        if (filter.category) query.set("category", filter.category);
        const [equipmentData, categories] = await Promise.all([
          api<PageData<Equipment>>(`/api/equipment?${query}`, token, { signal: controller.signal }),
          // Optional: without it the filter falls back to the styled categories.
          api<string[]>("/api/equipment/categories", token, { signal: controller.signal }).catch(() => null),
        ]);
        if (isCurrentSessionRequest() && requestSequence === equipmentRequestSequence.current) {
          setEquipment(equipmentData);
          setEquipmentCategories(categories);
        }
      } else if (target === "reservations") {
        const query = new URLSearchParams({ size: "30", sort: "createdAt,desc" });
        if (reservationFilter) query.set("status", reservationFilter);
        const lookup = settle(api<PageData<Equipment>>(EQUIPMENT_LOOKUP, token, { signal: controller.signal }));
        const [reservationData, scheduleData] = await Promise.all([
          api<PageData<Reservation>>(`/api/reservations?${query}`, token, { signal: controller.signal }),
          loadSchedule(token, controller.signal),
        ]);
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
        const query = new URLSearchParams({ page: String(page), size: "20", sort: "createdAt,desc" });
        if (filter.actorRole) query.set("actorRole", filter.actorRole);
        if (filter.action) query.set("action", filter.action);
        const auditData = await api<PageData<AuditLog>>(`/api/audit-logs?${query}`, token, { signal: controller.signal });
        // A slower response for an earlier page or filter must not overwrite the latest one.
        if (isCurrentSessionRequest() && requestSequence === auditRequestSequence.current) setAuditLogs(auditData);
      }
    } catch (error) {
      if (isCurrentSessionRequest()) {
        notify(error instanceof Error ? error.message : "加载失败", true);
      }
    } finally {
      activeSessionRequests.current.delete(controller);
      if (isCurrentSessionRequest()) setPageLoading(false);
    }
  }, [accessToken, reservationFilter, workorderFilter, notify, loadTrends]);

  // Keep React accessToken in sync when api() silently refreshes the HttpOnly-cookie session.
  // This is the single place that reacts to a session change, so every path that ends a
  // session (explicit logout, failed refresh, a different user signing in) clears the same state.
  useEffect(() => {
    return subscribeSession((session) => {
      if (!session) {
        sessionPrincipal.current = "";
        resetUserScopedState();
        setAccessToken("");
        setUser(null);
        return;
      }
      const nextPrincipal = `${session.user.id}:${session.user.username}:${session.user.role}`;
      if (sessionPrincipal.current !== nextPrincipal) {
        resetUserScopedState();
        sessionPrincipal.current = nextPrincipal;
      }
      setAccessToken(session.accessToken);
      setUser({
        id: session.user.id,
        username: session.user.username,
        displayName: session.user.displayName,
        role: session.user.role as UserProfile["role"],
      });
    });
  }, [resetUserScopedState]);

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
    api<Technician[]>("/api/users/technicians", accessToken)
      .then(setTechnicians)
      .catch(() => setTechnicians([]));
  }, [accessToken, canAssignWorkOrders]);

  async function submitLogin(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    try {
      const result = await loginRequest(login.username.trim(), login.password);
      setAuthSession(result);
      setAccessToken(result.accessToken);
      setUser({
        id: result.user.id,
        username: result.user.username,
        displayName: result.user.displayName,
        role: result.user.role as UserProfile["role"],
      });
      setView("dashboard");
    } catch (error) {
      notify(error instanceof Error ? error.message : "登录失败", true);
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
  async function mutate(path: string, init: RequestInit, success: string): Promise<boolean> {
    const generation = sessionGeneration.current;
    const controller = new AbortController();
    activeSessionRequests.current.add(controller);
    const isCurrentSessionRequest = () =>
      generation === sessionGeneration.current && !controller.signal.aborted;
    setBusy(true);
    try {
      await api(path, accessToken, { ...init, signal: controller.signal });
      if (!isCurrentSessionRequest()) return false;
      notify(success);
      setModal(null);
      await load(activeView);
      return isCurrentSessionRequest();
    } catch (error) {
      if (isCurrentSessionRequest()) {
        notify(error instanceof Error ? error.message : "操作失败", true);
      }
      return false;
    } finally {
      activeSessionRequests.current.delete(controller);
      if (isCurrentSessionRequest()) setBusy(false);
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
    const body = { ...reservationForm, equipmentId: Number(reservationForm.equipmentId), startTime: new Date(reservationForm.startTime).toISOString(), endTime: new Date(reservationForm.endTime).toISOString() };
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

  async function decideReservation(id: number, decision: "APPROVED" | "REJECTED") {
    await mutate(`/api/reservations/${id}/decision`, { method: "PATCH", body: JSON.stringify({ decision }) }, decision === "APPROVED" ? "预约已批准" : "预约已拒绝");
  }

  async function transitionWorkorder(item: WorkOrder, targetStatus: string, assigneeId?: number) {
    if (targetStatus === "ASSIGNED" && !assigneeId) return;
    const message = targetStatus === "ASSIGNED" ? "派单成功" : "工单状态已更新";
    await mutate(`/api/work-orders/${item.id}/status`, { method: "PATCH", body: JSON.stringify({ targetStatus, assigneeId }) }, message);
  }

  async function claimWorkorder(item: WorkOrder) {
    await mutate(`/api/work-orders/${item.id}/claim`, { method: "PATCH" }, "接单成功");
  }

  async function openCreate(kind: ModalKind) {
    if ((kind === "reservation" || kind === "workorder") && (!equipment || !equipment.content.length)) {
      try {
        setEquipment(await api<PageData<Equipment>>("/api/equipment?size=100", accessToken));
      } catch (error) {
        notify(error instanceof Error ? error.message : "设备加载失败", true);
        return;
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

  function applyAuditFilter() {
    appliedAuditQuery.current = { filter: auditFilter, page: 0 };
    void load("audit");
  }

  function resetAuditFilter() {
    setAuditFilter(emptyAuditFilter);
    appliedAuditQuery.current = { filter: emptyAuditFilter, page: 0 };
    void load("audit");
  }

  function goToAuditPage(page: number) {
    appliedAuditQuery.current = { ...appliedAuditQuery.current, page };
    void load("audit");
  }

  // Row actions open the usual modals with the device already chosen.
  function reserveEquipment(item: Equipment) {
    setReservationForm({ ...emptyReservationForm, equipmentId: String(item.id) });
    void openCreate("reservation");
  }

  function reportEquipment(item: Equipment) {
    setWorkorderForm({ ...emptyWorkorderForm, equipmentId: String(item.id) });
    void openCreate("workorder");
  }

  if (!user) return <LoginView login={login} busy={busy} toast={toast} onLoginChange={setLogin} onSubmit={submitLogin} onDemo={(username, password) => setLogin({ username, password })} />;

  return <main className="app-shell">
    <AppHeader user={user} view={activeView} onViewChange={setView} onLogout={logout} />
    <section className="workspace">
      {/* The dashboard and audit heroes carry their own titles. */}
      {activeView !== "dashboard" && activeView !== "audit" && <WorkspaceHeader user={user} view={activeView} onRefresh={() => void load(activeView)} />}
      {pageLoading && <div className="loading-bar"><span></span></div>}
      {activeView === "dashboard" && <DashboardView user={user} stats={stats} trends={trends} reservations={reservations} workorders={workorders} equipment={dashboardEquipment} busy={busy} canApprove={canApprove} onViewChange={setView} onOpenCreate={(kind) => void openCreate(kind)} onDecide={(id, decision) => void decideReservation(id, decision)} onRefresh={() => void load("dashboard")} />}
      {activeView === "equipment" && <EquipmentView equipment={equipment} categories={equipmentCategories} filter={equipmentFilter} canManage={canManageEquipment} canReserve={canReserve} onFilterChange={setEquipmentFilter} onLoad={applyEquipmentFilter} onReset={resetEquipmentFilter} onCreate={() => void openCreate("equipment")} onReserve={reserveEquipment} onReport={reportEquipment} onRetire={(item) => void mutate(`/api/equipment/${item.id}/retire`, { method: "PATCH" }, `设备 ${item.code} 已退役`)} onRestore={(item) => void mutate(`/api/equipment/${item.id}/restore`, { method: "PATCH" }, `设备 ${item.code} 已恢复`)} />}
      {activeView === "reservations" && <ReservationsView user={user} reservations={reservations} scheduleReservations={scheduleReservations} equipment={equipment} filter={reservationFilter} canApprove={canApprove} onFilterChange={setReservationFilter} onLoad={() => void load("reservations")} onCreate={() => void openCreate("reservation")} onDecide={(id, decision) => void decideReservation(id, decision)} onCancel={(id) => void mutate(`/api/reservations/${id}/cancel`, { method: "PATCH" }, "预约已取消")} onComplete={(id) => void mutate(`/api/reservations/${id}/complete`, { method: "PATCH" }, "预约已完成")} />}
      {activeView === "workorders" && user && <WorkOrdersView user={user} workorders={workorders} equipment={dashboardEquipment} technicians={technicians} filter={workorderFilter} canAssign={canAssignWorkOrders} canClaim={canClaimWorkOrders} canProcess={canProcessWorkOrders} onFilterChange={setWorkorderFilter} onLoad={() => void load("workorders")} onCreate={() => void openCreate("workorder")} onTransition={(item, target, assigneeId) => void transitionWorkorder(item, target, assigneeId)} onClaim={(item) => void claimWorkorder(item)} />}
      {activeView === "audit" && <AuditView auditLogs={auditLogs} filter={auditFilter} onFilterChange={setAuditFilter} onSearch={applyAuditFilter} onReset={resetAuditFilter} onPage={goToAuditPage} />}
    </section>
    <ActionModals modal={modal} busy={busy} equipment={equipment} equipmentForm={equipmentForm} reservationForm={reservationForm} workorderForm={workorderForm} onClose={() => setModal(null)} onEquipmentChange={setEquipmentForm} onReservationChange={setReservationForm} onWorkorderChange={setWorkorderForm} onSubmitEquipment={submitEquipment} onSubmitReservation={submitReservation} onSubmitWorkorder={submitWorkorder} />
    <Toast toast={toast} />
  </main>;
}
