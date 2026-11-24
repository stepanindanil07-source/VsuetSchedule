import type { PlanData, Point, RoomHit } from './types';

/** Буквы латиницы, похожие на кириллицу: в расписании и на плане их легко перепутать («108a»). */
const LATIN_TO_CYRILLIC: Record<string, string> = { a: 'а', b: 'в', c: 'с', e: 'е', h: 'н', k: 'к', m: 'м', o: 'о', p: 'р', t: 'т', x: 'х', y: 'у' };

/**
 * Приводит номер аудитории к сравнимому виду: нижний регистр, без пробелов и знаков, латиница похожих букв заменена на кириллицу.
 * Ведущие нули сохраняются: «016» и «16» это разные помещения.
 */
export function normalizeRoomRef(raw: string): string {
  return Array.from(raw.toLowerCase())
    .map((ch) => LATIN_TO_CYRILLIC[ch] ?? ch)
    .filter((ch) => /[0-9а-яё]/.test(ch))
    .join('');
}

/**
 * Номера аудиторий из поля «аудитория» занятия. В поле может быть несколько помещений («329, 330») и приписки («ауд. 25», «а. 25»).
 * Возвращаются только токены, где есть цифра: «спорт.зал» и «актовый зал» на плане по номеру не ищутся.
 */
export function extractRoomRefs(classroom: string | null): string[] {
  if (classroom === null) return [];
  return classroom
    .split(/[,;/]+/)
    .map((part) => part.replace(/(^|\s)(ауд(итория)?|а)\.?\s*(?=\d)/gi, '$1'))
    .map(normalizeRoomRef)
    .filter((ref) => /[0-9]/.test(ref));
}

const withoutLeadingZeros = (normalized: string): string => normalized.replace(/^0+(?=[0-9])/, '');

/**
 * Помещения плана с этим номером. Сначала точное совпадение («17» не равно «017»: на плане есть обе комнаты). Если точного нет,
 * ищется совпадение без ведущих нулей («9» в расписании и «09» на плане) и результат помечается как вероятный.
 */
export function findRooms(plan: PlanData, ref: string): RoomHit[] {
  const wanted = normalizeRoomRef(ref);
  const collect = (matches: (planRef: string) => boolean, approximate: boolean): RoomHit[] => {
    const hits: RoomHit[] = [];
    for (const building of plan.buildings) {
      for (const floor of building.floors) {
        for (const room of floor.rooms) {
          if (room.ref !== '' && matches(normalizeRoomRef(room.ref))) hits.push(approximate ? { building, floor, room, approximate } : { building, floor, room });
        }
      }
    }
    return hits;
  };
  const exact = collect((r) => r === wanted, false);
  if (exact.length > 0) return exact;
  const loose = withoutLeadingZeros(wanted);
  return collect((r) => withoutLeadingZeros(r) === loose, true);
}

export function centroid(polygon: readonly Point[]): Point {
  let x = 0;
  let y = 0;
  for (const [px, py] of polygon) {
    x += px;
    y += py;
  }
  return [x / polygon.length, y / polygon.length];
}

/** Лежит ли точка внутри многоугольника (чётно-нечётное правило). */
export function pointInPolygon(point: Point, polygon: readonly Point[]): boolean {
  const [x, y] = point;
  let inside = false;
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
    const [xi, yi] = polygon[i];
    const [xj, yj] = polygon[j];
    if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}
