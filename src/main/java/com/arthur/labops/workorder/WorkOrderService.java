package com.arthur.labops.workorder;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import com.arthur.labops.common.BusinessException;
import com.arthur.labops.equipment.Equipment;
import com.arthur.labops.equipment.EquipmentRepository;
import com.arthur.labops.equipment.EquipmentStatus;
import com.arthur.labops.equipment.EquipmentStatusService;
import com.arthur.labops.audit.AuditLogService;
import com.arthur.labops.payment.PaymentIdempotency;
import com.arthur.labops.payment.PaymentOrder;
import com.arthur.labops.payment.PaymentOrderRepository;
import com.arthur.labops.payment.PaymentRequest;
import com.arthur.labops.payment.PaymentRequestRepository;
import com.arthur.labops.payment.PaymentRequestStatus;
import com.arthur.labops.payment.PaymentTransactionType;
import com.arthur.labops.payment.reconcile.PaymentDiscrepancyTicketService;
import com.arthur.labops.reservation.Reservation;
import com.arthur.labops.reservation.ReservationClosure;
import com.arthur.labops.reservation.ReservationClosureService;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.arthur.labops.reservation.ReservationStatuses;
import com.arthur.labops.user.CurrentUserService;
import com.arthur.labops.user.PlatformUser;
import com.arthur.labops.user.PlatformUserRepository;
import com.arthur.labops.user.UserRole;

@Service
public class WorkOrderService {

    private static final Set<WorkOrderStatus> ACTIVE_STATUSES = WorkOrderStatus.ACTIVE;

    /**
     * Every reservation a fault report has to deal with, money included. Taking
     * equipment offline must not skip a paid reservation — that would leave the
     * user without the equipment and without their money.
     */
    private static final Set<ReservationStatus> OPEN_RESERVATIONS = ReservationStatuses.OPEN;

    private static final Map<WorkOrderStatus, Set<WorkOrderStatus>> ALLOWED_TRANSITIONS = Map.of(
            WorkOrderStatus.SUBMITTED, EnumSet.of(WorkOrderStatus.ASSIGNED, WorkOrderStatus.CANCELLED),
            WorkOrderStatus.ASSIGNED, EnumSet.of(WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.CANCELLED),
            WorkOrderStatus.IN_PROGRESS, EnumSet.of(WorkOrderStatus.RESOLVED),
            WorkOrderStatus.RESOLVED, EnumSet.of(WorkOrderStatus.CLOSED, WorkOrderStatus.IN_PROGRESS),
            WorkOrderStatus.CLOSED, EnumSet.noneOf(WorkOrderStatus.class),
            WorkOrderStatus.CANCELLED, EnumSet.noneOf(WorkOrderStatus.class));

    /**
     * Accounting discrepancies share the table, not the repair workflow. They are
     * admin-only and never get an equipment technician assignee, because assigning
     * a normal repair ticket is also the operation that takes equipment offline.
     */
    private static final Map<WorkOrderStatus, Set<WorkOrderStatus>> PAYMENT_DISCREPANCY_TRANSITIONS = Map.of(
            WorkOrderStatus.SUBMITTED, EnumSet.of(WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.CANCELLED),
            WorkOrderStatus.ASSIGNED, EnumSet.noneOf(WorkOrderStatus.class),
            WorkOrderStatus.IN_PROGRESS, EnumSet.of(WorkOrderStatus.RESOLVED, WorkOrderStatus.CANCELLED),
            WorkOrderStatus.RESOLVED, EnumSet.of(WorkOrderStatus.CLOSED, WorkOrderStatus.IN_PROGRESS),
            WorkOrderStatus.CLOSED, EnumSet.noneOf(WorkOrderStatus.class),
            WorkOrderStatus.CANCELLED, EnumSet.noneOf(WorkOrderStatus.class));

    private final EquipmentRepository equipmentRepository;
    private final FaultWorkOrderRepository workOrderRepository;
    private final ReservationRepository reservationRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final PaymentRequestRepository paymentRequestRepository;
    private final PlatformUserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final AuditLogService auditLogService;
    private final EquipmentStatusService equipmentStatusService;
    private final ReservationClosureService closureService;

