import { centroid } from './rooms';
import type { PlanFloor, PlanRoom, Point } from './types';

/**
 * Маршрут по оси коридоров одного этажа. Коридоры режутся на узлы с шагом STEP, соседние узлы соединяются, узлы разных коридоров,
 * лежащие ближе JOIN, склеиваются (перекрёстки). Дверь комнаты это ближайший к её центру узел коридора.
 * Длины в единицах плана: масштаба в метрах у плана нет, поэтому метры не показываются.
 */
const STEP = 18;
const JOIN = 14;

interface Graph {
  nodes: Point[];
  edges: Array<Array<{ to: number; length: number }>>;
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
  // перекрёстки: узлы разных коридоров рядом считаются одним местом
  for (let i = 0; i < nodes.length; i++) {
    for (let j = i + 1; j < nodes.length; j++) {
      if (dist(nodes[i], nodes[j]) < JOIN && !edges[i].some((e) => e.to === j)) link(i, j);
    }
  }
  return { nodes, edges };
}

export function nearestNode(graph: Graph, point: Point): number {
  let best = -1;
  let bestDistance = Infinity;
  graph.nodes.forEach((n, i) => {
    const d = dist(n, point);
    if (d < bestDistance) {
      bestDistance = d;
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

/** Кратчайший путь между комнатами по коридорам; null, если коридоры не связаны. */
export function routeBetween(floor: PlanFloor, from: PlanRoom, to: PlanRoom, graph: Graph = buildGraph(floor)): Route | null {
  if (graph.nodes.length === 0) return null;
  const a = centroid(from.polygon);
  const b = centroid(to.polygon);
  const start = nearestNode(graph, a);
  const goal = nearestNode(graph, b);
  const best = new Array<number>(graph.nodes.length).fill(Infinity);
  const previous = new Array<number>(graph.nodes.length).fill(-1);
  const open = new Set<number>([start]);
  best[start] = 0;
  while (open.size > 0) {
    let current = -1;
    for (const id of open) if (current === -1 || best[id] < best[current]) current = id;
    open.delete(current);
    if (current === goal) break;
    for (const { to, length } of graph.edges[current]) {
      if (best[current] + length < best[to]) {
        best[to] = best[current] + length;
        previous[to] = current;
        open.add(to);
      }
    }
  }
  if (best[goal] === Infinity) return null;
  const path: Point[] = [];
  for (let at = goal; at !== -1; at = previous[at]) path.push(graph.nodes[at]);
  path.reverse();
  const points: Point[] = [a, ...path, b];
  let length = 0;
  for (let i = 1; i < points.length; i++) length += dist(points[i - 1], points[i]);
  return { points, length };
}
