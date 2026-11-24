import { centroid } from './rooms';
import type { PlanFloor, PlanRoom, Point } from './types';

/**
 * Маршрут по этажу. Два источника «проходимости»:
 *  1. проходимые клетки (floor.walkable): узлы это центры клеток, соседние клетки соединяются;
 *  2. оси коридоров (floor.corridors): ломаные режутся на узлы с шагом STEP, перекрёстки склеиваются.
 * Дверь комнаты это ближайший к её границе узел. Длины в единицах плана: масштаба в метрах у плана нет, метры не показываются.
 */
const STEP = 18;
const JOIN = 14;

export interface Graph {
  nodes: Point[];
  edges: Array<Array<{ to: number; length: number }>>;
  /** Ключи клеток проходимой сетки (для спрямления пути); null для графа по осям коридоров. */
  cellKeys: Set<string> | null;
  cell: number;
}

const dist = (a: Point, b: Point): number => Math.hypot(a[0] - b[0], a[1] - b[1]);

export function buildGraph(floor: PlanFloor): Graph {
  const nodes: Point[] = [];
  const edges: Graph['edges'] = [];
  const link = (a: number, b: number): void => {
    const length = dist(nodes[a], nodes[b]);
    edges[a].push({ to: b, length });
    edges[b].push({ to: a, length });
  };
  const add = (p: Point): number => {
    nodes.push(p);
    edges.push([]);
    return nodes.length - 1;
  };

  if (floor.walkable !== undefined) {
    const { cell, cells } = floor.walkable;
    const index = new Map<string, number>();
    const key = (x: number, y: number): string => `${Math.round(x / cell)},${Math.round(y / cell)}`;
    for (const c of cells) index.set(key(c[0], c[1]), add(c));
    for (const [k, id] of index) {
      const [gx, gy] = k.split(',').map(Number);
      // восемь соседей; диагональ только если оба соседних по сторонам клетки проходимы (путь не режет угол стены)
      for (const [dx, dy] of [[1, 0], [0, 1], [1, 1], [1, -1]] as const) {
        const other = index.get(`${gx + dx},${gy + dy}`);
        if (other === undefined) continue;
        if (dx !== 0 && dy !== 0 && (!index.has(`${gx + dx},${gy}`) || !index.has(`${gx},${gy + dy}`))) continue;
        link(id, other);
      }
    }
    return { nodes, edges, cellKeys: new Set(index.keys()), cell };
  }

  for (const corridor of floor.corridors) {
    let previous: number | null = null;
    for (let s = 0; s < corridor.length - 1; s++) {
      const [a, b] = [corridor[s], corridor[s + 1]];
      const steps = Math.max(1, Math.ceil(dist(a, b) / STEP));
      for (let k = s === 0 ? 0 : 1; k <= steps; k++) {
        const t = k / steps;
        const id = add([a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t]);
        if (previous !== null) link(previous, id);
        previous = id;
      }
    }
  }
  for (let i = 0; i < nodes.length; i++) {
    for (let j = i + 1; j < nodes.length; j++) {
      if (dist(nodes[i], nodes[j]) < JOIN && !edges[i].some((e) => e.to === j)) link(i, j);
    }
  }
  return { nodes, edges, cellKeys: null, cell: STEP };
}

/** Расстояние от точки до границы многоугольника. */
function distanceToPolygon(p: Point, polygon: readonly Point[]): number {
  let best = Infinity;
  for (let i = 0; i < polygon.length; i++) {
    const a = polygon[i];
    const b = polygon[(i + 1) % polygon.length];
    const dx = b[0] - a[0];
    const dy = b[1] - a[1];
    const len2 = dx * dx + dy * dy || 1;
    const t = Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len2));
    best = Math.min(best, Math.hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dy)));
  }
  return best;
}

/** Узел, через который входят в комнату: ближайший к её границе; при равенстве ближайший к центру. */
export function doorNode(graph: Graph, room: PlanRoom): number {
  const c = centroid(room.polygon);
  let best = -1;
  let bestScore = Infinity;
  graph.nodes.forEach((n, i) => {
    const score = distanceToPolygon(n, room.polygon) * 4 + dist(n, c) * 0.1;
    if (score < bestScore) {
      bestScore = score;
      best = i;
    }
  });
  return best;
}

export interface Route {
  /** Ломаная от центра начальной комнаты до центра конечной. */
  points: Point[];
  /** Длина в единицах плана. */
  length: number;
}

function straight(graph: Graph, a: Point, b: Point): boolean {
  if (graph.cellKeys === null) return false;
  const steps = Math.ceil(dist(a, b) / (graph.cell / 2));
  for (let i = 0; i <= steps; i++) {
    const t = steps === 0 ? 0 : i / steps;
    const x = a[0] + (b[0] - a[0]) * t;
    const y = a[1] + (b[1] - a[1]) * t;
    if (!graph.cellKeys.has(`${Math.round(x / graph.cell)},${Math.round(y / graph.cell)}`)) return false;
  }
  return true;
}

/** Спрямление: от каждой точки идём как можно дальше по прямой, не выходя из проходимых клеток. */
function smooth(graph: Graph, path: Point[]): Point[] {
  if (graph.cellKeys === null || path.length < 3) return path;
  const out: Point[] = [path[0]];
  let i = 0;
  while (i < path.length - 1) {
    let j = path.length - 1;
    while (j > i + 1 && !straight(graph, path[i], path[j])) j--;
    out.push(path[j]);
    i = j;
  }
  return out;
}

/**
 * Кратчайший путь между комнатами по проходимым местам; null, если пути нет: коридоры не связаны или в одну из комнат на этом этаже
 * нельзя попасть (access).
 */
export function routeBetween(floor: PlanFloor, from: PlanRoom, to: PlanRoom, graph: Graph = buildGraph(floor)): Route | null {
  if (graph.nodes.length === 0 || from.access !== undefined || to.access !== undefined) return null;
  const a = centroid(from.polygon);
  const b = centroid(to.polygon);
  const start = doorNode(graph, from);
  const goal = doorNode(graph, to);
  const best = new Array<number>(graph.nodes.length).fill(Infinity);
  const previous = new Array<number>(graph.nodes.length).fill(-1);
  const open = new Set<number>([start]);
  best[start] = 0;
  while (open.size > 0) {
    let current = -1;
    for (const id of open) if (current === -1 || best[id] < best[current]) current = id;
    open.delete(current);
    if (current === goal) break;
    for (const { to: next, length } of graph.edges[current]) {
      if (best[current] + length < best[next]) {
        best[next] = best[current] + length;
        previous[next] = current;
        open.add(next);
      }
    }
  }
  if (best[goal] === Infinity) return null;
  const path: Point[] = [];
  for (let at = goal; at !== -1; at = previous[at]) path.push(graph.nodes[at]);
  path.reverse();
  const points: Point[] = [a, ...smooth(graph, path), b];
  let length = 0;
  for (let i = 1; i < points.length; i++) length += dist(points[i - 1], points[i]);
  return { points, length };
}