    public WorkOrderService(EquipmentRepository equipmentRepository,
                            FaultWorkOrderRepository workOrderRepository,
                            ReservationRepository reservationRepository,
                            PaymentOrderRepository paymentOrderRepository,
                            PaymentRequestRepository paymentRequestRepository,
                            PlatformUserRepository userRepository,
                            CurrentUserService currentUserService,
                            AuditLogService auditLogService,
                            EquipmentStatusService equipmentStatusService,
                            ReservationClosureService closureService) {
        this.equipmentRepository = equipmentRepository;
        this.workOrderRepository = workOrderRepository;
        this.reservationRepository = reservationRepository;
        this.paymentOrderRepository = paymentOrderRepository;
        this.paymentRequestRepository = paymentRequestRepository;
        this.userRepository = userRepository;
        this.currentUserService = currentUserService;
        this.auditLogService = auditLogService;
        this.equipmentStatusService = equipmentStatusService;
        this.closureService = closureService;
    }

    @Transactional
    public WorkOrderResponse create(CreateWorkOrderRequest request) {
        PlatformUser reporter = currentUserService.getRequiredUser();
        Equipment equipment = lockReportableEquipment(request.equipmentId());

        boolean privileged = privilegedReporter(reporter);
        int cancelledReservations = privileged
                ? takeEquipmentOffline(equipment, reporter)
                : cancelOwnOpenReservations(equipment.getId(), reporter);

        FaultWorkOrder saved = saveWorkOrder(equipment, reporter, request, privileged);
        auditLogService.record(reporter, "WORK_ORDER_CREATED", "WORK_ORDER", saved.getId(),
                buildCreationDetail(equipment, cancelledReservations));
        return WorkOrderResponse.from(saved);
    }

    /**
     * 维修员原子接单：仅能将 SUBMITTED 故障工单接给自己。
     * 依赖悲观锁；并发下第二人得到 409。
     */
    @Transactional
    public WorkOrderResponse claim(Long id) {
        PlatformUser actor = requireClaimingTechnician();
        LockedWorkOrder locked = lockEquipmentAndWorkOrder(id);
        ensureClaimableFaultOrder(locked.workOrder());

        assignWorkOrder(locked.workOrder(), locked.equipment(), actor.getId(), actor);
        auditLogService.record(actor, "WORK_ORDER_CLAIMED", "WORK_ORDER", locked.workOrder().getId(),
                "维修员主动接单");
        return WorkOrderResponse.from(locked.workOrder());
    }

    @Transactional
    public WorkOrderResponse transition(Long id, TransitionWorkOrderRequest request) {
        PlatformUser actor = currentUserService.getRequiredUser();
        LockedWorkOrder locked = lockEquipmentAndWorkOrder(id);
        Equipment equipment = locked.equipment();
        FaultWorkOrder workOrder = locked.workOrder();

        WorkOrderStatus current = workOrder.getStatus();
        WorkOrderStatus target = request.targetStatus();
        if (workOrder.getCategory() == WorkOrderCategory.PAYMENT_DISCREPANCY) {
            return transitionPaymentDiscrepancy(actor, equipment, workOrder, current, target, request.assigneeId());
        }

        applyStandardTransition(actor, equipment, workOrder, current, target, request.assigneeId());
        syncEquipmentIfClosed(target, equipment.getId());
        auditLogService.record(actor, "WORK_ORDER_" + target.name(), "WORK_ORDER", workOrder.getId(),
                "工单从 " + current + " 流转到 " + target);
        return WorkOrderResponse.from(workOrder);
    }

    @Transactional(readOnly = true)
    public Page<WorkOrderResponse> findAll(WorkOrderStatus status, WorkOrderPriority priority,
                                           Long equipmentId, Pageable pageable) {
        PlatformUser actor = currentUserService.getRequiredUser();
        Specification<FaultWorkOrder> specification = buildQuerySpecification(status, priority, equipmentId, actor);
        return workOrderRepository.findAll(specification, pageable).map(WorkOrderResponse::from);
    }

