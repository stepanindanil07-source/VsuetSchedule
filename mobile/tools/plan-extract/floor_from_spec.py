#!/usr/bin/env python3
"""
Собирает этаж из описания: помещения, коридоры и лестницы заданы вручную в пикселях выпрямленных крупных кадров (координаты стен снимаются
по белым линиям). Так геометрия получается ровной: общая стена задана одним числом и у соседей совпадает точно.

Описание (JSON):
  {"id", "level", "title", "size": [W, H], "cell": 12,
   "parts": [ {"name", "scale": s, "offset": [dx, dy],          # план = scale * точка кадра + offset
               "rooms": [ {"ref"?, "title"?, "office"?, "tone"?, "rect": [x0, y0, x1, y1] | "polygon": [[x, y], ...]} ],
               "outline": [ polygon, ... ], "stairs": [[x, y], ...], "walls": [[[x, y], [x, y]], ...]} ]}

Проходимые клетки: пол внутри контуров без помещений и без клеток у внутренних стен.
Результат: этаж в формате PlanFloor приложения (src/plan/types.ts).

Запуск: floor_from_spec.py spec.json floor.json
"""
import importlib.util
import json
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
spec_ = importlib.util.spec_from_file_location('bp', os.path.join(HERE, 'build_plan.py'))
bp = importlib.util.module_from_spec(spec_)
spec_.loader.exec_module(bp)


def drop_specks(cells, cell, smallest=3):
    """Убирает одиночные клетки в щелях между помещениями: иначе «дверь» комнаты может оказаться в такой щели и маршрут пропадёт."""
    index = {(x // cell, y // cell): [x, y] for x, y in cells}
    seen, keep = set(), []
    for start in index:
        if start in seen:
            continue
        seen.add(start)
        queue, group = [start], []
        while queue:
            gx, gy = queue.pop()
            group.append((gx, gy))
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    n = (gx + dx, gy + dy)
                    if n not in index or n in seen:
                        continue
                    # те же правила, что в приложении: по диагонали только если обе соседние клетки проходимы
                    if dx and dy and ((gx + dx, gy) not in index or (gx, gy + dy) not in index):
                        continue
                    seen.add(n)
                    queue.append(n)
        if len(group) >= smallest:
            keep += [index[k] for k in group]
    return sorted(keep, key=lambda c: (c[1], c[0]))


def main(spec_path, out_path):
    spec = json.load(open(spec_path))
    W, H = spec['size']
    cell = spec.get('cell', 12)
    rooms, outline, stairs, walls = [], [], [], []
    for part in spec['parts']:
        s = part['scale']
        dx, dy = part['offset']
        P = lambda p: [round(s * p[0] + dx), round(s * p[1] + dy)]
        for r in part.get('rooms', []):
            if 'rect' in r:
                x0, y0, x1, y1 = r['rect']
                polygon = [[x0, y0], [x1, y0], [x1, y1], [x0, y1]]
            else:
                polygon = r['polygon']
            room = {'ref': r.get('ref', ''), 'polygon': [P(p) for p in polygon]}
            if r.get('title'):
                room['title'] = r['title']
            if r.get('tone'):
                room['tone'] = r['tone']  # цвет как в исходнике: 'gray' или 'pink' (помещения без названия)
            if r.get('office'):
                room['office'] = True  # кабинет без номера: рисуется как аудитория, а не как служебное помещение
            rooms.append(room)
        outline += [[P(p) for p in loop] for loop in part.get('outline', [])]
        stairs += [P(p) for p in part.get('stairs', [])]
        walls += [[P(a), P(b)] for a, b in part.get('walls', [])]

    # каждый контур заливается отдельно: соседние части не вычитают друг друга
    floor = np.zeros((H, W), dtype=bool)
    for loop in outline:
        floor |= bp.raster_loops([loop], (H, W), 1)
    room_mask = np.zeros_like(floor)
    for r in rooms:
        shape = bp.raster_loops([r['polygon']], (H, W), 1)
        # безымянный блок со значком лестницы это лестничная клетка: по нему ходят
        if not r['ref'] and not r.get('title') and any(0 <= y < H and 0 <= x < W and shape[y, x] for x, y in stairs):
            continue
        room_mask |= bp.ex.erode(shape, 5)
    floor &= ~room_mask

    def near_wall(c):
        for (ax, ay), (bx, by) in walls:
            vx, vy = bx - ax, by - ay
            t = max(0.0, min(1.0, ((c[0] - ax) * vx + (c[1] - ay) * vy) / ((vx * vx + vy * vy) or 1)))
            if math.hypot(c[0] - ax - t * vx, c[1] - ay - t * vy) < cell * 0.75:
                return True
        return False

    cells = []
    for y in range(0, H - cell, cell):
        for x in range(0, W - cell, cell):
            block = floor[y:y + cell, x:x + cell]
            c = [x + cell // 2, y + cell // 2]
            if block.mean() > 0.55 and not near_wall(c):
                cells.append(c)

    cells = drop_specks(cells, cell)
    out = {
        'id': spec['id'], 'level': spec['level'], 'title': spec['title'], 'width': W, 'height': H,
        'rooms': rooms, 'corridors': [], 'stairs': stairs, 'outline': outline,
        'walkable': {'cell': cell, 'cells': cells}, 'walls': walls,
    }
    json.dump(out, open(out_path, 'w'), ensure_ascii=False, separators=(',', ':'))
    print('помещений', len(rooms), 'с подписью', sum(1 for r in rooms if r['ref'] or r.get('title')), 'лестниц', len(stairs), 'клеток', len(cells))


if __name__ == '__main__':
    main(*sys.argv[1:3])
