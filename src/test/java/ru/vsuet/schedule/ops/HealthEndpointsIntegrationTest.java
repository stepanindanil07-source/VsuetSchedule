package ru.vsuet.schedule.ops;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import ru.vsuet.schedule.TestcontainersConfiguration;
import ru.vsuet.schedule.academic.domain.OrganizationUnit;
import ru.vsuet.schedule.academic.infrastructure.OrganizationUnitRepository;
import ru.vsuet.schedule.importer.domain.ImportRun;
import ru.vsuet.schedule.importer.domain.ScheduleSource;
import ru.vsuet.schedule.importer.infrastructure.ImportRunRepository;
import ru.vsuet.schedule.importer.infrastructure.ScheduleSourceRepository;

/** What a load balancer, a container runtime and an uptime monitor see, and that nothing else is exposed. */
@SpringBootTest(properties = {"vsuet.import.scheduler.enabled=false", "vsuet.import.health-max-age=6h"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HealthEndpointsIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationUnitRepository units;
    @Autowired ScheduleSourceRepository sources;
    @Autowired ImportRunRepository runs;

    private ScheduleSource source;

    @BeforeEach
    void oneSourceNeverImported() {
        jdbc.execute("TRUNCATE schedule_entry, import_run, study_group, schedule_source, academic_period, "
                + "organization_unit RESTART IDENTITY CASCADE");
        OrganizationUnit unit = units.save(new OrganizationUnit("uits", "ФУИТС"));
        source = sources.save(new ScheduleSource(unit.getId(), "k", "http://example.test/uits.xlsx"));
    }

    @Test
    void livenessAndReadinessAreUpWhenTheDatabaseIsReachable() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void importsGroupIsDownWithoutADetailLeakWhileASourceWasNeverImported() throws Exception {
        mvc.perform(get("/actuator/health/imports"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("example.test"))));
    }

    @Test
    void importsGroupGoesUpAfterAGoodImportAndLivenessNeverDependsOnIt() throws Exception {
        ImportRun run = ImportRun.start(source.getId(), "sha", Instant.now());
        run.unchanged(Instant.now());
        runs.save(run);

        mvc.perform(get("/actuator/health/imports")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void aStaleImportMakesOnlyTheImportsGroupDown() throws Exception {
        ImportRun run = ImportRun.start(source.getId(), "sha", Instant.now().minusSeconds(8 * 3600));
        run.unchanged(Instant.now().minusSeconds(8 * 3600));
        runs.save(run);

        mvc.perform(get("/actuator/health/imports")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void noOtherActuatorEndpointIsExposed() throws Exception {
        for (String path : new String[] {"/actuator", "/actuator/env", "/actuator/beans", "/actuator/info", "/actuator/metrics",
                "/actuator/heapdump", "/actuator/configprops", "/actuator/mappings"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }
}
