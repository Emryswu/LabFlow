package com.arthur.labops.dashboard;

public record DashboardStatsResponse(
        long usersTotal,
        long equipmentTotal,
        long equipmentAvailable,
        long equipmentMaintenance,
        long equipmentInUse,
        long equipmentRetired,
        long reservationsPending,
        long reservationsApproved,
        long reservationsToday,
        long workOrdersOpen,
        long workOrdersUrgent,
        long paymentDiscrepanciesOpen,
        long myReservationsPending,
        long myReservationsActive,
        long myWorkOrdersTotal,
        long workOrdersClaimable,
        long myWorkOrdersInProgress,
        long technicianPriorityWorkOrders
) {
}
