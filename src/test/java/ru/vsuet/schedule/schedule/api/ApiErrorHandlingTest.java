package ru.vsuet.schedule.schedule.api;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.vsuet.schedule.schedule.application.ScheduleQueryService;

/** Web layer only (no database): every error leaves the API as problem+json and never leaks internals. */
@WebMvcTest(controllers = ScheduleController.class)
class ApiErrorHandlingTest {

    @Autowired MockMvc mvc;
    @MockitoBean ScheduleQueryService service;

    @Test
    void unexpectedFailureIsAGenericProblemDetailWithoutInternals() throws Exception {
        when(service.groupVersion(anyLong())).thenThrow(new IllegalStateException("secret table schedule_entry exploded"));

        mvc.perform(get("/api/v1/groups/1/schedule/version"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.title").value("Internal error"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    void unknownUrlAndWrongMethodAreProblemDetailsToo() throws Exception {
        mvc.perform(get("/api/v1/nothing-here"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(post("/api/v1/groups/1/schedule"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void nonNumericGroupIdIs400() throws Exception {
        mvc.perform(get("/api/v1/groups/abc/schedule"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }
}