    private Equipment lockReportableEquipment(Long equipmentId) {
        Equipment equipment = equipmentRepository.findByIdForUpdate(equipmentId)
                .orElseThrow(() -> new BusinessException(
                        "EQUIPMENT_NOT_FOUND", "设备不存在", HttpStatus.NOT_FOUND));
        if (equipment.getStatus() == EquipmentStatus.RETIRED) {
            throw new BusinessException(
                    "EQUIPMENT_RETIRED", "已退役设备不能创建故障工单", HttpStatus.CONFLICT);
        }
        if (workOrderRepository.existsByEquipmentIdAndCategoryAndStatusIn(
                equipment.getId(), WorkOrderCategory.FAULT, ACTIVE_STATUSES)) {
            throw new BusinessException(
                    "ACTIVE_WORK_ORDER_EXISTS", "该设备已有未关闭的故障工单", HttpStatus.CONFLICT);
        }
        return equipment;
    }

    private FaultWorkOrder saveWorkOrder(Equipment equipment, PlatformUser reporter,
                                         CreateWorkOrderRequest request, boolean privileged) {
        FaultWorkOrder workOrder = new FaultWorkOrder(
                equipment,
                reporter.getId(),
                reporter.getDisplayName(),
                request.title().trim(),
                request.description().trim(),
                request.priority());
        if (privileged) {
            // Only a privileged report holds the equipment offline. A student report
            // leaves it usable, and must keep doing so when sync() next runs.
            workOrder.markEquipmentTakenOffline();
        }
        return workOrderRepository.save(workOrder);
    }

    private static String buildCreationDetail(Equipment equipment, int cancelledReservations) {
        String detail = "设备 " + equipment.getCode() + " 报修";
        return cancelledReservations > 0
                ? detail + "，已取消冲突预约 " + cancelledReservations + " 条"
                : detail;
    }

    private PlatformUser requireClaimingTechnician() {
        PlatformUser actor = currentUserService.getRequiredUser();
        if (actor.getRole() != UserRole.TECHNICIAN) {
            throw new BusinessException(
                    "WORK_ORDER_CLAIM_FORBIDDEN", "只有维修员可以主动接单", HttpStatus.FORBIDDEN);
        }
        if (!actor.isEnabled()) {
            throw new BusinessException(
                    "ASSIGNEE_DISABLED", "当前账号已停用", HttpStatus.FORBIDDEN);
        }
        return actor;
    }

    private static void ensureClaimableFaultOrder(FaultWorkOrder workOrder) {
        if (workOrder.getCategory() != WorkOrderCategory.FAULT) {
            throw new BusinessException(
                    "WORK_ORDER_CATEGORY_FORBIDDEN",
                    "对账差异工单由管理员处理，不能进入维修员接单流程",
                    HttpStatus.FORBIDDEN);
        }
        if (workOrder.getStatus() != WorkOrderStatus.SUBMITTED) {
            throw new BusinessException(
                    "WORK_ORDER_ALREADY_CLAIMED", "工单已被接单或状态不可接", HttpStatus.CONFLICT);
        }
    }

    private void applyStandardTransition(PlatformUser actor, Equipment equipment, FaultWorkOrder workOrder,
                                         WorkOrderStatus current, WorkOrderStatus target, Long requestedAssigneeId) {
        if (!ALLOWED_TRANSITIONS.get(current).contains(target)) {
            throw new BusinessException(
                    "INVALID_WORK_ORDER_TRANSITION",
                    "工单不能从 " + current + " 流转到 " + target,
                    HttpStatus.CONFLICT);
        }
        assertTransitionAuthorized(actor, workOrder, target, requestedAssigneeId);
        if (target == WorkOrderStatus.ASSIGNED) {
            Long assigneeId = resolveAssigneeForAssignment(actor, requestedAssigneeId);
            assignWorkOrder(workOrder, equipment, assigneeId, actor);
        } else {
            workOrder.transitionTo(target);
        }
    }

    private void syncEquipmentIfClosed(WorkOrderStatus target, Long equipmentId) {
        if (target == WorkOrderStatus.CLOSED || target == WorkOrderStatus.CANCELLED) {
            workOrderRepository.flush();
            equipmentStatusService.sync(equipmentId);
        }
    }

