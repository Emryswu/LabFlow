package com.arthur.labops;

import static com.arthur.labops.TestAuth.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.arthur.labops.equipment.Equipment;
import com.arthur.labops.equipment.EquipmentRepository;
import com.arthur.labops.payment.PaymentOrder;
import com.arthur.labops.payment.PaymentOrderRepository;
import com.arthur.labops.payment.PaymentService;
import com.arthur.labops.reservation.Reservation;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.user.PlatformUser;
import com.arthur.labops.user.PlatformUserRepository;
import com.arthur.labops.workorder.FaultWorkOrder;
import com.arthur.labops.workorder.FaultWorkOrderRepository;
import com.arthur.labops.workorder.WorkOrderPriority;
import com.arthur.labops.workorder.WorkOrderStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dashboard-role-stats;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "labops.demo-data.enabled=false",
        "labops.reservation-max-active-per-user=50"
})
@AutoConfigureMockMvc
class DashboardRoleStatsIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EquipmentRepository equipmentRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PaymentOrderRepository paymentOrderRepository;
    @Autowired private FaultWorkOrderRepository workOrderRepository;
    @Autowired private PlatformUserRepository userRepository;

    @Test
    void studentAndTechnicianStatsAreNotTruncatedByPageSizeAndIncludePaidStates() throws Exception {
        TestAuth.clearCache();
        String studentToken = bearer(mockMvc, objectMapper, "student", "student123");
        String techToken = bearer(mockMvc, objectMapper, "technician", "tech123");
        PlatformUser student = userRepository.findByUsername("student").orElseThrow();
        PlatformUser teacher = userRepository.findByUsername("teacher").orElseThrow();
        PlatformUser tech = userRepository.findByUsername("technician").orElseThrow();

        Equipment equipment = equipmentRepository.saveAndFlush(new Equipment(
                "STAT-" + UUID.randomUUID().toString().substring(0, 8),
                "总览统计设备", "综合仪器", "实验楼 S101"));
        Instant base = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        // 13 PENDING + 1 APPROVED + 1 AWAITING_PAYMENT + 1 PAID + 1 REFUNDING + 1 CANCELLED
        for (int i = 0; i < 13; i++) {
            reservationRepository.save(new Reservation(
                    equipment, student.getId(), student.getDisplayName(), "待批-" + i,
                    base.plus(i * 2L, ChronoUnit.HOURS), base.plus(i * 2L + 1, ChronoUnit.HOURS),
                    base.plus(1, ChronoUnit.HOURS)));
        }
        Reservation approved = new Reservation(
                equipment, student.getId(), student.getDisplayName(), "已批",
                base.plus(30, ChronoUnit.HOURS), base.plus(31, ChronoUnit.HOURS), base);
        approved.approve();
        reservationRepository.save(approved);

        Reservation awaiting = new Reservation(
                equipment, student.getId(), student.getDisplayName(), "待支付",
                base.plus(32, ChronoUnit.HOURS), base.plus(33, ChronoUnit.HOURS), base);
        awaiting.awaitPayment(base);
        reservationRepository.save(awaiting);

        Reservation paid = new Reservation(
                equipment, student.getId(), student.getDisplayName(), "已支付",
                base.plus(34, ChronoUnit.HOURS), base.plus(35, ChronoUnit.HOURS), base);
        paid.markPaid();
        reservationRepository.save(paid);

        Reservation refunding = new Reservation(
                equipment, student.getId(), student.getDisplayName(), "退款中",
                base.plus(36, ChronoUnit.HOURS), base.plus(37, ChronoUnit.HOURS), base);
        refunding.beginRefund();
        reservationRepository.save(refunding);

        Reservation cancelled = new Reservation(
                equipment, student.getId(), student.getDisplayName(), "已取消",
                base.plus(38, ChronoUnit.HOURS), base.plus(39, ChronoUnit.HOURS), base);
        cancelled.cancel();
        reservationRepository.saveAndFlush(cancelled);

        // 14 SUBMITTED (URGENT) + 13 ASSIGNED to tech (HIGH) + 1 SUBMITTED discrepancy (excluded for tech)
        for (int i = 0; i < 14; i++) {
            workOrderRepository.save(new FaultWorkOrder(
                    equipment, student.getId(), student.getDisplayName(),
                    "待接单-" + i, "描述", WorkOrderPriority.URGENT));
        }
        for (int i = 0; i < 13; i++) {
            FaultWorkOrder assigned = new FaultWorkOrder(
                    equipment, teacher.getId(), teacher.getDisplayName(),
                    "处理中-" + i, "描述", WorkOrderPriority.HIGH);
            assigned.assign(tech.getId());
            if (i == 0) {
                assigned.transitionTo(WorkOrderStatus.IN_PROGRESS);
            }
            workOrderRepository.save(assigned);
        }
        workOrderRepository.saveAndFlush(FaultWorkOrder.discrepancy(
                equipment, teacher.getId(), "2026-11-26|" + PaymentService.orderNoFor(1L) + "|AMOUNT_MISMATCH",
                "差账", "不应该算给维修员", WorkOrderPriority.URGENT));
        Equipment retired = new Equipment(
                "LEGACY-" + UUID.randomUUID().toString().substring(0, 8),
                "已退役的测试设备", "综合仪器", "实验楼 S101");
        retired.retire();
        workOrderRepository.saveAndFlush(new FaultWorkOrder(
                equipmentRepository.saveAndFlush(retired), teacher.getId(), teacher.getDisplayName(),
                "历史待接单", "设备已退役，不应算作可接单", WorkOrderPriority.URGENT));
        paymentOrderRepository.saveAndFlush(new PaymentOrder(
                PaymentService.orderNoFor(paid.getId()), paid.getId(), equipment.getId(), student.getId(), 1000));

        // 13 PENDING, 16 ACTIVE (13 PENDING + 1 APPROVED + 1 AWAITING_PAYMENT + 1 PAID), 14 FAULT created by student
        mockMvc.perform(get("/api/dashboard/stats")
                        .header(HttpHeaders.AUTHORIZATION, studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myReservationsPending").value(13))
                .andExpect(jsonPath("$.myReservationsActive").value(16))
                .andExpect(jsonPath("$.myWorkOrdersTotal").value(14));

        // 14 SUBMITTED FAULT claimable, 13 ASSIGNED/IN_PROGRESS for tech, 27 URGENT/HIGH FAULT visible to tech
        mockMvc.perform(get("/api/dashboard/stats")
                        .header(HttpHeaders.AUTHORIZATION, techToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workOrdersClaimable").value(14))
                .andExpect(jsonPath("$.myWorkOrdersInProgress").value(13))
                .andExpect(jsonPath("$.technicianPriorityWorkOrders").value(27));
    }
}
