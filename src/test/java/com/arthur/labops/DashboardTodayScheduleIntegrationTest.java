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
import java.util.Map;

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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code reservationsToday} used to count every reservation starting today in
 * any status, on a closed interval: cancelled and rejected requests showed up
 * as today's schedule, and one starting exactly at the next midnight did too.
 * Rows are rolled back with the test transaction; the assertion is a delta
 * because other test classes share the database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DashboardTodayScheduleIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EquipmentRepository equipmentRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private PlatformUserRepository userRepository;

    @Test
    void todaysScheduleCountsOnlyReservationsStillOnTheCalendar() throws Exception {
        String teacher = bearer(mockMvc, objectMapper, "teacher", "teacher123");
        long before = reservationsToday(teacher);

        Equipment equipment = equipmentRepository.saveAndFlush(
                new Equipment("TODAY-" + System.nanoTime(), "今日排期测试设备", "综合仪器", "实验楼 T102"));
        Long studentId = userId("student");
        // Cancelled today: off the calendar.
        Reservation cancelled = reservation(equipment, studentId, "林知夏", daysAgo(0));
        cancelled.cancel();
        reservationRepository.saveAndFlush(cancelled);
        // Starts exactly at the next midnight: tomorrow's schedule, not today's.
        Instant tomorrow = LocalDate.now(ZoneId.systemDefault()).plusDays(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        reservationRepository.saveAndFlush(new Reservation(equipment, studentId, "林知夏", "跨午夜",
                tomorrow, tomorrow.plus(1, ChronoUnit.HOURS), tomorrow));
        // Waiting for approval today: on the calendar.
        reservation(equipment, studentId, "林知夏", daysAgo(0));

        assertThat(reservationsToday(teacher) - before).isEqualTo(1);
    }

    private long reservationsToday(String token) throws Exception {
        byte[] body = mockMvc.perform(get("/api/dashboard/stats").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        Map<String, Object> stats = objectMapper.readValue(body, new TypeReference<>() {});
        return ((Number) stats.get("reservationsToday")).longValue();
    }

    private Long userId(String username) {
        return userRepository.findByUsername(username).orElseThrow().getId();
    }

    /** Noon local time, {@code days} days ago: far from either midnight. */
    private static Instant daysAgo(int days) {
        ZoneId zone = ZoneId.systemDefault();
        return LocalDate.now(zone).minusDays(days).atTime(LocalTime.NOON).atZone(zone).toInstant();
    }

    private Reservation reservation(Equipment equipment, Long requesterId, String requesterName, Instant start) {
        return reservationRepository.saveAndFlush(new Reservation(
                equipment, requesterId, requesterName, "今日排期", start, start.plus(1, ChronoUnit.HOURS), start));
    }
}
