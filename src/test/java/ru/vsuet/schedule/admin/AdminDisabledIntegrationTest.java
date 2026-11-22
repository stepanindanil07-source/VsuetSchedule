package ru.vsuet.schedule.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import ru.vsuet.schedule.TestcontainersConfiguration;

/** Without a configured token the administrative API does not exist. */
@SpringBootTest(properties = {"vsuet.admin.token=", "vsuet.import.scheduler.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminDisabledIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void adminApiIsSwitchedOffAndAnswersNotFoundEvenWithAHeader() throws Exception {
        mvc.perform(get("/api/v1/admin/periods").header("Authorization", "Bearer anything"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(post("/api/v1/admin/imports")).andExpect(status().isNotFound());
    }

    @Test
    void thePublicApiIsUnaffected() throws Exception {
        mvc.perform(get("/api/v1/faculties")).andExpect(status().isOk());
    }
}
