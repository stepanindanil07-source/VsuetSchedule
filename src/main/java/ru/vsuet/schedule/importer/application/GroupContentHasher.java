package ru.vsuet.schedule.importer.application;

import java.util.List;
import java.util.stream.Collectors;
import ru.vsuet.schedule.common.Hashes;
import ru.vsuet.schedule.importer.parser.ParsedEntry;

/**
 * Fingerprint of everything a client can see for one group in one period. Source coordinates and raw text are
 * left out on purpose: moving a lesson to another cell without changing it must not bump the schedule version.
 */
final class GroupContentHasher {

    private static final char SEP = '\u001f';

    private GroupContentHasher() {
    }

    static String hash(Long periodId, List<ParsedEntry> entries) {
        String body = entries.stream()
                .map(GroupContentHasher::line)
                .sorted()
                .collect(Collectors.joining("\n"));
        return Hashes.sha256Hex(periodId + "\n" + body);
    }

    /**
     * The date is appended only to dated lessons: the line of a weekly lesson must stay byte for byte what it was before
     * dates existed, otherwise deploying the correspondence parser would raise the schedule version of every group and
     * make every installed app download everything once for nothing.
     */
    private static String line(ParsedEntry e) {
        String line = String.join(String.valueOf(SEP),
                String.valueOf(e.dayOfWeek().getValue()), e.startTime().toString(), e.endTime().toString(),
                e.weekType().name(), String.valueOf(e.subgroup()), e.subject(), e.lessonType().name(),
                String.valueOf(e.teacherText()), String.valueOf(e.roomText()));
        return e.date() == null ? line : line + SEP + e.date();
    }
}
