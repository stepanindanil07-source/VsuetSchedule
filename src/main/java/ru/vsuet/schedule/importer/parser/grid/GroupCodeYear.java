package ru.vsuet.schedule.importer.parser.grid;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The year a group was admitted, written in its code: the digits after the dash start with the year. Two spellings
 * are in use in the same files: "У-261", "Хм-254", "ПКД-218" (two digits of the year, then the group number) and
 * "ЭБ-61", "УБ-51", "ЭБ-21" (the last digit of the year, then the group number).
 *
 * <p>Checked against every group whose course the sheets state (245 of 245 agree in the autumn 2026 files), but it is a
 * naming convention, not a rule of the file format: a course derived from it is only accepted when the sheet's own title
 * names that course (see {@link GridSheetParser}). Stateless.
 */
final class GroupCodeYear {

    private static final Pattern DIGITS_AFTER_DASH = Pattern.compile("-(\\d+)");

    private GroupCodeYear() {
    }

    /**
     * @param academicYearFrom the calendar year in which the academic year of the schedule started (2026 for 2026-2027)
     * @return the course (1 = admitted in {@code academicYearFrom}), or {@code null} if the code has no year
     */
    static Integer courseOf(String groupCode, int academicYearFrom) {
        Integer admitted = admissionYear(groupCode, academicYearFrom);
        return admitted == null ? null : academicYearFrom - admitted + 1;
    }

    static Integer admissionYear(String groupCode, int academicYearFrom) {
        Matcher m = DIGITS_AFTER_DASH.matcher(groupCode);
        if (!m.find()) {
            return null;
        }
        String digits = m.group(1);
        if (digits.length() >= 3) {
            return 2000 + Integer.parseInt(digits.substring(0, 2));
        }
        if (digits.length() == 2) {
            // one digit of the year: the latest year not after the academic year that ends in that digit
            int lastDigit = digits.charAt(0) - '0';
            return academicYearFrom - Math.floorMod(academicYearFrom - lastDigit, 10);
        }
        return null;
    }
}
