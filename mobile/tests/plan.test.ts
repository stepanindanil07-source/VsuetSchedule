import { DEMO_PLAN } from '../src/plan/demoMainFloor1';
import { centroid, extractRoomRefs, findRooms, normalizeRoomRef, pointInPolygon } from '../src/plan/rooms';
import { buildGraph, routeBetween } from '../src/plan/routing';

const floor = DEMO_PLAN.buildings[0].floors[0];
const room = (ref: string) => floor.rooms.find((r) => r.ref === ref)!;

describe('номера аудиторий', () => {
  it('нормализация: регистр, пробелы, латиница вместо кириллицы, ведущие нули остаются', () => {
    expect(normalizeRoomRef('108a')).toBe('108а'); // латинская a
    expect(normalizeRoomRef(' 108 А ')).toBe('108а');
    expect(normalizeRoomRef('016а')).toBe('016а');
    expect(normalizeRoomRef('16а')).not.toBe(normalizeRoomRef('016а'));
  });

  it('из поля «аудитория» занятия берутся номера: несколько помещений и приписки', () => {
    expect(extractRoomRefs('329')).toEqual(['329']);
    expect(extractRoomRefs('329, 330')).toEqual(['329', '330']);
    expect(extractRoomRefs('ауд. 25')).toEqual(['25']);
    expect(extractRoomRefs('а. 25')).toEqual(['25']);
    expect(extractRoomRefs('108а')).toEqual(['108а']);
    expect(extractRoomRefs('спорт.зал')).toEqual([]);
    expect(extractRoomRefs(null)).toEqual([]);
  });

  it('поиск на плане: найденная аудитория, повторяющийся номер (оба помещения), неизвестный номер', () => {
    expect(findRooms(DEMO_PLAN, '129')).toHaveLength(1);
    expect(findRooms(DEMO_PLAN, '125')).toHaveLength(2); // на плане два помещения с этим номером
    expect(findRooms(DEMO_PLAN, '108a')[0].floor.title).toBe('1 этаж');
    expect(findRooms(DEMO_PLAN, '999')).toEqual([]);
  });

  it('нет точного совпадения: ищется без ведущих нулей и помечается вероятным; точное совпадение важнее', () => {
    const nine = findRooms(DEMO_PLAN, '9');
    expect(nine.map((h) => h.room.ref)).toEqual(['09']);
    expect(nine[0].approximate).toBe(true);
    // «17» есть на плане и как «17», и как «017»: берётся только точное
    expect(findRooms(DEMO_PLAN, '17').map((h) => h.room.ref)).toEqual(['17']);
    expect(findRooms(DEMO_PLAN, '17')[0].approximate).toBeUndefined();
    expect(findRooms(DEMO_PLAN, '017').map((h) => h.room.ref)).toEqual(['017']);
  });
});

describe('геометрия', () => {
  it('точка в многоугольнике и центр', () => {
    const r = room('129');
    expect(pointInPolygon(centroid(r.polygon), r.polygon)).toBe(true);
    expect(pointInPolygon([5, 5], r.polygon)).toBe(false);
  });

  it('комнаты не пересекаются своими центрами (центр одной не лежит в другой)', () => {
    const numbered = floor.rooms.filter((r) => r.ref !== '');
    for (const a of numbered) {
      for (const b of numbered) {
        if (a !== b) expect(pointInPolygon(centroid(a.polygon), b.polygon)).toBe(false);
      }
    }
  });

  it('все комнаты внутри плана', () => {
    for (const r of floor.rooms) for (const [x, y] of r.polygon) expect(x >= 0 && x <= floor.width && y >= 0 && y <= floor.height).toBe(true);
  });
});

describe('маршрут по коридорам', () => {
  const graph = buildGraph(floor);

  it('есть путь между далёкими комнатами разных крыльев', () => {
    for (const [from, to] of [['105', '151'], ['103', '012'], ['12', '019'], ['102', '134'], ['133', '031']] as const) {
      const route = routeBetween(floor, room(from), room(to), graph);
      expect(route).not.toBeNull();
      expect(route!.points[0]).toEqual(centroid(room(from).polygon));
      expect(route!.points[route!.points.length - 1]).toEqual(centroid(room(to).polygon));
    }
  });

  it('путь не короче прямой и симметричен', () => {
    const ab = routeBetween(floor, room('105'), room('151'), graph)!;
    const ba = routeBetween(floor, room('151'), room('105'), graph)!;
    const [x1, y1] = centroid(room('105').polygon);
    const [x2, y2] = centroid(room('151').polygon);
    expect(ab.length).toBeGreaterThanOrEqual(Math.hypot(x1 - x2, y1 - y2));
    expect(Math.abs(ab.length - ba.length)).toBeLessThan(1);
  });

  it('все пронумерованные комнаты достижимы от 129 (коридоры связаны)', () => {
    for (const r of floor.rooms.filter((x) => x.ref !== '')) expect(routeBetween(floor, room('129'), r, graph)).not.toBeNull();
  });

  it('этаж без коридоров маршрута не имеет', () => {
    expect(routeBetween({ ...floor, corridors: [] }, room('105'), room('151'))).toBeNull();
  });
});
