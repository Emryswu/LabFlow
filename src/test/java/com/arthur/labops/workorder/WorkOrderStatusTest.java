package com.arthur.labops.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WorkOrderStatusTest {

    @Test
    void activeStatusesMatchAllNonTerminalStatesAndAreImmutable() {
        assertThat(WorkOrderStatus.ACTIVE).containsExactlyInAnyOrder(
                WorkOrderStatus.SUBMITTED,
                WorkOrderStatus.ASSIGNED,
                WorkOrderStatus.IN_PROGRESS,
                WorkOrderStatus.RESOLVED);
        assertThat(WorkOrderStatus.ACTIVE)
                .doesNotContain(WorkOrderStatus.CLOSED, WorkOrderStatus.CANCELLED);

        assertThatThrownBy(() -> WorkOrderStatus.ACTIVE.add(WorkOrderStatus.CLOSED))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
