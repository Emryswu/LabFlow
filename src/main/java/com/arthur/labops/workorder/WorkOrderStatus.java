package com.arthur.labops.workorder;

import java.util.EnumSet;
import java.util.Set;

public enum WorkOrderStatus {
    SUBMITTED,
    ASSIGNED,
    IN_PROGRESS,
    RESOLVED,
    CLOSED,
    CANCELLED;

    /** Non-terminal statuses representing work orders that are still open. */
    public static final Set<WorkOrderStatus> ACTIVE = Set.copyOf(EnumSet.of(
            SUBMITTED,
            ASSIGNED,
            IN_PROGRESS,
            RESOLVED));
}
