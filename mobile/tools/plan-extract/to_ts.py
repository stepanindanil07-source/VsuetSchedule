#!/usr/bin/env python3
"""Превращает результат extract.py в файл данных приложения (TypeScript, формат src/plan/types.ts)."""
import json
import sys

src, dst = sys.argv[1], sys.argv[2]
d = json.load(open(src))

# Помещения: безымянные мелкие обрезки между перегородками отбрасываются
rooms = []
for r in d['rooms']:
    xs = [p[0] for p in r['polygon']]
    ys = [p[1] for p in r['polygon']]
    w, h = max(xs) - min(xs), max(ys) - min(ys)
    if not r['ref'] and not r.get('title') and (r['area'] < 1200 or min(w, h) < 14):
        continue
    cx, cy = sum(xs) / len(xs), sum(ys) / len(ys)
    room = {'ref': r['ref'], 'polygon': [[int(x), int(y)] for x, y in r['polygon']]}
    if r.get('title'):
        room['title'] = r['title']
    # северо-восточное крыло: на первом этаже в него нет прохода, попасть можно только со второго этажа
    if cx > 1190 and cy < 640:
        room['access'] = 'UPPER_FLOOR'
    rooms.append(room)

cell = d['cell']
# оси коридоров, которых не видно по цвету (узкие проходы), добавляются вручную: extra.json -> extraCorridors
extra = json.load(open(sys.argv[3])) if len(sys.argv) > 3 else {}
cellset = {(c[0] // cell, c[1] // cell): c for c in d['walkable']}
for line in extra.get('extraCorridors', []):
    for (x0, y0), (x1, y1) in zip(line, line[1:]):
        n = max(1, int(max(abs(x1 - x0), abs(y1 - y0)) / (cell / 2)))
        for i in range(n + 1):
            x, y = x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n
            cellset[(int(x) // cell, int(y) // cell)] = [int(x) // cell * cell + cell // 2, int(y) // cell * cell + cell // 2]
# северо-восточное крыло отрезано от первого этажа
cellset = {k: v for k, v in cellset.items() if not (v[0] > 1180 and v[1] < 640)}
# только связная часть основной сети (от главного коридора): отдельные пятна (библиотека, двор) в маршрутах не участвуют
import collections
seed = min(cellset, key=lambda k: (k[0] * cell - 600) ** 2 + (k[1] * cell - 620) ** 2)
seen, queue = {seed}, collections.deque([seed])
while queue:
    gx, gy = queue.popleft()
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            n = (gx + dx, gy + dy)
            if n in cellset and n not in seen:
                seen.add(n)
                queue.append(n)
cells = [cellset[k] for k in sorted(seen)]
stairs = [[215, 258], [190, 458], [430, 665], [1150, 685], [1180, 765], [1523, 772], [1755, 703], [1630, 247], [527, 762], [692, 764], [397, 910], [395, 1043]]

W, H = d['size']
plan = {
    'provenance': 'Демо: первый этаж главного корпуса, контур и помещения получены обработкой снимка карты 2ГИС (владелец проекта получил согласие университета на использование данных вуза). Приблизительно, не для выпуска: заменить планами университета.',
    'buildings': [{
        'id': 'main', 'name': 'Главный корпус',
        'floors': [{
            'id': 'main-1', 'level': 1, 'title': '1 этаж', 'width': W, 'height': H,
            'rooms': rooms, 'corridors': [], 'stairs': stairs,
            'outline': [[[int(x), int(y)] for x, y in lp] for lp in d['outline']],
            'walkable': {'cell': d['cell'], 'cells': cells},
        }],
    }],
}
body = json.dumps(plan, ensure_ascii=False, separators=(',', ':'))
open(dst, 'w').write(
    "// СГЕНЕРИРОВАНО tools/plan-extract (extract.py, to_ts.py) из снимка карты; не править руками и не коммитить: файл в .gitignore.\n"
    "import type { PlanData } from '../types';\n\n"
    f"export const GENERATED_PLAN: PlanData = {body} as unknown as PlanData;\n"
)
print('rooms', len(rooms), 'named', sum(1 for r in rooms if r['ref'] or r.get('title')), 'upper-floor', sum(1 for r in rooms if r.get('access')), 'cells', len(cells))
