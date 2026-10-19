package com.arthur.labops;

import static com.arthur.labops.TestAuth.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The list page used to build its category filter from the rows it happened to
 * show, so a status filter or a page boundary made custom categories vanish.
 * The categories endpoint answers independently of both. Rows are rolled back
 * with the test transaction.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EquipmentCategoriesIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EquipmentRepository equipmentRepository;

    @Test
    void categoriesIgnoreListFiltersAndPages() throws Exception {
        String student = bearer(mockMvc, objectMapper, "student", "student123");
        String custom = "自制装置-" + System.nanoTime();
        Equipment homemade = new Equipment("CAT-" + System.nanoTime(), "自制测试装置", custom, "实验楼 T201");
        homemade.retire();
        equipmentRepository.saveAndFlush(homemade);
        // Two rows with the same category come back once.
        equipmentRepository.saveAndFlush(new Equipment("CAT2-" + System.nanoTime(), "第二台", custom, "实验楼 T202"));

        // The device is invisible to a maintenance filter and to a one-row page...
        Map<String, Object> filtered = json(mockMvc.perform(get("/api/equipment")
                        .param("status", "MAINTENANCE").param("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, student))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(), new TypeReference<>() {});
        assertThat(filtered.get("content").toString()).doesNotContain(custom);

        // ...but its category is still offered, and only once.
        List<String> categories = json(mockMvc.perform(get("/api/equipment/categories")
                        .header(HttpHeaders.AUTHORIZATION, student))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(), new TypeReference<>() {});
        assertThat(categories).containsOnlyOnce(custom);
    }

    @Test
    void categoriesRequireSignIn() throws Exception {
        mockMvc.perform(get("/api/equipment/categories")).andExpect(status().isUnauthorized());
    }

    private <T> T json(byte[] body, TypeReference<T> type) throws Exception {
        return objectMapper.readValue(body, type);
    }
}
