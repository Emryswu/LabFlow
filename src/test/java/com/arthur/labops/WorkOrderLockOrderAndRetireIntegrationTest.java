package com.arthur.labops;

import static com.arthur.labops.TestAuth.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
import com.arthur.labops.user.PlatformUserRepository;
import com.arthur.labops.user.SystemAccountInitializer;
import com.arthur.labops.workorder.FaultWorkOrder;
import com.arthur.labops.workorder.FaultWorkOrderRepository;
import com.arthur.labops.workorder.WorkOrderPriority;
import com.arthur.labops.workorder.WorkOrderStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.arthur.labops.SqlCaptureStatementInspector")
@AutoConfigureMockMvc
class WorkOrderLockOrderAndRetireIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EquipmentRepository equipmentRepository;
    @Autowired private FaultWorkOrderRepository workOrderRepository;
    @Autowired private PlatformUserRepository userRepository;

    @BeforeEach
    void setUp() {
        TestAuth.clearCache();
    }

    @Test
    void studentFaultReportBlocksRetirementUntilWorkOrderIsClosed() throws Exception {
        String admin = bearer(mockMvc, objectMapper, "admin", "admin123");
        String student = bearer(mockMvc, objectMapper, "student", "student123");
        Long equipmentId = createEquipment(admin, "RETIRE-FAULT");
        Long workOrderId = createWorkOrder(student, equipmentId);

        mockMvc.perform(patch("/api/equipment/{id}/retire", equipmentId)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_HAS_OPEN_WORK_ORDERS"));

        transition(admin, workOrderId, Map.of("targetStatus", "CANCELLED"));

        mockMvc.perform(patch("/api/equipment/{id}/retire", equipmentId)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETIRED"));
    }

    @Test
    void openPaymentDiscrepancyTicketDoesNotBlockEquipmentRetirement() throws Exception {
        String admin = bearer(mockMvc, objectMapper, "admin", "admin123");
        Long equipmentId = createEquipment(admin, "RETIRE-DISC");
        Equipment equipment = equipmentRepository.findById(equipmentId).orElseThrow();
        Long systemId = userRepository.findByUsername(SystemAccountInitializer.SYSTEM_USERNAME)
                .orElseThrow().getId();
        workOrderRepository.saveAndFlush(FaultWorkOrder.discrepancy(
                equipment, systemId, "2026-11-26|LF99999999|AMOUNT_MISMATCH",
                "[对账差异] 测试订单", "不阻塞退役", WorkOrderPriority.MEDIUM));

        mockMvc.perform(patch("/api/equipment/{id}/retire", equipmentId)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETIRED"));
    }

    @Test
    void retiredEquipmentHistoricalWorkOrderCannotBeClaimedOrAssigned() throws Exception {
        String admin = bearer(mockMvc, objectMapper, "admin", "admin123");
        String student = bearer(mockMvc, objectMapper, "student", "student123");
        String tech = bearer(mockMvc, objectMapper, "technician", "tech123");
        Long techId = userId(tech);

        Long equipmentId = createEquipment(admin, "RETIRED-WO");
        Long workOrderId = createWorkOrder(student, equipmentId);
        // Simulate a historical SUBMITTED work order left before retirement (or restored/legacy state).
        Equipment equipment = equipmentRepository.findById(equipmentId).orElseThrow();
        equipment.retire();
        equipmentRepository.saveAndFlush(equipment);

        mockMvc.perform(get("/api/work-orders").param("equipmentId", equipmentId.toString())
                        .param("status", "SUBMITTED").header(HttpHeaders.AUTHORIZATION, tech))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/work-orders").param("equipmentId", equipmentId.toString())
                        .param("status", "SUBMITTED").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(workOrderId))
                .andExpect(jsonPath("$.content[0].equipmentStatus").value("RETIRED"));

        mockMvc.perform(patch("/api/work-orders/{id}/claim", workOrderId)
                        .header(HttpHeaders.AUTHORIZATION, tech))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_RETIRED"));

        mockMvc.perform(patch("/api/work-orders/{id}/status", workOrderId)
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "ASSIGNED",
                                "assigneeId", techId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_RETIRED"));

        FaultWorkOrder untouched = workOrderRepository.findById(workOrderId).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(WorkOrderStatus.SUBMITTED);
        assertThat(untouched.isEquipmentTakenOffline()).isFalse();
        assertThat(untouched.getAssigneeId()).isNull();

        transition(admin, workOrderId, Map.of("targetStatus", "CANCELLED"));
        assertThat(workOrderRepository.findById(workOrderId).orElseThrow().getStatus())
                .isEqualTo(WorkOrderStatus.CANCELLED);
    }

    @Test
    void claimAndAssignLockEquipmentBeforeFaultWorkOrder() throws Exception {
        String admin = bearer(mockMvc, objectMapper, "admin", "admin123");
        String student = bearer(mockMvc, objectMapper, "student", "student123");
        String tech = bearer(mockMvc, objectMapper, "technician", "tech123");
        Long techId = userId(tech);

        Long claimEquip = createEquipment(admin, "LOCK-CLAIM");
        Long claimOrder = createWorkOrder(student, claimEquip);
        SqlCaptureStatementInspector.clear();
        mockMvc.perform(patch("/api/work-orders/{id}/claim", claimOrder)
                        .header(HttpHeaders.AUTHORIZATION, tech))
                .andExpect(status().isOk());
        assertEquipmentLockedBeforeWorkOrder(SqlCaptureStatementInspector.snapshot(), "claim");

        Long assignEquip = createEquipment(admin, "LOCK-ASSIGN");
        Long assignOrder = createWorkOrder(student, assignEquip);
        SqlCaptureStatementInspector.clear();
        transition(admin, assignOrder, Map.of("targetStatus", "ASSIGNED", "assigneeId", techId));
        assertEquipmentLockedBeforeWorkOrder(SqlCaptureStatementInspector.snapshot(), "transition ASSIGNED");
    }

    private static void assertEquipmentLockedBeforeWorkOrder(List<String> sql, String label) {
        List<String> locks = sql.stream()
                .filter(statement -> statement.contains(" for update"))
                .toList();
        int equipmentLock = firstIndex(locks, s -> s.contains(" from equipment "));
        int workOrderLock = firstIndex(locks, s -> s.contains(" from fault_work_orders "));
        assertThat(equipmentLock).as(label + " must lock equipment; locks=%s", locks).isGreaterThanOrEqualTo(0);
        assertThat(workOrderLock).as(label + " must lock fault_work_orders; locks=%s", locks).isGreaterThanOrEqualTo(0);
        assertThat(equipmentLock).as(label + " must lock equipment before fault_work_orders; locks=%s", locks)
                .isLessThan(workOrderLock);
    }

    private static int firstIndex(List<String> items, java.util.function.Predicate<String> predicate) {
        for (int i = 0; i < items.size(); i++) {
            if (predicate.test(items.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private Long createEquipment(String admin, String prefix) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/equipment")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", prefix + "-" + UUID.randomUUID().toString().substring(0, 8),
                                "name", "测试设备-" + prefix,
                                "category", "并发测试",
                                "location", "实验楼 A101"))))
                .andExpect(status().isCreated())
                .andReturn();
        return idFrom(result);
    }

    private Long createWorkOrder(String token, Long equipmentId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/work-orders")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "equipmentId", equipmentId,
                                "title", "测试报修",
                                "description", "验证退役守卫与锁顺序",
                                "priority", "MEDIUM"))))
                .andExpect(status().isCreated())
                .andReturn();
        return idFrom(result);
    }

    private void transition(String token, Long workOrderId, Map<String, Object> body) throws Exception {
        mockMvc.perform(patch("/api/work-orders/{id}/status", workOrderId)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private Long userId(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andReturn();
        return idFrom(result);
    }

    private Long idFrom(MvcResult result) throws Exception {
        Map<String, Object> body = objectMapper.readValue(
                result.getResponse().getContentAsByteArray(), new TypeReference<>() {});
        return ((Number) body.get("id")).longValue();
    }
}
