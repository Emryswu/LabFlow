package com.arthur.labops.workorder;

import org.springframework.data.jpa.domain.Specification;

import com.arthur.labops.equipment.EquipmentStatus;

public final class WorkOrderSpecifications {

    private static final Specification<FaultWorkOrder> FAULT = (root, query, builder) ->
            builder.equal(root.get("category"), WorkOrderCategory.FAULT);

    private static final Specification<FaultWorkOrder> AVAILABLE_FOR_ASSIGNMENT = (root, query, builder) ->
            builder.and(
                    builder.equal(root.get("status"), WorkOrderStatus.SUBMITTED),
                    builder.notEqual(root.get("equipment").get("status"), EquipmentStatus.RETIRED));

    private WorkOrderSpecifications() {
    }

    public static Specification<FaultWorkOrder> claimable() {
        return FAULT.and(AVAILABLE_FOR_ASSIGNMENT);
    }

    public static Specification<FaultWorkOrder> visibleToTechnician(Long technicianId) {
        return FAULT.and(AVAILABLE_FOR_ASSIGNMENT.or((root, query, builder) ->
                builder.equal(root.get("assigneeId"), technicianId)));
    }
}
