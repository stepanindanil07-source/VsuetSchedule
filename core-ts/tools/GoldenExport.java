import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import ru.vsuet.schedule.importer.parser.ParseContext;
import ru.vsuet.schedule.importer.parser.ParsedEntry;
import ru.vsuet.schedule.importer.parser.ParsedGroup;
import ru.vsuet.schedule.importer.parser.ParsedSchedule;
import ru.vsuet.schedule.importer.parser.ScheduleParser;
import ru.vsuet.schedule.importer.parser.fuits.FuitsExcelParser;
import ru.vsuet.schedule.importer.parser.spo.SpoExcelParser;
import ru.vsuet.schedule.importer.parser.zao.ZaoExcelParser;

/**
 * Эталонная выгрузка: разбирает реальные файлы Java-парсерами backend и пишет результат в JSON в том виде, в каком
 * его должны выдавать TypeScript-парсеры. Запуск из корня проекта (после ./mvnw compile):
 *   java -cp "target/classes:$(cat classpath.txt)" core-ts/tools/GoldenExport.java
 * Контекст разбора такой же, как у настоящего импорта привязанного источника: семестр 2026-2027, начало 2026-09-01.
 */
public class GoldenExport {

    public static void main(String[] args) throws Exception {
        Path files = Path.of("core-ts/fixtures/files");
        Path out = Path.of("core-ts/fixtures/golden");
        Files.createDirectories(out);
        ParseContext context = new ParseContext(LocalDate.of(2026, 9, 1), 2026);
        ObjectMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

        Map<String, ScheduleParser> parsers = new LinkedHashMap<>();
        for (String f : List.of("uits.xlsx", "itf.xlsx", "eht.xlsx", "tf.xlsx", "eiu.xlsx")) {
            parsers.put(f, new FuitsExcelParser());
        }
        parsers.put("fspo-1_kurs.xls", new SpoExcelParser());
        parsers.put("fspo-2-3-4_kurs.xls", new SpoExcelParser());
        parsers.put("zao-5_kurs.xlsx", new ZaoExcelParser());

        for (var e : parsers.entrySet()) {
            ParsedSchedule s;
            try (InputStream in = Files.newInputStream(files.resolve(e.getKey()))) {
                s = e.getValue().parse(in, context);
            }
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("file", e.getKey());
            doc.put("parserKey", s.parserKey());
            doc.put("parserVersion", s.diagnostics().parserVersion());
            doc.put("period", s.period() == null ? null : Map.of(
                    "yearFrom", s.period().yearFrom(), "yearTo", s.period().yearTo(), "term", s.period().term().name()));
            List<Object> groups = new ArrayList<>();
            for (ParsedGroup g : s.groups()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", g.code());
                m.put("course", g.course());
                groups.add(m);
            }
            doc.put("groups", groups);
            List<Object> entries = new ArrayList<>();
            for (ParsedEntry p : s.entries()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("groupCode", p.groupCode());
                m.put("dayOfWeek", p.dayOfWeek().getValue());
                m.put("date", p.date() == null ? null : p.date().format(DateTimeFormatter.ISO_LOCAL_DATE));
                m.put("startTime", p.startTime().format(DateTimeFormatter.ofPattern("HH:mm")));
                m.put("endTime", p.endTime().format(DateTimeFormatter.ofPattern("HH:mm")));
                m.put("weekType", p.weekType().name());
                m.put("subgroup", p.subgroup());
                m.put("subject", p.subject());
                m.put("lessonType", p.lessonType().name());
                m.put("teacher", p.teacherText());
                m.put("room", p.roomText());
                m.put("rawText", p.rawText());
                m.put("sheet", p.location().container());
                m.put("range", p.location().position());
                entries.add(m);
            }
            doc.put("entries", entries);
            List<Object> issues = new ArrayList<>();
            s.diagnostics().issues().forEach(i -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("severity", i.severity().name());
                m.put("code", i.code());
                m.put("message", i.message());
                m.put("sheet", i.location() == null ? null : i.location().container());
                m.put("range", i.location() == null ? null : i.location().position());
                issues.add(m);
            });
            Map<String, Object> diag = new LinkedHashMap<>();
            diag.put("totalFound", s.diagnostics().totalFound());
            diag.put("recognized", s.diagnostics().recognized());
            diag.put("skipped", s.diagnostics().skipped());
            diag.put("issues", issues);
            doc.put("diagnostics", diag);
            String name = e.getKey().replaceAll("\\.[a-z]+$", "") + ".json";
            Files.writeString(out.resolve(name), mapper.writeValueAsString(doc));
            System.out.printf("%-22s -> %-24s groups=%3d entries=%4d issues=%d%n", e.getKey(), name, groups.size(), entries.size(), issues.size());
        }
    }
}
