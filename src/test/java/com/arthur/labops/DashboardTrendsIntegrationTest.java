package com.arthur.labops;

import static com.arthur.labops.TestAuth.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.arthur.labops.equipment.Equipment;
import com.arthur.labops.equipment.EquipmentRepository;
import com.arthur.labops.reservation.Reservation;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.user.PlatformUserRepository;
import com.arthur.labops.workorder.FaultWorkOrder;
import com.arthur.labops.workorder.FaultWorkOrderRepository;
import com.arthur.labops.workorder.WorkOrderPriority;
import com.arthur.labops.workorder.WorkOrderStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;

/**
 * Rows are inserted inside the test transaction and rolled back afterwards.
 * Other test classes share the same database, so every assertion compares the
 * series before and after the inserts instead of absolute counts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DashboardTrendsIntegrationTest {

    private static final int DAYS = 14;
    private static final int TODAY = DAYS - 1;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EquipmentRepository equipmentRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private FaultWorkOrderRepository workOrderRepository;

    @Autowired
    private PlatformUserRepository userRepository;

    @Test
    void seriesAreBucketedByDayAndScopedByRole() throws Exception {
        String student = bearer(mockMvc, objectMapper, "student", "student123");
        String teacher = bearer(mockMvc, objectMapper, "teacher", "teacher123");
        String technician = bearer(mockMvc, objectMapper, "technician", "tech123");
        String admin = bearer(mockMvc, objectMapper, "admin", "admin123");

        Map<String, Object> studentBefore = trends(student);
        Map<String, Object> teacherBefore = trends(teacher);
        Map<String, Object> technicianBefore = trends(technician);
        Map<String, Object> adminBefore = trends(admin);

        Equipment equipment = equipmentRepository.saveAndFlush(
                new Equipment("TREND-" + System.nanoTime(), "趋势统计测试设备", "综合仪器", "实验楼 T101"));
        Long studentId = userId("student");
        Long teacherId = userId("teacher");
        Long technicianId = userId("technician");
        Long technician2Id = userId("technician2");
        Long adminId = userId("admin");

        // Student request submitted 2 days ago, starting next week (outside the window).
        Reservation studentPending = reservation(equipment, studentId, "林知夏", daysFromToday(7));
        createdAt(studentPending, daysAgo(2));
        // Teacher's approved booking: submitted 5 days ago, used 3 days ago.
        Reservation teacherApproved = reservation(equipment, teacherId, "周教授", daysAgo(3));
        teacherApproved.approve();
        reservationRepository.saveAndFlush(teacherApproved);
        createdAt(teacherApproved, daysAgo(5));
        // Student cancelled a slot 3 days ago: still "created", never on the schedule.
        Reservation studentCancelled = reservation(equipment, studentId, "林知夏", daysAgo(3));
        studentCancelled.cancel();
        reservationRepository.saveAndFlush(studentCancelled);
        createdAt(studentCancelled, daysAgo(3));
        // Older than the window: must not show up anywhere.
        Reservation tooOld = reservation(equipment, teacherId, "周教授", daysAgo(20));
        createdAt(tooOld, daysAgo(20));

        FaultWorkOrder studentFault = workOrderRepository.saveAndFlush(new FaultWorkOrder(
                equipment, studentId, "林知夏", "目镜模糊", "无法对焦", WorkOrderPriority.MEDIUM));
        workOrderCreatedAt(studentFault, daysAgo(0));
        resolvedFault(equipment, teacherId, technicianId);
        resolvedFault(equipment, teacherId, technician2Id);
        FaultWorkOrder discrepancy = workOrderRepository.saveAndFlush(FaultWorkOrder.discrepancy(
                equipment, adminId, "TREND-" + System.nanoTime(), "差账", "金额不一致", WorkOrderPriority.HIGH));
        workOrderCreatedAt(discrepancy, daysAgo(4));

        Map<String, Object> studentAfter = trends(student);
        assertThat(studentAfter.get("days")).asInstanceOf(InstanceOfAssertFactories.LIST).hasSize(DAYS)
                .last().isEqualTo(LocalDate.now(ZoneId.systemDefault()).toString());
        assertThat(delta(studentBefore, studentAfter, "reservationsCreated")).isEqualTo(series(at(TODAY - 2, 1), at(TODAY - 3, 1)));
        assertThat(delta(studentBefore, studentAfter, "reservationsScheduled")).isEqualTo(series());
        assertThat(delta(studentBefore, studentAfter, "workOrdersCreated")).isEqualTo(series(at(TODAY, 1)));
        assertThat(studentAfter.get("paymentDiscrepanciesCreated")).isNull();

        Map<String, Object> teacherAfter = trends(teacher);
        assertThat(delta(teacherBefore, teacherAfter, "reservationsCreated"))
                .isEqualTo(series(at(TODAY - 2, 1), at(TODAY - 3, 1), at(TODAY - 5, 1)));
        assertThat(delta(teacherBefore, teacherAfter, "reservationsScheduled")).isEqualTo(series(at(TODAY - 3, 1)));
        // Reconciliation tickets stay out of non-admin work-order counts.
        assertThat(delta(teacherBefore, teacherAfter, "workOrdersCreated")).isEqualTo(series(at(TODAY, 1), at(TODAY - 6, 2)));
        assertThat(delta(teacherBefore, teacherAfter, "workOrdersResolved")).isEqualTo(series(at(TODAY - 1, 2)));
        assertThat(teacherAfter.get("paymentDiscrepanciesCreated")).isNull();

        Map<String, Object> technicianAfter = trends(technician);
        assertThat(delta(technicianBefore, technicianAfter, "workOrdersResolved")).isEqualTo(series(at(TODAY - 1, 1)));

        Map<String, Object> adminAfter = trends(admin);
        assertThat(delta(adminBefore, adminAfter, "workOrdersCreated"))
                .isEqualTo(series(at(TODAY, 1), at(TODAY - 4, 1), at(TODAY - 6, 2)));
        assertThat(delta(adminBefore, adminAfter, "paymentDiscrepanciesCreated")).isEqualTo(series(at(TODAY - 4, 1)));
    }

    private Map<String, Object> trends(String token) throws Exception {
        byte[] body = mockMvc.perform(get("/api/dashboard/trends").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        return objectMapper.readValue(body, new TypeReference<>() {});
    }

    private Long userId(String username) {
        return userRepository.findByUsername(username).orElseThrow().getId();
    }

    /** Noon local time, {@code days} days ago: far from either midnight. */
    private static Instant daysAgo(int days) {
        return daysFromToday(-days);
    }

    private static Instant daysFromToday(int days) {
        ZoneId zone = ZoneId.systemDefault();
        return LocalDate.now(zone).plusDays(days).atTime(LocalTime.NOON).atZone(zone).toInstant();
    }

    private Reservation reservation(Equipment equipment, Long requesterId, String requesterName, Instant start) {
        return reservationRepository.saveAndFlush(new Reservation(
                equipment, requesterId, requesterName, "趋势统计", start, start.plus(1, ChronoUnit.HOURS), start));
    }

    private void resolvedFault(Equipment equipment, Long reporterId, Long assigneeId) {
        FaultWorkOrder workOrder = new FaultWorkOrder(
                equipment, reporterId, "周教授", "真空泵异响", "运行时有异响", WorkOrderPriority.HIGH);
        workOrder.assign(assigneeId);
        workOrder.transitionTo(WorkOrderStatus.RESOLVED);
        workOrderRepository.saveAndFlush(workOrder);
        workOrderCreatedAt(workOrder, daysAgo(6));
        entityManager.createQuery("update FaultWorkOrder w set w.resolvedAt = :at where w.id = :id")
                .setParameter("at", daysAgo(1))
                .setParameter("id", workOrder.getId())
                .executeUpdate();
    }

    private void createdAt(Reservation reservation, Instant at) {
        entityManager.createQuery("update Reservation r set r.createdAt = :at where r.id = :id")
                .setParameter("at", at)
                .setParameter("id", reservation.getId())
                .executeUpdate();
    }

    private void workOrderCreatedAt(FaultWorkOrder workOrder, Instant at) {
        entityManager.createQuery("update FaultWorkOrder w set w.createdAt = :at where w.id = :id")
                .setParameter("at", at)
                .setParameter("id", workOrder.getId())
                .executeUpdate();
    }

    private static List<Long> delta(Map<String, Object> before, Map<String, Object> after, String series) {
        List<?> from = (List<?>) before.get(series);
        List<?> to = (List<?>) after.get(series);
        assertThat(to).hasSize(DAYS);
        List<Long> result = new ArrayList<>();
        for (int i = 0; i < DAYS; i++) {
            result.add(((Number) to.get(i)).longValue() - ((Number) from.get(i)).longValue());
        }
        return result;
    }

    private record Point(int index, long value) {
    }

    private static Point at(int index, long value) {
        return new Point(index, value);
    }

    private static List<Long> series(Point... points) {
        List<Long> values = new ArrayList<>(java.util.Collections.nCopies(DAYS, 0L));
        for (Point point : points) {
            values.set(point.index(), values.get(point.index()) + point.value());
        }
        return values;
    }
}
