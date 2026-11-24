import { findRooms, pointInPolygon } from '../src/plan/rooms';
import { buildGraph, routeBetween } from '../src/plan/routing';
import type { PlanData } from '../src/plan/types';

/** Эталон плана (в git не хранится): проверки выполняются, только если файл есть рядом. */
let plan: PlanData | null = null;
try {
  plan = (require('../src/plan/etalon/mainFloor1') as { ETALON_PLAN: PlanData }).ETALON_PLAN;
} catch {
  plan = null;
}
// describe.skip всё равно выполняет тело блока, поэтому при отсутствии файла блок не объявляется вовсе
if (plan === null) {
  it.skip('эталона плана нет (в git не хранится): проверки пропущены', () => undefined);
} else describe('эталон плана первого этажа', () => {
  const floor = plan!.buildings[0].floors[0];
  const graph = buildGraph(floor);
  const room = (ref: string) => findRooms(plan!, ref)[0].room;

  it('есть контур здания и пронумерованные помещения', () => {
    expect(floor.outline!.length).toBeGreaterThan(0);
    expect(floor.rooms.filter((r) => r.ref !== '').length).toBeGreaterThan(75);
  });

  it('правки владельца проекта: 1б, медпункт 101, 128, 138 и 140', () => {
    for (const ref of ['1а', '1б', '101', '128', '138', '140']) expect(findRooms(plan!, ref).length).toBeGreaterThan(0);
    expect(findRooms(plan!, '101')[0].room.title).toBe('Медпункт');
  });

  it('северо-восточное крыло отмечено как доступное только со 2 этажа', () => {
    for (const ref of ['019', '01', '012', '016', '031']) expect(room(ref).access).toBe('UPPER_FLOOR');
    for (const ref of ['105', '129', '145', '151', '130']) expect(room(ref).access).toBeUndefined();
  });

  it('маршрут в основной части есть, в северо-восточное крыло на этом этаже не строится', () => {
    expect(routeBetween(floor, room('105'), room('151'), graph)).not.toBeNull();
    expect(routeBetween(floor, room('102'), room('134'), graph)).not.toBeNull();
    expect(routeBetween(floor, room('111'), room('019'), graph)).toBeNull();
    expect(routeBetween(floor, room('019'), room('111'), graph)).toBeNull();
  });

  it('все помещения с номерами вне северо-восточного крыла достижимы от 129', () => {
    const unreachable = floor.rooms.filter((r) => r.ref !== '' && r.access === undefined && routeBetween(floor, room('129'), r, graph) === null).map((r) => r.ref);
    expect(unreachable).toEqual([]);
  });

  it('маршрут в правое крыло идёт в обход стены у мужского туалета, не сквозь неё', () => {
    const walls = floor.walls ?? [];
    expect(walls.length).toBeGreaterThan(0);
    type P = readonly [number, number];
    const side = (a: P, b: P, c: P) => Math.sign((b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]));
    const crosses = (a: P, b: P, c: P, d: P) => side(a, b, c) * side(a, b, d) < 0 && side(c, d, a) * side(c, d, b) < 0;
    for (const [from, to] of [['124', '130'], ['126', '135'], ['102', '151'], ['111', '140'], ['124', '12']] as const) {
      const route = routeBetween(floor, room(from), room(to), graph);
      expect(route).not.toBeNull();
      const path = route!.points.slice(1, -1); // без отрезков от центров комнат до дверей
      for (let i = 1; i < path.length; i++) {
        for (const [c, d] of walls) expect({ from, to, through: crosses(path[i - 1], path[i], c, d) }).toEqual({ from, to, through: false });
      }
    }
  });

  it('значки лестниц не заходят на помещения и стены', () => {
    const half = 9;
    const overlaps = floor.stairs.flatMap(([x, y]) => {
      const rooms = floor.rooms.filter((r) => {
        const xs = r.polygon.map((p) => p[0]);
        const ys = r.polygon.map((p) => p[1]);
        return x + half > Math.min(...xs) && x - half < Math.max(...xs) && y + half > Math.min(...ys) && y - half < Math.max(...ys);
      });
      const walls = (floor.walls ?? []).filter(([a, b]) => x + half > Math.min(a[0], b[0]) && x - half < Math.max(a[0], b[0]) && y + half > Math.min(a[1], b[1]) && y - half < Math.max(a[1], b[1]));
      return rooms.length + walls.length > 0 ? [{ x, y, rooms: rooms.map((r) => r.ref || r.title), walls: walls.length }] : [];
    });
    expect(overlaps).toEqual([]);
  });

  it('помещения не накладываются друг на друга', () => {
    const boxes = floor.rooms.map((r) => {
      const xs = r.polygon.map((p) => p[0]);
      const ys = r.polygon.map((p) => p[1]);
      return { polygon: r.polygon, name: r.ref || r.title || '(без номера)', x0: Math.min(...xs), y0: Math.min(...ys), x1: Math.max(...xs), y1: Math.max(...ys) };
    });
    const clashes: string[] = [];
    for (let i = 0; i < boxes.length; i++) {
      for (let j = i + 1; j < boxes.length; j++) {
        const a = boxes[i];
        const b = boxes[j];
        const w = Math.min(a.x1, b.x1) - Math.max(a.x0, b.x0);
        const h = Math.min(a.y1, b.y1) - Math.max(a.y0, b.y0);
        // касание стенами (несколько единиц) допустимо, наложение площадью нет
        if (w <= 4 || h <= 4) continue;
        // рамки пересекаются; у непрямоугольных помещений проверяем сами фигуры точками внутри пересечения
        let shared = 0;
        for (let x = Math.max(a.x0, b.x0) + 2.5; x < Math.min(a.x1, b.x1) - 2; x += 3) {
          for (let y = Math.max(a.y0, b.y0) + 2.5; y < Math.min(a.y1, b.y1) - 2; y += 3) {
            if (pointInPolygon([x, y], a.polygon) && pointInPolygon([x, y], b.polygon)) shared++;
          }
        }
        if (shared > 0) clashes.push(`${a.name} × ${b.name} у ${Math.max(a.x0, b.x0)},${Math.max(a.y0, b.y0)}`);
      }
    }
    expect(clashes).toEqual([]);
  });
});