    private static Specification<FaultWorkOrder> buildQuerySpecification(WorkOrderStatus status,
                                                                         WorkOrderPriority priority,
                                                                         Long equipmentId,
                                                                         PlatformUser actor) {
        Specification<FaultWorkOrder> specification = (root, query, builder) -> builder.conjunction();
        if (status != null) {
            specification = specification.and((root, query, builder) -> builder.equal(root.get("status"), status));
        }
        if (priority != null) {
            specification = specification.and((root, query, builder) -> builder.equal(root.get("priority"), priority));
        }
        if (equipmentId != null) {
            specification = specification.and((root, query, builder) ->
                    builder.equal(root.get("equipment").get("id"), equipmentId));
        }
        return scopeByRole(specification, actor);
    }

    private static Specification<FaultWorkOrder> scopeByRole(Specification<FaultWorkOrder> specification,
                                                             PlatformUser actor) {
        if (actor.getRole() == UserRole.TECHNICIAN) {
            return specification.and(WorkOrderSpecifications.visibleToTechnician(actor.getId()));
        }
        if (actor.getRole() == UserRole.ADMIN) {
            return specification;
        }
        Specification<FaultWorkOrder> faultOnly = specification.and((root, query, builder) ->
                builder.equal(root.get("category"), WorkOrderCategory.FAULT));
        if (actor.getRole() == UserRole.STUDENT) {
            return faultOnly.and((root, query, builder) ->
                    builder.equal(root.get("reporterId"), actor.getId()));
        }
        return faultOnly;
    }

    private WorkOrderResponse transitionPaymentDiscrepancy(PlatformUser actor,
                                                           Equipment equipment,
                                                           FaultWorkOrder workOrder,
                                                           WorkOrderStatus current,
                                                           WorkOrderStatus target,
                                                           Long requestedAssigneeId) {
        validateDiscrepancyTransition(actor, current, target, requestedAssigneeId);
        workOrder.transitionTo(target);
        if (target == WorkOrderStatus.CLOSED) {
            settleRefundingReservationIfApplicable(actor, equipment, workOrder);
        }
        auditLogService.record(actor, "WORK_ORDER_" + target.name(), "WORK_ORDER", workOrder.getId(),
                "对账差异工单从 " + current + " 流转到 " + target);
        return WorkOrderResponse.from(workOrder);
    }

    private static void validateDiscrepancyTransition(PlatformUser actor, WorkOrderStatus current,
                                                      WorkOrderStatus target, Long requestedAssigneeId) {
        if (actor.getRole() != UserRole.ADMIN) {
            throw new BusinessException(
                    "WORK_ORDER_CATEGORY_FORBIDDEN", "对账差异工单仅由管理员处理", HttpStatus.FORBIDDEN);
        }
        if (requestedAssigneeId != null) {
            throw new BusinessException(
                    "PAYMENT_DISCREPANCY_ASSIGNEE_FORBIDDEN",
                    "对账差异工单不进入维修员派单流程",
                    HttpStatus.BAD_REQUEST);
        }
        if (!PAYMENT_DISCREPANCY_TRANSITIONS.get(current).contains(target)) {
            throw new BusinessException(
                    "INVALID_WORK_ORDER_TRANSITION",
                    "对账差异工单不能从 " + current + " 流转到 " + target,
                    HttpStatus.CONFLICT);
        }
    }

    private void settleRefundingReservationIfApplicable(PlatformUser actor, Equipment equipment, FaultWorkOrder workOrder) {
        PaymentRequest haltedRefund = findHaltedCancellationRefund(workOrder);
        if (haltedRefund == null) {
            return;
        }
        PaymentOrder order = paymentOrderRepository.findByOrderNo(haltedRefund.getOrderNo()).orElse(null);
        if (order == null || !Objects.equals(order.getEquipmentId(), equipment.getId())) {
            return;
        }
        Reservation reservation = reservationRepository.findByIdForUpdate(order.getReservationId()).orElse(null);
        if (reservation != null && reservation.getStatus() == ReservationStatus.REFUNDING) {
            reservation.completeRefund();
            auditLogService.record(actor, "RESERVATION_CANCELLED", "RESERVATION", reservation.getId(),
                    "差账工单 #" + workOrder.getId() + " 已关闭，退款核销完成");
        }
    }

