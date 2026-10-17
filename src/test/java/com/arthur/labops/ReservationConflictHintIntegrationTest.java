package com.arthur.labops;

import static com.arthur.labops.TestAuth.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
 * The list endpoint's {@code conflictsWith} is only useful if it agrees with what
 * approval enforces. Rows live in the test transaction and are rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReservationConflictHintIntegrationTest {

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

    private final Instant base = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

    @Test
    void conflictHintMatchesWhatApprovalEnforces() throws Exception {
        String teacher = bearer(mockMvc, objectMapper, "teacher", "teacher123");
        String student = bearer(mockMvc, objectMapper, "student", "student123");
        Long teacherId = userId("teacher");
        Long studentId = userId("student");
        Equipment equipment = equipment("HINT");
        Equipment other = equipment("HINT-OTHER");

        Reservation held = reservation(equipment, teacherId, "周教授", 10, 12);
        held.approve();
        reservationRepository.saveAndFlush(held);
        Reservation overlapping = reservation(equipment, studentId, "林知夏", 11, 13);
        Reservation backToBack = reservation(equipment, studentId, "林知夏", 12, 13);
        Reservation otherDevice = reservation(other, studentId, "林知夏", 11, 12);

        Map<Long, List<Map<String, Object>>> asTeacher = conflicts(teacher);
        assertThat(asTeacher.get(overlapping.getId())).singleElement().satisfies(clash -> {
            assertThat(clash.get("id")).isEqualTo(held.getId().intValue());
            assertThat(clash.get("status")).isEqualTo("APPROVED");
            assertThat(clash.get("requesterName")).isEqualTo("周教授");
        });
        assertThat(asTeacher.get(backToBack.getId())).as("[12:00, 13:00) touches [10:00, 12:00) but does not overlap").isEmpty();
        assertThat(asTeacher.get(otherDevice.getId())).isEmpty();
        assertThat(asTeacher.get(held.getId())).as("only pending requests carry hints").isEmpty();

        // A student learns the slot is taken, not by whom.
        assertThat(conflicts(student).get(overlapping.getId())).singleElement()
                .satisfies(clash -> assertThat(clash.get("requesterName")).isNull());

        // And approval agrees with the hints. The rejected call goes last: it marks the
        // shared test transaction rollback-only.
        decide(teacher, backToBack.getId()).andExpect(status().isOk());
        decide(teacher, otherDevice.getId()).andExpect(status().isOk());
        decide(teacher, overlapping.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_CONFLICT"));
    }

    @Test
    void timeWindowReturnsReservationsTouchingIt() throws Exception {
        String teacher = bearer(mockMvc, objectMapper, "teacher", "teacher123");
        Long studentId = userId("student");
        Equipment equipment = equipment("WINDOW");
        Reservation endsAtWindowStart = reservation(equipment, studentId, "林知夏", 8, 10);
        Reservation inside = reservation(equipment, studentId, "林知夏", 11, 12);
        Reservation crossesWindowEnd = reservation(equipment, studentId, "林知夏", 13, 15);
        Reservation startsAtWindowEnd = reservation(equipment, studentId, "林知夏", 14, 16);

        Map<String, Object> page = list(teacher, Map.of(
                "equipmentId", equipment.getId().toString(),
                "from", at(10).toString(),
                "to", at(14).toString()));
        List<Object> ids = ((List<?>) page.get("content")).stream()
                .map(item -> (Object) ((Map<?, ?>) item).get("id"))
                .toList();
        assertThat(ids).containsExactlyInAnyOrder(inside.getId().intValue(), crossesWindowEnd.getId().intValue());
        assertThat(ids).doesNotContain(endsAtWindowStart.getId().intValue(), startsAtWindowEnd.getId().intValue());

        mockMvc.perform(get("/api/reservations")
                        .param("from", at(14).toString())
                        .param("to", at(10).toString())
                        .header(HttpHeaders.AUTHORIZATION, teacher))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_WINDOW"));
    }

    private Map<Long, List<Map<String, Object>>> conflicts(String token) throws Exception {
        Map<String, Object> page = list(token, Map.of("size", "100", "sort", "createdAt,desc"));
        Map<Long, List<Map<String, Object>>> result = new HashMap<>();
        for (Object item : (List<?>) page.get("content")) {
            Map<?, ?> reservation = (Map<?, ?>) item;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> clashes = (List<Map<String, Object>>) reservation.get("conflictsWith");
            result.put(((Number) reservation.get("id")).longValue(), clashes);
        }
        return result;
    }

    private Map<String, Object> list(String token, Map<String, String> params) throws Exception {
        var request = get("/api/reservations").header(HttpHeaders.AUTHORIZATION, token);
        params.forEach(request::param);
        byte[] body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        return objectMapper.readValue(body, new TypeReference<>() {});
    }

    private org.springframework.test.web.servlet.ResultActions decide(String token, Long id) throws Exception {
        return mockMvc.perform(patch("/api/reservations/{id}/decision", id)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVED\"}"));
    }

    private Long userId(String username) {
        return userRepository.findByUsername(username).orElseThrow().getId();
    }

    private Equipment equipment(String prefix) {
        return equipmentRepository.saveAndFlush(
                new Equipment(prefix + "-" + System.nanoTime(), "冲突提示测试设备", "综合仪器", "实验楼 C301"));
    }

    private Instant at(int hour) {
        return base.plus(hour, ChronoUnit.HOURS);
    }

    private Reservation reservation(Equipment equipment, Long requesterId, String requesterName, int fromHour, int toHour) {
        return reservationRepository.saveAndFlush(new Reservation(
                equipment, requesterId, requesterName, "冲突提示", at(fromHour), at(toHour), at(fromHour)));
    }
}
