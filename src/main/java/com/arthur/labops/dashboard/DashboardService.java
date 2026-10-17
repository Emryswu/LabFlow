package com.arthur.labops.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.arthur.labops.equipment.EquipmentRepository;
import com.arthur.labops.equipment.EquipmentStatus;
import com.arthur.labops.reservation.Reservation;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.arthur.labops.reservation.ReservationStatuses;
import com.arthur.labops.user.CurrentUserService;
import com.arthur.labops.user.PlatformUser;
import com.arthur.labops.user.PlatformUserRepository;
import com.arthur.labops.user.SystemAccountInitializer;
import com.arthur.labops.user.UserRole;
import com.arthur.labops.workorder.FaultWorkOrder;
import com.arthur.labops.workorder.FaultWorkOrderRepository;
import com.arthur.labops.workorder.WorkOrderCategory;
import com.arthur.labops.workorder.WorkOrderPriority;
import com.arthur.labops.workorder.WorkOrderStatus;

import jakarta.persistence.criteria.Predicate;

@Service
public class DashboardService {

    /** Two weeks: this week against the week before, for the cards' week-over-week change. */
    public static final int TREND_DAYS = 14;

    private final PlatformUserRepository userRepository;
    private final EquipmentRepository equipmentRepository;
    private final ReservationRepository reservationRepository;
    private final FaultWorkOrderRepository workOrderRepository;
    private final CurrentUserService currentUserService;
    private final DashboardTrendQuery trendQuery;

    public DashboardService(PlatformUserRepository userRepository,
                            EquipmentRepository equipmentRepository,
                            ReservationRepository reservationRepository,
                            FaultWorkOrderRepository workOrderRepository,
                            CurrentUserService currentUserService,
                            DashboardTrendQuery trendQuery) {
        this.userRepository = userRepository;
        this.equipmentRepository = equipmentRepository;
        this.reservationRepository = reservationRepository;
        this.workOrderRepository = workOrderRepository;
        this.currentUserService = currentUserService;
        this.trendQuery = trendQuery;
    }

    @Transactional(readOnly = true)
    public DashboardStatsResponse getStats() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        Instant dayStart = today.atStartOfDay(zone).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant();
        EnumSet<WorkOrderStatus> openStatuses = EnumSet.of(
                WorkOrderStatus.SUBMITTED, WorkOrderStatus.ASSIGNED,
                WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.RESOLVED);
        return new DashboardStatsResponse(
                // Excludes the platform's own account: it is a foreign-key target for
                // records the platform raises for itself, not a user of the platform.
                userRepository.countByUsernameNot(SystemAccountInitializer.SYSTEM_USERNAME),
                equipmentRepository.count(),
                equipmentRepository.countByStatus(EquipmentStatus.AVAILABLE),
                equipmentRepository.countByStatus(EquipmentStatus.MAINTENANCE),
                equipmentRepository.countByStatus(EquipmentStatus.IN_USE),
                equipmentRepository.countByStatus(EquipmentStatus.RETIRED),
                reservationRepository.countByStatus(ReservationStatus.PENDING),
                reservationRepository.countByStatus(ReservationStatus.APPROVED),
                // Only reservations still on the calendar, counted on [today 00:00, tomorrow 00:00).
                reservationRepository.countByStartTimeGreaterThanEqualAndStartTimeLessThanAndStatusIn(
                        dayStart, dayEnd, ReservationStatuses.SCHEDULED),
                workOrderRepository.countByStatusIn(openStatuses),
                workOrderRepository.countByPriorityAndStatusIn(WorkOrderPriority.URGENT, openStatuses),
                // Discrepancy tickets the reconciliation job raised and nobody has closed yet.
                workOrderRepository.countByCategoryAndStatusIn(WorkOrderCategory.PAYMENT_DISCREPANCY, openStatuses));
    }

    /**
     * Per-day counts for the dashboard cards. Scoped like the list endpoints:
     * students see their own reservations and fault reports, only admins see
     * payment discrepancy tickets, and a technician's "resolved" series is the
     * work they closed themselves.
     */
    @Transactional(readOnly = true)
    public DashboardTrendsResponse getTrends() {
        PlatformUser actor = currentUserService.getRequiredUser();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate firstDay = LocalDate.now(zone).minusDays(TREND_DAYS - 1);
        Instant from = firstDay.atStartOfDay(zone).toInstant();
        Instant to = firstDay.plusDays(TREND_DAYS).atStartOfDay(zone).toInstant();

        List<Instant> reservationsCreated = trendQuery.timestamps(
                Reservation.class, "createdAt", from, to, reservationScope(actor, false));
        List<Instant> reservationsScheduled = trendQuery.timestamps(
                Reservation.class, "startTime", from, to, reservationScope(actor, true));
        List<Instant> workOrdersCreated = trendQuery.timestamps(
                FaultWorkOrder.class, "createdAt", from, to, workOrderScope(actor, false));
        List<Instant> workOrdersResolved = trendQuery.timestamps(
                FaultWorkOrder.class, "resolvedAt", from, to, workOrderScope(actor, true));
        List<Long> discrepancies = actor.getRole() == UserRole.ADMIN
                ? DailyBuckets.count(trendQuery.timestamps(FaultWorkOrder.class, "createdAt", from, to,
                        (root, builder) -> List.of(builder.equal(root.get("category"),
                                WorkOrderCategory.PAYMENT_DISCREPANCY))),
                        firstDay, TREND_DAYS, zone)
                : null;

        return new DashboardTrendsResponse(
                DailyBuckets.days(firstDay, TREND_DAYS),
                DailyBuckets.count(reservationsCreated, firstDay, TREND_DAYS, zone),
                DailyBuckets.count(reservationsScheduled, firstDay, TREND_DAYS, zone),
                DailyBuckets.count(workOrdersCreated, firstDay, TREND_DAYS, zone),
                DailyBuckets.count(workOrdersResolved, firstDay, TREND_DAYS, zone),
                discrepancies);
    }

    private static DashboardTrendQuery.Filter<Reservation> reservationScope(PlatformUser actor, boolean scheduledOnly) {
        return (root, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (actor.getRole() == UserRole.STUDENT) {
                predicates.add(builder.equal(root.get("requesterId"), actor.getId()));
            }
            if (scheduledOnly) {
                predicates.add(root.get("status").in(ReservationStatuses.SCHEDULED));
            }
            return predicates;
        };
    }

    private static DashboardTrendQuery.Filter<FaultWorkOrder> workOrderScope(PlatformUser actor, boolean resolved) {
        return (root, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            // Admins count every ticket, matching workOrdersOpen in the stats; everyone
            // else never sees reconciliation tickets in their work-order list.
            if (actor.getRole() != UserRole.ADMIN) {
                predicates.add(builder.equal(root.get("category"), WorkOrderCategory.FAULT));
            }
            if (actor.getRole() == UserRole.STUDENT) {
                predicates.add(builder.equal(root.get("reporterId"), actor.getId()));
            }
            if (resolved && actor.getRole() == UserRole.TECHNICIAN) {
                predicates.add(builder.equal(root.get("assigneeId"), actor.getId()));
            }
            return predicates;
        };
    }
}
