package ru.vsuet.schedule.importer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.vsuet.schedule.TestcontainersConfiguration;
import ru.vsuet.schedule.academic.domain.AcademicTerm;
import ru.vsuet.schedule.academic.domain.AcademicPeriod;
import ru.vsuet.schedule.academic.domain.OrganizationUnit;
import ru.vsuet.schedule.academic.infrastructure.AcademicPeriodRepository;
import ru.vsuet.schedule.academic.infrastructure.OrganizationUnitRepository;
import ru.vsuet.schedule.common.WeekType;
import ru.vsuet.schedule.importer.domain.ImportRun;
import ru.vsuet.schedule.importer.domain.ImportStatus;
import ru.vsuet.schedule.importer.domain.ScheduleSource;
import ru.vsuet.schedule.importer.infrastructure.ImportRunRepository;
import ru.vsuet.schedule.importer.infrastructure.ScheduleSourceRepository;
import ru.vsuet.schedule.importer.parser.fuits.FuitsExcelParser;
import ru.vsuet.schedule.importer.parser.fuits.WorkbookFixture;
import ru.vsuet.schedule.schedule.infrastructure.ScheduleEntryRepository;

/** Download + import end to end: a local HTTP server stands in for vsuet.ru. */
@SpringBootTest(properties = {
        "vsuet.import.download.max-attempts=2",
        "vsuet.import.download.retry-delay=10ms",
        "vsuet.import.download.connect-timeout=2s",
        "vsuet.import.download.read-timeout=2s",
        "vsuet.import.scheduler.enabled=false"})
@Import(TestcontainersConfiguration.class)
class SourceImportIntegrationTest {

    @Autowired SourceImporter sourceImporter;
    @Autowired ScheduledImportJob job;
    @Autowired OrganizationUnitRepository units;
    @Autowired AcademicPeriodRepository periods;
    @Autowired ScheduleSourceRepository sources;
    @Autowired ScheduleEntryRepository entries;
    @Autowired ImportRunRepository runs;
    @Autowired JdbcTemplate jdbc;

    private HttpServer server;
    private ExecutorService executor;
    private final AtomicInteger okStatus = new AtomicInteger(200);
    private OrganizationUnit unit;

    @BeforeEach
    void seedAndStartServer() throws IOException {
        jdbc.execute("TRUNCATE schedule_entry, import_run, study_group, schedule_source, academic_period, "
                + "organization_unit RESTART IDENTITY CASCADE");
        unit = units.save(new OrganizationUnit("uits", "ФУИТС"));
        periods.save(new AcademicPeriod(2026, AcademicTerm.AUTUMN, LocalDate.of(2026, 9, 1),
                LocalDate.of(2027, 1, 31), LocalDate.of(2026, 8, 31), WeekType.NUMERATOR));

        byte[] scheduleFile = scheduleFile();
        okStatus.set(200);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/ok.xlsx", exchange -> {
            int status = okStatus.get();
            if (status == 200) {
                exchange.sendResponseHeaders(200, scheduleFile.length);
                exchange.getResponseBody().write(scheduleFile);
            } else {
                exchange.sendResponseHeaders(status, -1);
            }
            exchange.close();
        });
        server.createContext("/down.xlsx", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.createContext("/garbage.xlsx", exchange -> {
            byte[] junk = "not an excel file".getBytes();
            exchange.sendResponseHeaders(200, junk.length);
            exchange.getResponseBody().write(junk);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
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

    @Test
    void downloadsTheFileAndImportsIt() {
        ScheduleSource source = source("/ok.xlsx");

        ImportResult result = sourceImporter.importSource(source.getId());

        assertThat(result.status()).isEqualTo(ImportStatus.SUCCESS);
        assertThat(entries.count()).isEqualTo(4);
        assertThat(sources.findById(source.getId()).orElseThrow().getLastCheckedAt()).isNotNull();
    }

    @Test
    void unavailableSiteIsRecordedAsFailedRunAndKeepsTheCurrentSchedule() {
        ScheduleSource source = source("/ok.xlsx");
        sourceImporter.importSource(source.getId());
        okStatus.set(503);

        ImportResult result = sourceImporter.importSource(source.getId());

        assertThat(result.status()).isEqualTo(ImportStatus.FAILED);
        assertThat(result.message()).startsWith("Download failed").contains("after 2 attempts");
        assertThat(entries.count()).isEqualTo(4);
        ImportRun failed = runs.findById(result.runId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ImportStatus.FAILED);
        assertThat(failed.getSha256()).isNull();
    }

    @Test
    void unknownAndDisabledSourcesAreRefused() {
        ScheduleSource disabled = source("/ok.xlsx");
        disabled.disable();
        sources.save(disabled);

        assertThatThrownBy(() -> sourceImporter.importSource(9999)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sourceImporter.importSource(disabled.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("disabled");
        assertThat(runs.count()).isZero();
    }

    @Test
    void jobImportsEveryEnabledSourceEvenWhenSomeFail() {
        ScheduleSource ok = source("/ok.xlsx");
        ScheduleSource down = source("/down.xlsx");
        ScheduleSource garbage = source("/garbage.xlsx");
        ScheduleSource disabled = source("/ok.xlsx");
        disabled.disable();
        sources.save(disabled);

        List<ImportResult> results = job.importAllEnabledSources();

        assertThat(results).extracting(ImportResult::status)
                .containsExactly(ImportStatus.SUCCESS, ImportStatus.FAILED, ImportStatus.FAILED);
        assertThat(entries.count()).isEqualTo(4);
        assertThat(runs.findAll()).extracting(ImportRun::getSourceId)
                .containsExactlyInAnyOrder(ok.getId(), down.getId(), garbage.getId())
                .doesNotContain(disabled.getId());
        assertThat(runs.findAll().stream().filter(r -> r.getSourceId().equals(garbage.getId())).findFirst().orElseThrow()
                .getErrorMessage()).contains("Cannot read");
    }
}