    private PaymentRequest findHaltedCancellationRefund(FaultWorkOrder workOrder) {
        String key = workOrder.getDiscrepancyKey();
        if (key == null || !key.startsWith(PaymentDiscrepancyTicketService.OUTBOUND_KEY_PREFIX)) {
            return null;
        }
        String idempotencyKey = key.substring(PaymentDiscrepancyTicketService.OUTBOUND_KEY_PREFIX.length());
        if (idempotencyKey.isBlank()) {
            return null;
        }
        return paymentRequestRepository.findByIdempotencyKey(idempotencyKey)
                .filter(request -> request.getType() == PaymentTransactionType.REFUND)
                .filter(request -> PaymentIdempotency.cancellationRefund(request.getOrderNo()).equals(idempotencyKey))
                .filter(request -> request.getStatus() == PaymentRequestStatus.ABANDONED
                        || request.getStatus() == PaymentRequestStatus.OBSOLETE)
                .orElse(null);
    }

    /**
     * 权限模型：
     * - 管理员：可派给任意维修员、取消、处理任意故障工单；对账差异走独立 admin-only 流程
     * - 维修员：可主动接故障单（claim）；派单接口只能指定自己；处理仅限自己的故障单；不可取消
     */
    private void assertTransitionAuthorized(PlatformUser actor, FaultWorkOrder workOrder,
                                            WorkOrderStatus target, Long requestedAssigneeId) {
        if (actor.getRole() == UserRole.ADMIN) {
            return;
        }
        if (actor.getRole() != UserRole.TECHNICIAN) {
            throw new BusinessException(
                    "WORK_ORDER_FORBIDDEN", "当前角色无权操作工单", HttpStatus.FORBIDDEN);
        }
        if (target == WorkOrderStatus.CANCELLED) {
            throw new BusinessException(
                    "WORK_ORDER_CANCEL_FORBIDDEN",
                    "只有管理员可以取消工单",
                    HttpStatus.FORBIDDEN);
        }
        if (target == WorkOrderStatus.ASSIGNED) {
            // 维修员通过 status 接口只能接给自己；推荐使用 /claim
            if (workOrder.getStatus() != WorkOrderStatus.SUBMITTED) {
                throw new BusinessException(
                        "WORK_ORDER_ALREADY_CLAIMED",
                        "工单已被接单或状态不可接",
                        HttpStatus.CONFLICT);
            }
            if (requestedAssigneeId != null && !Objects.equals(requestedAssigneeId, actor.getId())) {
                throw new BusinessException(
                        "WORK_ORDER_CLAIM_SELF_ONLY",
                        "维修员只能将工单接给自己，不能指定其他维修员",
                        HttpStatus.FORBIDDEN);
            }
            return;
        }
        if (!Objects.equals(workOrder.getAssigneeId(), actor.getId())) {
            throw new BusinessException(
                    "WORK_ORDER_NOT_ASSIGNED",
                    "只能处理分配给自己的工单",
                    HttpStatus.FORBIDDEN);
        }
    }

    private Long resolveAssigneeForAssignment(PlatformUser actor, Long requestedAssigneeId) {
        if (actor.getRole() == UserRole.TECHNICIAN) {
            return actor.getId();
        }
        return requireTechnicianAssignee(requestedAssigneeId);
    }

    private Long requireTechnicianAssignee(Long assigneeId) {
        if (assigneeId == null) {
            throw new BusinessException(
                    "ASSIGNEE_REQUIRED", "派单时必须指定维修人员", HttpStatus.BAD_REQUEST);
        }
        PlatformUser assignee = userRepository.findById(assigneeId)
                .orElseThrow(() -> new BusinessException(
                        "ASSIGNEE_NOT_FOUND", "指定的维修人员不存在", HttpStatus.BAD_REQUEST));
        if (assignee.getRole() != UserRole.TECHNICIAN) {
            throw new BusinessException(
                    "ASSIGNEE_NOT_TECHNICIAN", "只能派给维修员角色", HttpStatus.BAD_REQUEST);
        }
        if (!assignee.isEnabled()) {
            throw new BusinessException(
                    "ASSIGNEE_DISABLED", "该维修员账号已停用", HttpStatus.BAD_REQUEST);
        }
        return assignee.getId();
    }

