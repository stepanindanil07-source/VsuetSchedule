/**
 * Год набора, записанный в коде группы: цифры после дефиса начинаются с года. Два написания в одних и тех же файлах:
 * «У-261», «Хм-254», «ПКД-218» (две цифры года, затем номер группы) и «ЭБ-61», «УБ-51», «ЭБ-21» (последняя цифра года, затем номер).
 *
 * Проверено на всех группах, чей курс названы листами (245 из 245 совпали), но это соглашение об именах, а не правило формата файла:
 * курс, выведенный отсюда, принимается, только если заголовок листа сам называет этот курс.
 */
const DIGITS_AFTER_DASH = /-(\d+)/;

/** @param academicYearFrom год начала учебного года (2026 для 2026–2027) */
export function courseOf(groupCode: string, academicYearFrom: number): number | null {
  const admitted = admissionYear(groupCode, academicYearFrom);
  return admitted === null ? null : academicYearFrom - admitted + 1;
}

export function admissionYear(groupCode: string, academicYearFrom: number): number | null {
  const m = DIGITS_AFTER_DASH.exec(groupCode);
  if (!m) return null;
  const digits = m[1];
  if (digits.length >= 3) return 2000 + Number(digits.slice(0, 2));
  if (digits.length === 2) {
    // одна цифра года: самый поздний год не позже учебного, оканчивающийся на эту цифру
    const lastDigit = Number(digits[0]);
    return academicYearFrom - (((academicYearFrom - lastDigit) % 10) + 10) % 10;
  }
  return null;
}
