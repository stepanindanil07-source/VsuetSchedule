import { findRooms } from '../src/plan/rooms';
import { buildGraph, routeBetween } from '../src/plan/routing';
import type { PlanData } from '../src/plan/types';

/** План, сгенерированный из снимка (в git не хранится): тесты выполняются, только если файл есть рядом. */
let plan: PlanData | null = null;
try {
  plan = (require('../src/plan/generated/mainFloor1') as { GENERATED_PLAN: PlanData }).GENERATED_PLAN;
} catch {
  plan = null;
}
// describe.skip всё равно выполняет тело блока, поэтому при отсутствии файла блок не объявляется вовсе
if (plan === null) {
  it.skip('сгенерированного плана нет (в git не хранится): проверки пропущены', () => undefined);
} else describe('сгенерированный план первого этажа', () => {
  const floor = plan!.buildings[0].floors[0];
  const graph = buildGraph(floor);
  const room = (ref: string) => findRooms(plan!, ref)[0].room;

  it('есть контур здания и помещения без номеров', () => {
    expect(floor.outline!.length).toBeGreaterThan(0);
    expect(floor.rooms.filter((r) => r.ref === '' && !r.title).length).toBeGreaterThan(5);
    expect(floor.rooms.filter((r) => r.ref !== '').length).toBeGreaterThan(55);
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
});
