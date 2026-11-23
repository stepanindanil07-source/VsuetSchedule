import type { LessonType } from '../../model/types';
import { collapseSpaces, isBlank, L, LL, LU } from '../../util/text';
import type { CellContent, LessonTextParser, Rejected } from '../grid/format';

/**
 * Разбирает свободный текст ячейки занятия СПО, например
 *   ХИМИЯ     (лаб.)   1 под.     Черноусова О.В.,    а. 25 ВГУИТ
 * на предмет, тип занятия, подгруппу, преподавателей и аудиторию. Запись нестрогая (повторяющиеся пробелы, опечатки вроде
 * «лоаб.», аудитории вида «а. С и В» или «спорт.зал»), поэтому разбор идёт по узнаваемым частям, а не по позициям, и всё
 * неузнанное остаётся в предмете или в тексте аудитории: ничего не выбрасывается. Не знает ничего о геометрии Excel.
 * Перенос Java-класса SpoCellTextParser. Lookbehind и \p{...} заменены явными диапазонами и ручной проверкой символа слева
 * (см. util/text.ts), результат совпадает с эталоном на всех реальных файлах.
 */

const PARENTHESES = /\(([^()]*)\)/g;
const LETTER_DIGIT = '0-9' + L;
const SUBGROUP = new RegExp(`(\\d+)\\s*под(?:гр?)?(?![${L}])\\.?`, 'gi');
/**
 * Слово «аудитория» сокращённо: «а. 25», «а.18», «а 26.», «а . БАР», «ауд. 32», «Ауд. 19». Одинокое «а» считается, только если
 * после него цифра или точка, чтобы случайный союз в названии не принять за признак аудитории. Слева не должно быть буквы, цифры или точки.
 */
