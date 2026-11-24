#!/usr/bin/env python3
"""
Применяет правки, нарисованные человеком поверх картинки плана, к собранному плану (out.json после build_plan.py).

Правки задаются файлом JSON в координатах той картинки (пиксели), а `transform` переводит их в координаты плана:
plan = (x0 + px * k, y0 + py * k). Операции:
  add      новое помещение: {"ref", "title"?, "rect": [x0, y0, x1, y1]} или "polygon": [[x, y], ...]
  squeeze  сжать помещения внутри "rect" по горизонтали к новому правому краю "to_right"
  set      изменить помещение, в котором лежит точка "at": ref, title, left, right, top, bottom
  remove   убрать помещение, в котором лежит точка "at"
  outline  заменить в контуре цепочку вершин от ближайшей к "from" до ближайшей к "to" (включительно) на "points"
  wall     внутренняя стена: {"from": [x, y], "to": [x, y]} (рисуется и не проходится маршрутом)
  stair    передвинуть значок лестницы, ближайший к "from", в "to"
После правок проходимые клетки пересчитываются: пол внутри контура без комнат и стен.

Запуск: apply_marks.py plan.json marks.json out.json
"""
import importlib.util
import json
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
spec = importlib.util.spec_from_file_location('bp', os.path.join(HERE, 'build_plan.py'))
bp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bp)


def bbox(poly):
    xs = [p[0] for p in poly]
    ys = [p[1] for p in poly]
    return [min(xs), min(ys), max(xs), max(ys)]


def rect(b):
    x0, y0, x1, y1 = [round(v, 1) for v in b]
    return [[x0, y0], [x1, y0], [x1, y1], [x0, y1]]


def main(plan_path, marks_path, out_path):
    plan = json.load(open(plan_path))
    marks = json.load(open(marks_path))
    tf = marks['transform']
    X = lambda v: tf['x0'] + v * tf['k']
    Y = lambda v: tf['y0'] + v * tf['k']
    P = lambda p: [round(X(p[0]), 1), round(Y(p[1]), 1)]
    rooms = plan['rooms']
    walls = list(plan.get('walls', []))  # стены прошлых раундов правок сохраняются

    def find(at):
        x, y = X(at[0]), Y(at[1])
        hits = [r for r in rooms if bbox(r['polygon'])[0] <= x <= bbox(r['polygon'])[2] and bbox(r['polygon'])[1] <= y <= bbox(r['polygon'])[3]]
        if not hits:
            raise SystemExit(f'нет помещения в точке {at}')
        return min(hits, key=lambda r: (bbox(r['polygon'])[2] - bbox(r['polygon'])[0]) * (bbox(r['polygon'])[3] - bbox(r['polygon'])[1]))

    for op in marks['ops']:
        kind = op['op']
        if kind == 'add':
            if 'polygon' in op:
                polygon = [P(p) for p in op['polygon']]
            else:
                x0, y0, x1, y1 = op['rect']
                polygon = rect([X(x0), Y(y0), X(x1), Y(y1)])
            rooms.append({'ref': op.get('ref', ''), 'title': op.get('title'), 'polygon': polygon, 'area': 9999})
        elif kind == 'squeeze':
            # помещения внутри рамки сжимаются по горизонтали к новому правому краю (освобождают место в конце ряда)
            rx0, ry0, rx1, ry1 = X(op['rect'][0]), Y(op['rect'][1]), X(op['rect'][2]), Y(op['rect'][3])
            end = X(op['to_right'])
            inside = [r for r in rooms if bbox(r['polygon'])[0] >= rx0 and bbox(r['polygon'])[2] <= rx1 and bbox(r['polygon'])[1] >= ry0 and bbox(r['polygon'])[3] <= ry1]
            if not inside:
                raise SystemExit(f'в рамке {op["rect"]} нет помещений')
            left = min(bbox(r['polygon'])[0] for r in inside)
            right = max(bbox(r['polygon'])[2] for r in inside)
            for r in inside:
                r['polygon'] = [[round(left + (x - left) * (end - left) / (right - left), 1), y] for x, y in r['polygon']]
            print('сжато помещений:', len(inside))
        elif kind == 'set':
            r = find(op['at'])
            b = bbox(r['polygon'])
            for key, i, f in (('left', 0, X), ('top', 1, Y), ('right', 2, X), ('bottom', 3, Y)):
                if key in op:
                    b[i] = f(op[key])
            r['polygon'] = rect(b)
            for key in ('ref', 'title'):
                if key in op:
                    r[key] = op[key]
            r['area'] = max(r.get('area') or 0, 9999)
        elif kind == 'remove':
            rooms.remove(find(op['at']))
        elif kind == 'outline':
            target_from, target_to = P(op['from']), P(op['to'])
            best = None
            for li, lp in enumerate(plan['outline']):
                i = min(range(len(lp)), key=lambda k: math.dist(lp[k], target_from))
                j = min(range(len(lp)), key=lambda k: math.dist(lp[k], target_to))
                score = math.dist(lp[i], target_from) + math.dist(lp[j], target_to)
                if best is None or score < best[0]:
                    best = (score, li, i, j)
            score, li, i, j = best
            if score > 14 * tf['k']:
                raise SystemExit(f'вершины контура не найдены у {op["from"]} .. {op["to"]} (расхождение {score:.1f})')
            lp = plan['outline'][li]
            new = [P(p) for p in op['points']]
            plan['outline'][li] = lp[:i] + new + lp[j + 1:] if i <= j else new + lp[j + 1:i]
        elif kind == 'wall':
            walls.append([P(op['from']), P(op['to'])])
        elif kind == 'stair':
            i = min(range(len(plan['stairs'])), key=lambda k: math.dist(plan['stairs'][k], P(op['from'])))
            plan['stairs'][i] = P(op['to'])
        else:
            raise SystemExit(f'неизвестная операция {kind}')

    # проходимые клетки заново: пол внутри контура без комнат (чуть уже, чем есть) и без клеток у внутренних стен
    W, H = plan['size']
    cell = plan['cell']
    floor = bp.raster_loops(plan['outline'], (H, W), 1)
    room_mask = np.zeros_like(floor)
    for r in rooms:
        x0, y0, x1, y1 = bbox(r['polygon'])
        room_mask[max(0, int(y0) + 3):int(y1) - 2, max(0, int(x0) + 3):int(x1) - 2] = True
    floor &= ~room_mask

    def near_wall(c):
        for (ax, ay), (bx, by) in walls:
            dx, dy = bx - ax, by - ay
            t = max(0.0, min(1.0, ((c[0] - ax) * dx + (c[1] - ay) * dy) / ((dx * dx + dy * dy) or 1)))
            if math.hypot(c[0] - ax - t * dx, c[1] - ay - t * dy) < cell * 0.75:
                return True
        return False

    walk = []
    for y in range(0, H - cell, cell):
        for x in range(0, W - cell, cell):
            block = floor[y:y + cell, x:x + cell]
            c = [x + cell // 2, y + cell // 2]
            if block.size and block.mean() > 0.55 and not near_wall(c):
                walk.append(c)
    plan['walkable'] = walk
    plan['walls'] = walls
    plan['extraCorridors'] = []  # проходы теперь целиком из геометрии: прежние дорисовки не должны проходить сквозь стены
    json.dump(plan, open(out_path, 'w'), ensure_ascii=False)
    print('правок', len(marks['ops']), 'помещений', len(rooms), 'стен', len(walls), 'проходимых клеток', len(walk))


if __name__ == '__main__':
    main(*sys.argv[1:4])