    /** Acquires pessimistic locks in global Equipment -> FaultWorkOrder order. */
    private LockedWorkOrder lockEquipmentAndWorkOrder(Long workOrderId) {
        Long equipmentId = workOrderRepository.findEquipmentIdById(workOrderId)
                .orElseThrow(() -> new BusinessException(
                        "WORK_ORDER_NOT_FOUND", "故障工单不存在", HttpStatus.NOT_FOUND));
        Equipment equipment = equipmentRepository.findByIdForUpdate(equipmentId)
                .orElseThrow(() -> new BusinessException(
                        "EQUIPMENT_NOT_FOUND", "设备不存在", HttpStatus.NOT_FOUND));
        FaultWorkOrder workOrder = workOrderRepository.findByIdForUpdate(workOrderId)
                .orElseThrow(() -> new BusinessException(
                        "WORK_ORDER_NOT_FOUND", "故障工单不存在", HttpStatus.NOT_FOUND));
        return new LockedWorkOrder(equipment, workOrder);
    }

    private void assignWorkOrder(FaultWorkOrder workOrder, Equipment equipment, Long assigneeId, PlatformUser actor) {
        if (equipment.getStatus() == EquipmentStatus.RETIRED) {
            throw new BusinessException(
                    "EQUIPMENT_RETIRED",
                    "已退役设备不能接单或派单",
                    HttpStatus.CONFLICT);
        }
        workOrder.assign(assigneeId);
        takeEquipmentOfflineFor(workOrder, equipment, actor);
    }

    private record LockedWorkOrder(Equipment equipment, FaultWorkOrder workOrder) {
    }

    private boolean privilegedReporter(PlatformUser actor) {
        return actor.getRole() == UserRole.ADMIN || actor.getRole() == UserRole.TEACHER;
    }

    /**
     * An assigned work order always holds its equipment offline, so the flag is set
     * regardless of the equipment's current status — otherwise closing this order
     * would let sync() bring the equipment back while it is still under repair.
     */
    private void takeEquipmentOfflineFor(FaultWorkOrder workOrder, Equipment equipment, PlatformUser actor) {
        workOrder.markEquipmentTakenOffline();
        if (equipment.getStatus() == EquipmentStatus.RETIRED
                || equipment.getStatus() == EquipmentStatus.MAINTENANCE) {
            return;
        }
        takeEquipmentOffline(equipment, actor);
    }

    private int takeEquipmentOffline(Equipment equipment, PlatformUser actor) {
        int cancelled = cancelOpenReservations(equipment.getId(), actor, null);
        equipment.markMaintenance();
        return cancelled;
    }

    private int cancelOwnOpenReservations(Long equipmentId, PlatformUser actor) {
        return cancelOpenReservations(equipmentId, actor, actor.getId());
    }

    private int cancelOpenReservations(Long equipmentId, PlatformUser actor, Long onlyRequesterId) {
        List<Reservation> open = reservationRepository.findByEquipmentIdAndStatusInForUpdate(
                equipmentId, OPEN_RESERVATIONS);
        int cancelled = 0;
        for (Reservation reservation : open) {
            if (onlyRequesterId != null && !onlyRequesterId.equals(reservation.getRequesterId())) {
                continue;
            }
            // Same transition the owner's own cancel goes through, so a paid
            // reservation refunds here too instead of being closed on the quiet.
            ReservationClosure closure = closureService.close(reservation);
            if (closure == ReservationClosure.NOT_CLOSEABLE) {
                continue;
            }
            cancelled += 1;
            auditLogService.record(actor, "RESERVATION_CANCELLED", "RESERVATION", reservation.getId(),
                    closure == ReservationClosure.REFUND_PENDING
                            ? "设备报修联动取消预约，退款处理中"
                            : "设备报修联动取消预约");
        }
        return cancelled;
    }
}
