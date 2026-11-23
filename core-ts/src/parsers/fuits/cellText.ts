import { collapseSpaces, isBlank, L, LL, LU, lines } from '../../util/text';
import type { CellContent, LessonTextParser, Rejected } from '../grid/format';

/**
 * Разбирает текст одной ячейки занятия ФУИТС на тип, предмет, преподавателей и аудитории.
 *
 * Запись в файлах:
 *   пр.История России              строка 1: «<признак типа>.<предмет>»
 *   Гребенщиков А.Е.   46          строка 2: преподаватель, 2+ пробела, аудитория (может повторяться для нескольких)
 * Строки после первой это «детали». Что нельзя классифицировать, сохраняется, а не теряется: преподаватели и аудитории остаются
 * свободным текстом (через «, »), неизвестный признак даёт предупреждение. Не знает ничего о геометрии Excel.
 * Перенос Java-класса CellTextParser.
 */

const TYPE_MARKER = new RegExp(`^([${LL}]{1,6})\\.(?=\\S)(.*)$`);
const BARE_MARKER = new RegExp(`^[${LL}]{1,6}\\.$`);
const DETAIL_SEPARATOR = /\s{2,}/;
/**
 * «Фамилия И.О.» и, возможно, остаток токена (аудитория, написанная через один пробел). Терпимо к вариантам из реальных файлов:
 * «Е.В,» / «Л.А» / «Д.В..» после инициалов и «Чекмарева_Пол» как фамилия. Текст не «исправляется», только узнаётся.
 */
const TEACHER_PREFIX = new RegExp(`^([${LU}][${L}_]+(?:-[${LU}][${L}_]+)?\\s+[${LU}]\\.\\s?[${LU}][.,]*)(?:\\s+(.+))?$`);

export class FuitsCellTextParser implements LessonTextParser {
  parse(text: string): CellContent | Rejected {
    if (isBlank(text)) return { kind: 'rejected', code: 'EMPTY_TEXT', message: 'Cell text is empty' };
    if (text.trim() === '0') return { kind: 'rejected', code: 'ZERO_PLACEHOLDER', message: 'Cell contains the placeholder 0' };

    const parts = text
      .split(/\r\n|\r|\n/)
      .map((l) => l.trim())
      .filter((l) => l !== '');
    const warnings: CellContent['warnings'] = [];

    let lessonType: CellContent['lessonType'] = 'OTHER';
    let subject = parts[0];
    const marker = TYPE_MARKER.exec(subject);
    if (marker) {
      const code = marker[1];
      subject = marker[2];
      switch (code) {
        case 'пр':
          lessonType = 'PRACTICE';
          break;
        case 'л':
          lessonType = 'LECTURE';
          break;
        case 'лаб':
          lessonType = 'LAB';
          break;
        default:
          warnings.push({ code: 'UNKNOWN_LESSON_MARKER', message: `Unknown lesson type marker '${code}.'; lesson type set to OTHER` });
      }
    } else if (BARE_MARKER.test(subject)) {
      return { kind: 'rejected', code: 'EMPTY_SUBJECT', message: `Lesson marker without a subject: ${subject}` };
    }
    subject = collapseSpaces(subject).trim();
    if (subject === '') return { kind: 'rejected', code: 'EMPTY_SUBJECT', message: 'Lesson marker without a subject' };

    const teachers: string[] = [];
    const rooms: string[] = [];
    for (const detail of parts.slice(1)) this.parseDetailLine(detail, teachers, rooms, warnings);

    return {
      kind: 'content',
      lessonType,
      subject,
      teacher: teachers.length === 0 ? null : teachers.join(', '),
      room: rooms.length === 0 ? null : rooms.join(', '),
      subgroup: null,
      warnings,
    };
  }

  private parseDetailLine(line: string, teachers: string[], rooms: string[], warnings: CellContent['warnings']): void {
    for (const raw of line.split(DETAIL_SEPARATOR)) {
      const token = raw.trim();
      if (token === '') continue;
      const teacher = TEACHER_PREFIX.exec(token);
      if (teacher) {
        // запятая в конце это артефакт разделителя ячейки, а не часть имени
        teachers.push(collapseSpaces(teacher[1]).replace(/,+$/, ''));
        if (teacher[2] !== undefined) rooms.push(collapseSpaces(teacher[2]));
      } else if (!/\s/.test(token)) {
        rooms.push(token);
      } else {
        // Несколько слов, но не «Фамилия И.О.»: скорее искажённое имя, чем аудитория.
        teachers.push(collapseSpaces(token));
        warnings.push({ code: 'UNRECOGNIZED_DETAIL', message: `Cannot tell whether '${token}' is a teacher or a room; kept as teacher` });
      }
    }
  }
}
