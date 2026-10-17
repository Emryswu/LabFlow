package com.arthur.labops.dashboard;

import java.time.LocalDate;
import java.util.List;

/**
 * Daily counts for the last {@link DashboardService#TREND_DAYS} days, oldest
 * first; the last entry is today. Every series has one value per entry in
 * {@code days}. What each series covers depends on the caller's role, the same
 * way the list endpoints do (a student only sees their own reservations and
 * fault reports).
 *
 * @param reservationsCreated reservations submitted that day
 * @param reservationsScheduled reservations starting that day that are still on the calendar
 *                              ({@code ReservationStatuses.SCHEDULED})
 * @param workOrdersCreated work orders raised that day
 * @param workOrdersResolved work orders moved to RESOLVED that day
 * @param paymentDiscrepanciesCreated discrepancy tickets raised by reconciliation;
 *                                    {@code null} for everyone but admins
 */
public record DashboardTrendsResponse(
        List<LocalDate> days,
        List<Long> reservationsCreated,
        List<Long> reservationsScheduled,
        List<Long> workOrdersCreated,
        List<Long> workOrdersResolved,
        List<Long> paymentDiscrepanciesCreated
) {
}