const ROOM_MARKER = new RegExp(`(?:[Аа]уд\\s*\\.?|а\\s*\\.|а(?=\\s*\\d))\\s*`, 'g');
/** После преподавателя признаком может быть и голое «а» перед словом («а БАР»). */
const ROOM_MARKER_AFTER_TEACHER = new RegExp(`(?:[Аа]уд\\s*\\.?|[Аа]\\s*\\.|[Аа](?=\\s*\\d)|а(?=\\s+[${L}])|а(?=\\s*$))\\s*`, 'g');
const LEFT_BOUNDARY = new RegExp(`[${LETTER_DIGIT}.]`);
/** Аудитории без «а.»: спортивные помещения и актовый зал. Условие «перед «стадион» не буква» записано как [^буква]. */
const PLACE_WORDS = new RegExp(
  `(спорт\\.?\\s*зал(?![${L}]).*?[^${L}]стадион|актовый\\s+зал|спорт\\.?\\s*зал|стадион)`,
  'i',
);
/** «Фамилия И.О.» с вариантами написания из файлов: «Д.» (одна инициала), «Н.А..», «Фомичев   Г.М.». */
const TEACHER = new RegExp(`[${LU}][${LL}]+(?:-[${LU}][${LL}]+)?\\s+[${LU}]\\.(?:\\s*[${LU}]\\.?)?\\.*`, 'g');
/** Знаки препинания (категория \p{P}) и пробелы. */
const PUNCT_OR_SPACE = /[!"#%&'()*,\-./:;?@[\\\]_{}«»„“”‘’‚‹›–—…‐‑‒―•·¡¿§¶\s]+/g;

const TYPE_ORDER: LessonType[] = ['LECTURE', 'PRACTICE', 'LAB'];

function typesOf(marker: string): Set<LessonType> {
  const m = marker.toLowerCase();
  const named = new Set<LessonType>();
  if (m.includes('лаб') || m.includes('лоаб')) named.add('LAB');
  if (m.includes('практ') || m.includes('прак')) named.add('PRACTICE');
  if (m.includes('теор') || m.includes('лек')) named.add('LECTURE');
  return named;
}

/** Первое совпадение регулярного выражения не раньше `from`, у которого слева не стоит запрещённый символ (замена lookbehind). */
function findWithLeftBoundary(re: RegExp, text: string, from: number, forbiddenLeft: RegExp | null): RegExpExecArray | null {
  const r = new RegExp(re.source, re.flags);
  r.lastIndex = from;
  let m: RegExpExecArray | null;
  while ((m = r.exec(text)) !== null) {
    if (forbiddenLeft !== null && m.index > 0 && forbiddenLeft.test(text[m.index - 1])) {
      r.lastIndex = m.index + 1;
      continue;
    }
    return m;
  }
  return null;
}

const cleanTail = (tail: string): string => collapseSpaces(tail).replace(/^[\s,.]+|[\s,.]+$/g, '');
const cleanName = (name: string): string => collapseSpaces(name).trim().replace(/\.{2,}$/, '.');
const cleanSubject = (subject: string): string => collapseSpaces(subject).replace(/^[\s,.;:-]+|[\s,;:-]+$/g, '');

interface Span {
  start: number;
  end: number;
}

function findTeachers(rest: string): { names: string[]; spans: Span[] } {
  const names: string[] = [];
  const spans: Span[] = [];
  const re = new RegExp(TEACHER.source, TEACHER.flags);
  let m: RegExpExecArray | null;
  while ((m = re.exec(rest)) !== null) {
    names.push(cleanName(m[0]));
    spans.push({ start: m.index, end: m.index + m[0].length });
  }
  return { names, spans };
}

export class SpoCellTextParser implements LessonTextParser {
  parse(text: string): CellContent | Rejected {
    if (isBlank(text)) return { kind: 'rejected', code: 'EMPTY_TEXT', message: 'Cell text is empty' };
    if (text.trim() === '0') return { kind: 'rejected', code: 'ZERO_PLACEHOLDER', message: 'Cell contains the placeholder 0' };

    const warnings: CellContent['warnings'] = [];
    let rest = collapseSpaces(text).trim();

    // 1. тип занятия по признаку в скобках; незнакомые скобки остаются в предмете
    const types = new Set<LessonType>();
    const rawMarkers: string[] = [];
    rest = rest.replace(PARENTHESES, (whole: string, inner: string) => {
      const named = typesOf(inner);
      if (named.size === 0) return whole;
      rawMarkers.push(inner.trim());
      named.forEach((t) => types.add(t));
      return ' ';
    });
    let lessonType: LessonType = 'OTHER';
    if (types.size === 1) {
      lessonType = [...types][0];
    } else if (types.size > 1) {
      const names = TYPE_ORDER.filter((t) => types.has(t)).join(', ');
      warnings.push({ code: 'AMBIGUOUS_LESSON_TYPE', message: `Cell names several lesson types [${names}]; set to OTHER` });
    }

    // 2. подгруппа в виде «1 под.»
    let subgroup: number | null = null;
    const sg = findWithLeftBoundary(SUBGROUP, rest, 0, new RegExp(`[${LETTER_DIGIT}]`));
    if (sg) {
      subgroup = Number(sg[1]);
      rest = rest.substring(0, sg.index) + ' ' + rest.substring(sg.index + sg[0].length);
    }

    // 3. преподаватели; предмет это всё, что стоит перед первым из них
    let { names: teachers, spans: teacherSpans } = findTeachers(rest);
    let firstTeacher = teacherSpans.length > 0 ? teacherSpans[0].start : -1;
    const lastTeacherEnd = teacherSpans.length > 0 ? teacherSpans[teacherSpans.length - 1].end : 0;

    // 4. аудитория: всё после первого признака, идущего за преподавателями; иначе известное название места
    let room: string | null = null;
    let roomStart = rest.length;
    const marker = findWithLeftBoundary(teachers.length === 0 ? ROOM_MARKER : ROOM_MARKER_AFTER_TEACHER, rest, lastTeacherEnd, LEFT_BOUNDARY);
    if (marker) {
      room = cleanTail(rest.substring(marker.index + marker[0].length));
      roomStart = marker.index;
    } else {
      const place = PLACE_WORDS.exec(rest);
      if (place) {
        room = collapseSpaces(place[0]).trim();
        rest = rest.substring(0, place.index) + ' ' + rest.substring(place.index + place[0].length);
        // текст сдвинулся: преподавателей надо найти заново в укороченном тексте
        ({ names: teachers, spans: teacherSpans } = findTeachers(rest));
        firstTeacher = teacherSpans.length > 0 ? teacherSpans[0].start : -1;
        roomStart = rest.length;
      }
    }

    // признака нет, но после последнего преподавателя есть текст: там и проходит занятие («Попова Н.Н., 540 С и В»)
    if (room === null && teacherSpans.length > 0) {
      const afterTeachers = teacherSpans[teacherSpans.length - 1].end;
      const tail = cleanTail(rest.substring(afterTeachers));
      if (tail !== '') {
        room = tail;
        roomStart = afterTeachers;
      }
    }

    let subject = firstTeacher >= 0 ? rest.substring(0, firstTeacher) : rest.substring(0, roomStart);
    // текст между преподавателями и после них (до аудитории), который не знак препинания и не часть имени
    let leftover = '';
    for (let i = 0; i < teacherSpans.length; i++) {
      const from = teacherSpans[i].end;
      const to = i + 1 < teacherSpans.length ? teacherSpans[i + 1].start : roomStart;
      if (to > from) leftover += rest.substring(from, to) + ' ';
    }
    subject = cleanSubject(subject);
    const extra = leftover.replace(PUNCT_OR_SPACE, ' ').trim();
    if (extra !== '') warnings.push({ code: 'UNPARSED_REMAINDER', message: `Text after the teachers was not classified: '${extra}'` });
    if (subject === '') return { kind: 'rejected', code: 'EMPTY_SUBJECT', message: 'No subject found in the cell' };
    return {
      kind: 'content',
      lessonType,
      rawLessonType: rawMarkers.length === 0 ? null : rawMarkers.join(', '),
      subject,
      teacher: teachers.length === 0 ? null : teachers.join(', '),
      room: room === null || room === '' ? null : room,
      subgroup,
      warnings,
    };
  }
}
