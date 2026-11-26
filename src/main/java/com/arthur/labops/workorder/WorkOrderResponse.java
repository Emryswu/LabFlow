package com.arthur.labops.workorder;

import java.time.Instant;

import com.arthur.labops.equipment.EquipmentStatus;

public record WorkOrderResponse(
        Long id,
        Long equipmentId,
        Long reporterId,
        String reporterName,
        String title,
        String description,
        WorkOrderPriority priority,
        WorkOrderStatus status,
        Long assigneeId,
        Instant createdAt,
        Instant updatedAt,
        Instant resolvedAt,
        WorkOrderCategory category,
        EquipmentStatus equipmentStatus
) {
    static WorkOrderResponse from(FaultWorkOrder workOrder) {
        return new WorkOrderResponse(
                workOrder.getId(),
                workOrder.getEquipment().getId(),
                workOrder.getReporterId(),
                workOrder.getReporterName(),
                workOrder.getTitle(),
                workOrder.getDescription(),
                workOrder.getPriority(),
                workOrder.getStatus(),
                workOrder.getAssigneeId(),
                workOrder.getCreatedAt(),
                workOrder.getUpdatedAt(),
                workOrder.getResolvedAt(),
                workOrder.getCategory(),
                workOrder.getEquipment().getStatus());
    }
}
