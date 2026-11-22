package ru.vsuet.schedule.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.vsuet.schedule.TestcontainersConfiguration;
import ru.vsuet.schedule.academic.domain.AcademicPeriod;
import ru.vsuet.schedule.academic.domain.AcademicTerm;
import ru.vsuet.schedule.academic.domain.OrganizationUnit;
import ru.vsuet.schedule.academic.infrastructure.AcademicPeriodRepository;
import ru.vsuet.schedule.academic.infrastructure.OrganizationUnitRepository;
import ru.vsuet.schedule.common.WeekType;
import ru.vsuet.schedule.importer.domain.ScheduleSource;
import ru.vsuet.schedule.importer.infrastructure.ScheduleSourceRepository;
import ru.vsuet.schedule.importer.parser.fuits.FuitsExcelParser;
import ru.vsuet.schedule.importer.parser.fuits.WorkbookFixture;
import ru.vsuet.schedule.schedule.infrastructure.ScheduleEntryRepository;

/** Operating the schedule files through the admin API; a local HTTP server stands in for vsuet.ru. */
@SpringBootTest(properties = {
        "vsuet.admin.token=test-token",
        "vsuet.import.scheduler.enabled=false",
        "vsuet.import.download.max-attempts=2",
        "vsuet.import.download.retry-delay=10ms",
        "vsuet.import.download.connect-timeout=2s",
        "vsuet.import.download.read-timeout=2s"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminSourcesIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationUnitRepository units;
    @Autowired AcademicPeriodRepository periods;
    @Autowired ScheduleSourceRepository sources;
    @Autowired ScheduleEntryRepository entries;

    private HttpServer server;
    private ExecutorService executor;
    private OrganizationUnit unit;
    private AcademicPeriod autumn2026;

    @BeforeEach
    void seedAndStartServer() throws IOException {
        jdbc.execute("TRUNCATE schedule_entry, import_run, study_group, schedule_source, academic_period, "
                + "organization_unit RESTART IDENTITY CASCADE");
        unit = units.save(new OrganizationUnit("uits", "ФУИТС"));
        autumn2026 = periods.save(new AcademicPeriod(2026, AcademicTerm.AUTUMN, LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 1, 31), LocalDate.of(2026, 8, 31), WeekType.NUMERATOR));

        byte[] file = scheduleFile();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/ok.xlsx", exchange -> {
            exchange.sendResponseHeaders(200, file.length);
            exchange.getResponseBody().write(file);
            exchange.close();
        });
        server.createContext("/down.xlsx", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer test-token");
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private ScheduleSource source(String path) {
        return sources.save(new ScheduleSource(unit.getId(), FuitsExcelParser.KEY, url(path)));
    }

    private static byte[] scheduleFile() {
        WorkbookFixture f = WorkbookFixture.xlsx()
                .sheet("1 курс", "осеннего семестра 2026-2027 учебного года", "Г-1", 1, "Г-2", 1)
                .day("ПОНЕДЕЛЬНИК").slot("08.00-09.35", 2).slot("09.45-11.20", 2)
                .put("C3", "пр.Физика\nИванов И.И.   12").put("C5", "л.Химия\nПетров П.П.   13")
                .put("D3", "л.Химия\nПетров П.П.   13").put("D5", "пр.Физика\nИванов И.И.   12");
        try (InputStream in = f.stream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ResultActions importOne(long id) throws Exception {
        return mvc.perform(auth(post("/api/v1/admin/sources/{id}/import", id)));
    }

    // ---- listing -----------------------------------------------------------------------------------------

    @Test
    void listsFilesWithTheirUnitPinAndLatestImport() throws Exception {
        ScheduleSource ok = source("/ok.xlsx");
        ScheduleSource pinned = new ScheduleSource(unit.getId(), "spo-xls", url("/x.xls"));
        pinned.pinToPeriod(autumn2026.getId());
        pinned.setDefaultCourse(5);
        pinned = sources.save(pinned);
        importOne(ok.getId()).andExpect(status().isOk());

        mvc.perform(auth(get("/api/v1/admin/sources")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(ok.getId()))
                .andExpect(jsonPath("$[0].unitCode").value("uits"))
                .andExpect(jsonPath("$[0].parserKey").value("vsuet-grid-xlsx"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].pinnedPeriodId").value(nullValue()))
                .andExpect(jsonPath("$[0].lastImport.status").value("SUCCESS"))
                .andExpect(jsonPath("$[0].lastImport.recognized").value(4))
                .andExpect(jsonPath("$[1].pinnedPeriodId").value(autumn2026.getId()))
                .andExpect(jsonPath("$[1].defaultCourse").value(5))
                .andExpect(jsonPath("$[1].lastImport").value(nullValue()));
    }

    // ---- pin / enable ------------------------------------------------------------------------------------

    @Test
    void pinningToAPeriodAndRemovingThePin() throws Exception {
        ScheduleSource s = source("/ok.xlsx");

        mvc.perform(auth(put("/api/v1/admin/sources/{id}/period", s.getId())).contentType(APPLICATION_JSON)
                        .content("{\"periodId\":" + autumn2026.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinnedPeriodId").value(autumn2026.getId()));
        assertThat(sources.findById(s.getId()).orElseThrow().getAcademicPeriodId()).isEqualTo(autumn2026.getId());

        mvc.perform(auth(put("/api/v1/admin/sources/{id}/period", s.getId())).contentType(APPLICATION_JSON)
                        .content("{\"periodId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinnedPeriodId").value(nullValue()));
    }

    @Test
    void pinningToAnUnknownPeriodOrSourceIsRejected() throws Exception {
        ScheduleSource s = source("/ok.xlsx");

        mvc.perform(auth(put("/api/v1/admin/sources/{id}/period", s.getId())).contentType(APPLICATION_JSON)
                        .content("{\"periodId\":9999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("9999")));
        mvc.perform(auth(put("/api/v1/admin/sources/{id}/period", 9999)).contentType(APPLICATION_JSON)
                        .content("{\"periodId\":null}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void afterRepinningTheSameFileIsImportedAgainIntoTheNewPeriodInsteadOfBeingSkipped() throws Exception {
        ScheduleSource s = source("/ok.xlsx");
        importOne(s.getId()).andExpect(jsonPath("$.status").value("SUCCESS"));
        importOne(s.getId()).andExpect(jsonPath("$.status").value("SKIPPED_UNCHANGED"));

        mvc.perform(auth(put("/api/v1/admin/sources/{id}/period", s.getId())).contentType(APPLICATION_JSON)
                .content("{\"periodId\":" + autumn2026.getId() + "}")).andExpect(status().isOk());

        importOne(s.getId()).andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void aSourceCanBeSwitchedOffAndOn() throws Exception {
        ScheduleSource s = source("/ok.xlsx");

        mvc.perform(auth(put("/api/v1/admin/sources/{id}/enabled", s.getId())).contentType(APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        importOne(s.getId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("disabled")));

        mvc.perform(auth(put("/api/v1/admin/sources/{id}/enabled", s.getId())).contentType(APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(jsonPath("$.enabled").value(true));
        importOne(s.getId()).andExpect(status().isOk());
        mvc.perform(auth(put("/api/v1/admin/sources/{id}/enabled", s.getId())).contentType(APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
    }

    // ---- importing ---------------------------------------------------------------------------------------

    @Test
    void importsOneFileNowAndThenSkipsTheUnchangedOne() throws Exception {
        ScheduleSource s = source("/ok.xlsx");

        importOne(s.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.groupsCreated").value(2))
                .andExpect(jsonPath("$.entriesWritten").value(4));
        assertThat(entries.count()).isEqualTo(4);

        importOne(s.getId()).andExpect(jsonPath("$.status").value("SKIPPED_UNCHANGED"));
    }

    @Test
    void aFileThatCannotBeDownloadedIsAFailedImportNotAServerError() throws Exception {
        ScheduleSource s = source("/down.xlsx");

        importOne(s.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("Download failed")));
        assertThat(entries.count()).isZero();
    }

    @Test
    void importingAnUnknownSourceIsNotFound() throws Exception {
        importOne(9999).andExpect(status().isNotFound());
    }

    @Test
    void importsEveryEnabledFileInOneCallAndSkipsDisabledOnes() throws Exception {
        source("/ok.xlsx");
        ScheduleSource off = source("/ok.xlsx");
        off.disable();
        sources.save(off);

        mvc.perform(auth(post("/api/v1/admin/imports")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("SUCCESS"));
    }

    // ---- history -----------------------------------------------------------------------------------------

    @Test
    void historyIsNewestFirstAndLimited() throws Exception {
        ScheduleSource s = source("/ok.xlsx");
        importOne(s.getId());
        importOne(s.getId());
        importOne(s.getId());

        mvc.perform(auth(get("/api/v1/admin/sources/{id}/runs", s.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].status").value("SKIPPED_UNCHANGED"))
                .andExpect(jsonPath("$[2].status").value("SUCCESS"))
                .andExpect(jsonPath("$[2].recognized").value(4));
        mvc.perform(auth(get("/api/v1/admin/sources/{id}/runs", s.getId())).param("limit", "1"))
                .andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(auth(get("/api/v1/admin/sources/{id}/runs", s.getId())).param("limit", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/v1/admin/sources/{id}/runs", 9999))).andExpect(status().isNotFound());
    }

    @Test
    void diagnosticsOfARunAreTheStoredJsonDocument() throws Exception {
        ScheduleSource s = source("/ok.xlsx");
        long runId = ((Number) com.jayway.jsonpath.JsonPath.read(
                importOne(s.getId()).andReturn().getResponse().getContentAsString(), "$.runId")).longValue();

        mvc.perform(auth(get("/api/v1/admin/import-runs/{id}/diagnostics", runId)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.recognized").value(4))
                .andExpect(jsonPath("$.issues").isArray());
        mvc.perform(auth(get("/api/v1/admin/import-runs/{id}/diagnostics", 9999))).andExpect(status().isNotFound());
    }
}
