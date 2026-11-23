import type { LessonType } from '../../model/types';
import { collapseSpaces, LL } from '../../util/text';

/**
 * Четыре ячейки одного занятия заочного расписания в его части:
 *   строка предмета   «лаб. Технология бродильных производств- 2 п/г»   аудитория
 *   строка преподав.  «Дядищева С.С.»                                    (вторая аудитория)
 * Ячейка предмета из нескольких строк («лаб. Основы диетологии\nПищевые добавки») означает несколько параллельных занятий группы,
 * записанных вместе: они остаются одним занятием, предметы через « / », как и два преподавателя и две аудитории.
 * Перенос Java-класса ZaoLessonText. Без состояния.
 */

const TYPE_MARKER = new RegExp(`^([${LL}]{1,6})\\.\\s*(.*)$`);
const SUBGROUP = /\s*[-–]?\s*(\d+)\s*п\/г\s*$/;
const SEVERAL_SPACES = /\s{2,}|\n/;

export interface ZaoLesson {
  kind: 'lesson';
  type: LessonType;
  subject: string;
  subgroup: number | null;
  teacher: string | null;
  room: string | null;
  warnings: Array<{ code: string; message: string }>;
}

export interface ZaoRejected {
  kind: 'rejected';
  code: string;
  message: string;
}

export function parseZaoLesson(subjectCell: string, teacherCell: string, roomOnSubjectRow: string, roomOnTeacherRow: string): ZaoLesson | ZaoRejected {
  const lines = subjectCell
    .split(/\r\n|\r|\n/)
    .map((l) => l.trim())
    .filter((l) => l !== '');
  if (lines.length === 0) return { kind: 'rejected', code: 'LESSON_WITHOUT_SUBJECT', message: 'Teacher or room without a subject' };
  const warnings: ZaoLesson['warnings'] = [];

  let type: LessonType = 'OTHER';
  const parts = [...lines];
  const marker = TYPE_MARKER.exec(parts[0]);
  if (marker) {
    const code = marker[1];
    parts[0] = marker[2];
    switch (code) {
      case 'л':
        type = 'LECTURE';
        break;
      case 'пр':
        type = 'PRACTICE';
        break;
      case 'лаб':
        type = 'LAB';
        break;
      case 'экз':
        type = 'EXAM';
        break;
      case 'зач':
        type = 'CREDIT';
        break;
      default:
        warnings.push({ code: 'UNKNOWN_LESSON_MARKER', message: `Unknown lesson type marker '${code}.'; lesson type set to OTHER` });
    }
  }

  const subgroups: number[] = [];
  const subjects: string[] = [];
  for (let part of parts) {
    const sub = SUBGROUP.exec(part);
    if (sub) {
      const n = Number(sub[1]);
      if (!subgroups.includes(n)) subgroups.push(n);
      part = part.substring(0, sub.index);
    }
    part = collapseSpaces(part).trim();
    if (part !== '') subjects.push(part);
  }
  if (subjects.length === 0) return { kind: 'rejected', code: 'EMPTY_SUBJECT', message: `Lesson marker without a subject: ${subjectCell.trim()}` };

  let subgroup: number | null = null;
  if (subgroups.length === 1 && subgroups[0] >= 1) {
    subgroup = subgroups[0];
  } else if (subgroups.length > 1) {
    warnings.push({ code: 'SUBGROUP_AMBIGUOUS', message: `The cell names several subgroups [${subgroups.join(', ')}]; the lesson is shown to the whole group` });
  }

  return {
    kind: 'lesson',
    type,
    subject: subjects.join(' / '),
    subgroup,
    teacher: joined(splitTeachers(teacherCell)),
    room: joined(distinct([roomOnSubjectRow, roomOnTeacherRow])),
    warnings,
  };
}

/** Преподаватели разделены двумя и более пробелами: «Карлов Д.А.  Выродов Д.М.». */
function splitTeachers(text: string): string[] {
  return text
    .trim()
    .split(SEVERAL_SPACES)
    .map((piece) => collapseSpaces(piece).trim())
    .filter((p) => p !== '');
}

function distinct(rooms: string[]): string[] {
  const result: string[] = [];
  for (const room of rooms) {
    const clean = collapseSpaces(room.trim());
    if (clean !== '' && !result.includes(clean)) result.push(clean);
  }
  return result;
}

function joined(items: string[]): string | null {
  return items.length === 0 ? null : items.join(', ');
}
