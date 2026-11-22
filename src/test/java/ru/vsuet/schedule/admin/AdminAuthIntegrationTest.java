package ru.vsuet.schedule.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import ru.vsuet.schedule.TestcontainersConfiguration;

/** With a token configured only its holder may call {@code /api/v1/admin/**}. */
@SpringBootTest(properties = {"vsuet.admin.token=s3cret-token", "vsuet.import.scheduler.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminAuthIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void missingHeaderIsUnauthorizedWithAChallenge() throws Exception {
        mvc.perform(get("/api/v1/admin/periods"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void wrongTokenWrongSchemeAndTruncatedOrExtendedTokensAreRejected() throws Exception {
        for (String header : new String[] {"Bearer wrong", "Bearer s3cret-toke", "Bearer s3cret-token-and-more",
                "Basic s3cret-token", "s3cret-token"}) {
            mvc.perform(get("/api/v1/admin/periods").header("Authorization", header))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void everyAdminEndpointIsGuardedNotJustTheFirst() throws Exception {
        mvc.perform(post("/api/v1/admin/imports")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/sources/1/import")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/sources")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/import-runs/1/diagnostics")).andExpect(status().isUnauthorized());
    }

    @Test
    void theRightTokenIsAccepted() throws Exception {
        mvc.perform(get("/api/v1/admin/periods").header("Authorization", "Bearer s3cret-token"))
                .andExpect(status().isOk());
    }

    @Test
    void thePublicApiNeedsNoToken() throws Exception {
        mvc.perform(get("/api/v1/faculties")).andExpect(status().isOk());
    }
}
