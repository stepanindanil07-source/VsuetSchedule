#!/usr/bin/env python3
"""
Собирает план этажа из базового кадра (extract.py -> out.json) и крупных кадров (tiles.py): геометрия комнат, контур и проходимые клетки
берутся из крупных кадров там, где они есть, из базового кадра в остальных местах. Результат в формате out.json (как у extract.py),
дальше to_ts.py.

Запуск: build_plan.py base_out.json reg.json tiles_dir out.json
  tiles_dir: tile{N}.npy (кадр в RGB) и ocr/o_*.json (подписи, ocr/ocr.swift); список кадров задан в TILES ниже.
"""
import importlib.util
import json
import math
import os
import re
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import tiles as T  # noqa: E402

spec = importlib.util.spec_from_file_location('ex', os.path.join(HERE, 'extract.py'))
ex = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ex)

TILES = [('4', 'o_4.webp.json'), ('5', 'o_5.webp.json'), ('6', 'o_6.png.json'), ('7', 'o_7.png.json'), ('8', 'o_8.webp.json')]
SERVICE_NAMES = ('туалет', 'гардероб')
W0, H0 = 2000, 1253  # размер базового кадра (система координат плана)
UP = 1  # растр сборки: масштаб базового кадра (координаты комнат при этом дробные)


OCR_FIX = {'153': '135'}


def label_items(path):
    d = json.load(open(path))
    return [(OCR_FIX.get(i['text'].strip(), i['text']), i['x'] + i['w'] / 2, i['y'] + i['h'] / 2) for i in d['items']]


def process_tile(n, ocr_file, reg, tiles_dir):
    rgb = np.load(os.path.join(tiles_dir, f'tile{n}.npy'))
    H, W = rgb.shape[:2]
    th = reg['theta']
    rot = T.rotate_image(rgb, th)
    c = T.classes(rot)
    S = 2 if min(H, W) > 700 else 1

    def sub(m):
        return m[::S, ::S]

    dark = sub(rot.astype(int).sum(2) < 360)
    room = sub(c['room'])
    room_text = dark & (ex.boxsum(room, 5) >= 12)
    rooms = room | room_text
    corridor = sub(c['corridor'])
    pink = sub(c['pink'])
    gray = sub(c['gray'])
    wall = sub(c['wall'])
    h, w = room.shape

    # подписи кадра в системе выпрямленного кадра, в долях уменьшенного растра
    items = [(t, *T.rot_point(x, y, W, H, th)) for t, x, y in label_items(os.path.join(tiles_dir, 'ocr', ocr_file))]
    numeric = [(t, x / S, y / S) for t, x, y in items if re.fullmatch(r'[0-9]{1,3}\s?[a-zа-яA-ZА-Я]?', t.strip()) and len(t.strip()) >= 2]
    named = [(t, x / S, y / S) for t, x, y in items if sum(ch.isalpha() for ch in t) >= 4]

    # комнаты и розовые залы
    lab, cnt = ex.components(rooms | pink)
    sizes = np.bincount(lab.ravel())
    min_area = 40 if S == 2 else 100
    valid = {i for i in range(1, cnt + 1) if sizes[i] >= min_area}

    def comp_at(x, y):
        xi, yi = int(round(x)), int(round(y))
        if 0 <= yi < h and 0 <= xi < w and lab[yi, xi] in valid:
            return lab[yi, xi]
        win = lab[max(0, yi - 6): yi + 7, max(0, xi - 6): xi + 7]
        vals, cnts = np.unique(win, return_counts=True)
        cand = [(cc, v) for v, cc in zip(vals, cnts) if v in valid]
        return max(cand)[1] if cand else 0

    owners = {}
    for t, x, y in numeric:
        cid = comp_at(x, y)
        if cid:
            owners.setdefault(cid, []).append((t, x, y))
    titles = {}
    for t, x, y in named:
        low = t.lower()
        if any(k in low for k in SERVICE_NAMES):
            continue
        cid = comp_at(x, y)
        if cid:
            titles.setdefault(cid, t)

    s, R, tvec = np.array(reg['s']), np.array(reg['R']), np.array(reg['t'])

    def to_base(px, py):  # из растра кадра (S) в систему базового кадра
        return T.apply(float(s), R, tvec, px * S, py * S)

    rooms_out = []
    for cid in valid:
        comp = lab == cid
        ys, xs = np.nonzero(comp)
        touches = xs.min() <= 1 or ys.min() <= 1 or xs.max() >= w - 2 or ys.max() >= h - 2
        refs = owners.get(cid, [])
        parts = []
        if len(refs) <= 1:
            parts.append((refs[0][0] if refs else '', xs, ys))
        else:
            axis = 0 if (max(r[1] for r in refs) - min(r[1] for r in refs)) >= (max(r[2] for r in refs) - min(r[2] for r in refs)) else 1
            order = sorted(range(len(refs)), key=lambda k: refs[k][1 + axis])
            cuts = [(refs[order[k]][1 + axis] + refs[order[k + 1]][1 + axis]) / 2 for k in range(len(order) - 1)]
            coord = xs if axis == 0 else ys
            for rank, k in enumerate(order):
                lo = cuts[rank - 1] if rank > 0 else -1e9
                hi = cuts[rank] if rank < len(cuts) else 1e9
                sel = (coord >= lo) & (coord < hi)
                if sel.any():
                    parts.append((refs[k][0], xs[sel], ys[sel]))
        for ref, pxs, pys in parts:
            part = np.zeros_like(comp)
            part[pys, pxs] = True
            corners = [[*to_base(x, y)] for x, y in ex.box_of(pxs, pys)]
            bx = [c_[0] for c_ in corners]
            by = [c_[1] for c_ in corners]
            base_poly = [[min(bx), min(by)], [max(bx), min(by)], [max(bx), max(by)], [min(bx), max(by)]]  # прямоугольник в системе плана
            rooms_out.append({
                'ref': ref,
                'title': titles.get(cid) if not ref else None,
                'polygon': [[round(x, 1), round(y, 1)] for x, y in base_poly],
                'touches': bool(touches),
                'area': int(len(pxs) * (S * float(s)) ** 2),
                'tile': n,
            })

    # служебные помещения (серые): туалеты, гардероб
    glab, gcnt = ex.components(gray)
    gsizes = np.bincount(glab.ravel())
    service = []
    for t, x, y in [(t, x / S, y / S) for t, x, y in items if any(k in t.lower() for k in SERVICE_NAMES)]:
        xi, yi = int(round(x)), int(round(y))
        win = glab[max(0, yi - 25): yi + 8, max(0, xi - 25): xi + 25]
        vals, cnts = np.unique(win, return_counts=True)
        cand = [(cc, v) for v, cc in zip(vals, cnts) if v and gsizes[v] < 60000]
        if cand:
            gid = max(cand)[1]
            gy, gx = np.nonzero(glab == gid)
            service.append(gid)
            poly = [[*to_base(x_, y_)] for x_, y_ in ex.box_of(gx, gy)]
            rooms_out.append({'ref': '', 'title': t.replace('Ж', 'Ж'), 'polygon': [[round(a, 1), round(b, 1)] for a, b in ex.manhattan(poly)], 'touches': False, 'area': int(len(gx) * (S * float(s)) ** 2), 'tile': n})

    # лестницы: яркие синие значки
    Rr, Gg, Bb = [rot[:, :, i].astype(int) for i in range(3)]
    stair = sub((Bb >= 200) & (Rr <= 90) & (Gg >= 100) & (Gg <= 190))
    slab, scnt = ex.components(ex.dilate(stair, 5))
    stairs = []
    for i in range(1, scnt + 1):
        sy, sx = np.nonzero((slab == i) & stair)
        if len(sx) >= 25:
            stairs.append(to_base(sx.mean(), sy.mean()))

    # растры тайла: footprint, коридоры, покрытие
    wall_near = wall & ex.dilate(rooms | corridor | pink, 5)
    svc_mask = np.isin(glab, service) if service else np.zeros_like(gray)
    foot = rooms | corridor | pink | wall_near | svc_mask
    foot = ex.erode(ex.dilate(foot, 5), 5) | foot  # закрываем щели у стен
    return dict(n=n, rooms=rooms_out, stairs=stairs, foot=foot, corridor=corridor, S=S, s=float(s), R=R, t=tvec, shape=(h, w), H=H, W=W)


def warp(mask, tile, out_shape, scale):
    """Переносит маску кадра (растр S) в растр базовой системы (масштаб scale), метод обратного отображения. Возвращает (маска, покрытие)."""
    oh, ow = out_shape
    s, R, t, S = tile['s'], tile['R'], tile['t'], tile['S']
    Rinv = np.linalg.inv(R)
    yy, xx = np.mgrid[0:oh, 0:ow]
    bx, by = xx / scale, yy / scale
    # base = s R p + t  ->  p = R^-1 (base - t) / s  (p в пикселях кадра), затем в растр S
    px = (Rinv[0, 0] * (bx - t[0]) + Rinv[0, 1] * (by - t[1])) / s / S
    py = (Rinv[1, 0] * (bx - t[0]) + Rinv[1, 1] * (by - t[1])) / s / S
    xi, yi = px.round().astype(int), py.round().astype(int)
    h, w = mask.shape
    cov = (xi >= 0) & (xi < w) & (yi >= 0) & (yi < h)
    out = np.zeros((oh, ow), dtype=bool)
    out[cov] = mask[yi[cov], xi[cov]]
    return out, cov


def main(base_path, reg_path, tiles_dir, out_path):
    base = json.load(open(base_path))
    reg = json.load(open(reg_path))
    results = []
    for n, ocr in TILES:
        if n not in reg:
            continue
        r = process_tile(n, ocr, reg[n], tiles_dir)
        print('tile', n, 'rooms', len(r['rooms']), 'stairs', len(r['stairs']))
        results.append(r)

    # ---- комнаты: из кадров, где они есть целиком; иначе из базового кадра
    best = {}
    for r in results:
        for room in r['rooms']:
            if not room['ref']:
                continue
            k = T.norm(room['ref'])
            cur = best.get(k)
            score = (not room['touches'], room['area'])
            # дубли номеров: оставляем несколько разных помещений
            best.setdefault(k, []).append((score, room))
    rooms = []
    for k, lst in best.items():
        by_tile = {}
        for score, room in lst:
            by_tile.setdefault(room['tile'], []).append((score, room))
        # кадр, где номер встречается чаще всего (повторы номеров это разные помещения), при равенстве лучший по качеству
        chosen = max(by_tile.values(), key=lambda v: (len(v), max(sc for sc, _ in v)))
        for score, room in chosen:
            rooms.append(room)
    # безымянные и названные помещения кадров
    for r in results:
        for room in r['rooms']:
            if room['ref'] == '':
                rooms.append(room)

    def center(poly):
        return np.mean([p[0] for p in poly]), np.mean([p[1] for p in poly])

    # убираем дубли безымянных из соседних кадров (перекрытие кадров)
    def bbox(poly):
        xs = [p[0] for p in poly]; ys = [p[1] for p in poly]
        return min(xs), min(ys), max(xs), max(ys)

    def overlap(a, b):
        ax0, ay0, ax1, ay1 = bbox(a); bx0, by0, bx1, by1 = bbox(b)
        w_ = min(ax1, bx1) - max(ax0, bx0); h_ = min(ay1, by1) - max(ay0, by0)
        return max(0, w_) * max(0, h_) / max(1, (ax1 - ax0) * (ay1 - ay0))

    named = [r for r in rooms if r['ref']]
    unnamed = sorted([r for r in rooms if not r['ref']], key=lambda r: (r['touches'], -r['area']))
    kept_un = []
    for r in unnamed:
        if any(overlap(r['polygon'], o['polygon']) > 0.5 or overlap(o['polygon'], r['polygon']) > 0.5 for o in kept_un + named):
            continue
        kept_un.append(r)
    rooms = named + kept_un

    # базовые комнаты вне покрытия кадров
    cov_total = np.zeros((int(H0 * UP), int(W0 * UP)), dtype=bool)
    foot_total = np.zeros_like(cov_total)
    walk_total = np.zeros_like(cov_total)
    for r in results:
        f, cv = warp(r['foot'], r, cov_total.shape, UP)
        c_, _ = warp(r['corridor'], r, cov_total.shape, UP)
        foot_total |= f
        walk_total |= c_
        cov_total |= cv

    def covered(poly):
        cx, cy = center(poly)
        xi, yi = int(cx * UP), int(cy * UP)
        return 0 <= yi < cov_total.shape[0] and 0 <= xi < cov_total.shape[1] and cov_total[yi, xi]

    base_named = {T.norm(r['ref']) for r in rooms if r['ref']}
    for br in base['rooms']:
        if covered(br['polygon']):
            continue
        rooms.append({'ref': br['ref'], 'title': br.get('title'), 'polygon': br['polygon'], 'touches': False, 'area': br.get('area', 0), 'tile': 'base'})

    # ---- контур: кадры там, где покрытие; базовый контур остальное
    base_foot = raster_loops(base['outline'], cov_total.shape, UP)
    final = foot_total | (base_foot & ~cov_total)
    # чистка: щели у стен закрываем, узкие выступы и мелкие пятна убираем
    final = ex.erode(ex.dilate(final, 5), 5) | final
    final = ex.dilate(ex.erode(final, 7), 7) & final | (final & ~ex.dilate(~final, 7))
    lab, cnt = ex.components(ex.dilate(final, 3))
    sizes = np.bincount(lab.ravel())
    keep = np.zeros_like(final)
    for i in range(1, cnt + 1):
        if sizes[i] >= 3000 * UP * UP:
            keep |= (lab == i)
    # посторонние блоки без плана (отдельные здания во дворе) в контур не входят: берём только крупнейшую связную часть
    lab2, cnt2 = ex.components(ex.dilate(final & keep, 3))
    named_centers = [center(r['polygon']) for r in rooms if r['ref']]
    count = np.zeros(cnt2 + 1, dtype=int)
    for cx, cy in named_centers:
        xi, yi = int(round(cx * UP)), int(round(cy * UP))
        if 0 <= yi < lab2.shape[0] and 0 <= xi < lab2.shape[1]:
            count[lab2[yi, xi]] += 1
    good = {i for i in range(1, cnt2 + 1) if count[i] >= 3}
    print('building parts kept', len(good), 'of', cnt2)
    final = final & keep & np.isin(lab2, list(good))
    outline = []
    for lp in ex.loops_of(final):
        if abs(ex.area(lp)) >= 1500 * UP * UP:
            outline.append([[x / UP, y / UP] for x, y in ex.manhattan([list(p) for p in ex.simplify(lp, 2.2 * UP)])])

    # ---- ручные правки после сверки с кадрами: manual.json в tiles_dir, прямоугольники в пикселях кадра
    manual_path = os.path.join(tiles_dir, 'manual.json')
    for m in (json.load(open(manual_path)) if os.path.exists(manual_path) else []):
        g = reg[m['tile']]
        H_, W_ = np.load(os.path.join(tiles_dir, f"tile{m['tile']}.npy"), mmap_mode='r').shape[:2]
        x0, y0, x1, y1 = m['rect']
        pts = [T.apply(g['s'], np.array(g['R']), np.array(g['t']), *T.rot_point(x, y, W_, H_, g['theta'])) for x, y in ((x0, y0), (x1, y0), (x1, y1), (x0, y1))]
        bx = [p_[0] for p_ in pts]; by = [p_[1] for p_ in pts]
        new = {'ref': m.get('ref', ''), 'title': m.get('title'), 'polygon': [[round(min(bx), 1), round(min(by), 1)], [round(max(bx), 1), round(min(by), 1)], [round(max(bx), 1), round(max(by), 1)], [round(min(bx), 1), round(max(by), 1)]], 'area': 0}
        # заменяемые: с тем же номером или названием, а также лежащие внутри новой рамки
        rooms = [r for r in rooms if not ((new['ref'] and T.norm(r['ref']) == T.norm(new['ref'])) or (new['title'] and r.get('title') == new['title']) or overlap(r['polygon'], new['polygon']) > 0.5)]
        rooms.append(new)
    for r in rooms:
        r['ref'] = T.norm(r['ref'])  # латинские двойники букв -> кириллица, без пробелов, строчные

    # ---- ровные стены: близкие координаты сводятся в одну, наложения соседних комнат разрезаются посередине
    straighten(rooms, outline)

    # ---- проходимые клетки: пол здания без комнат (комнаты теперь точные прямоугольники)
    cell = base.get('cell', 12)
    room_mask = np.zeros_like(final)
    for r in rooms:
        x0, y0, x1, y1 = bbox(r['polygon'])
        # комната чуть уже, чем есть: узкий проход между рядами не теряется из-за погрешности сшивки кадров
        room_mask[max(0, int(y0 * UP) + 3):int(y1 * UP) - 2, max(0, int(x0 * UP) + 3):int(x1 * UP) - 2] = True
    floor_mask = (final & ~room_mask) | (ex.erode(walk_total, 3) & final & ~room_mask)
    walk = []
    for y in range(0, H0 - cell, cell):
        for x in range(0, W0 - cell, cell):
            blk = floor_mask[y * UP:(y + cell) * UP, x * UP:(x + cell) * UP]
            if blk.size and blk.mean() > 0.55:
                walk.append([x + cell // 2, y + cell // 2])

    # ---- лестницы
    stairs = []
    for r in results:
        for st in r['stairs']:
            if not any(math.hypot(st[0] - o[0], st[1] - o[1]) < 25 for o in stairs):
                stairs.append(st)
    for st in base.get('stairs', []):
        xi, yi = int(st[0] * UP), int(st[1] * UP)
        if not cov_total[min(yi, cov_total.shape[0] - 1), min(xi, cov_total.shape[1] - 1)] and not any(math.hypot(st[0] - o[0], st[1] - o[1]) < 25 for o in stairs):
            stairs.append(st)

    def inside_footprint(poly):
        cx, cy = center(poly)
        xi, yi = int(round(cx * UP)), int(round(cy * UP))
        return 0 <= yi < final.shape[0] and 0 <= xi < final.shape[1] and bool(final[yi, xi])

    def dims(poly):
        x0, y0, x1, y1 = bbox(poly)
        return x1 - x0, y1 - y0

    def acceptable(r):
        w_, h_ = dims(r['polygon'])
        if any(p[0] < -2 or p[1] < -2 or p[0] > W0 + 2 or p[1] > H0 + 2 for p in r['polygon']) and not r.get('title'):
            return False
        if r['ref'] or r.get('title'):
            return True
        # безымянные: достаточно крупные и внутри контура здания
        return w_ >= 10 and h_ >= 10 and w_ * h_ >= 250 and inside_footprint(r['polygon'])

    rooms = [r for r in rooms if acceptable(r)]

    out = {
        'size': [W0, H0], 'rooms': [{k: v for k, v in r.items() if k in ('ref', 'title', 'polygon', 'area')} for r in rooms],
        'outline': outline, 'walkable': walk, 'cell': cell, 'theta': base['theta'],
        'stairs': [[round(a, 1), round(b, 1)] for a, b in stairs], 'extraCorridors': base.get('extraCorridors', []),
    }
    json.dump(out, open(out_path, 'w'), ensure_ascii=False)
    print('rooms', len(rooms), 'named', sum(1 for r in rooms if r['ref'] or r.get('title')), 'outline loops', len(outline), 'walkable', len(walk), 'stairs', len(stairs))

    # картинка для проверки
    sys.path.insert(0, '/tmp/plan')
    from png import write_png
    viz = np.full((H0 * UP, W0 * UP, 3), 235, dtype=np.uint8)
    viz[final] = (250, 250, 255)
    viz[ex.erode(walk_total, 3) & final] = (200, 235, 200)
    for r in rooms:
        pts = np.array(r['polygon'])
        x0, y0 = int(pts[:, 0].min() * UP), int(pts[:, 1].min() * UP)
        x1, y1 = int(pts[:, 0].max() * UP), int(pts[:, 1].max() * UP)
        col = (120, 160, 235) if r['ref'] else (200, 180, 230)
        x0, y0 = max(0, x0), max(0, y0)
        viz[y0:y1, x0:x1] = col
    write_png('/tmp/plan/viz_build.png', viz)


def snap_map(values, tol):
    """Каждое значение -> медиана своей группы (группа: значения не дальше tol от её первого)."""
    out, group = {}, []
    for v in sorted(set(values)):
        if group and v - group[0] > tol:
            m = float(np.median(group))
            out.update({g: m for g in group})
            group = []
        group.append(v)
    if group:
        m = float(np.median(group))
        out.update({g: m for g in group})
    return out


def straighten(rooms, outline, tol=7, tol_y=13, wall_tol=6):
    boxes = []
    for r in rooms:
        xs = [p[0] for p in r['polygon']]; ys = [p[1] for p in r['polygon']]
        boxes.append([min(xs), min(ys), max(xs), max(ys)])
    mx = snap_map([b[i] for b in boxes for i in (0, 2)], tol)
    my = snap_map([b[i] for b in boxes for i in (1, 3)], tol_y)  # ряды комнат вдоль коридора выравниваются по высоте
    for b in boxes:
        b[0], b[2] = mx[b[0]], mx[b[2]]
        b[1], b[3] = my[b[1]], my[b[3]]
    # разрезаем наложения: общая стена посередине по направлению меньшего перекрытия
    for _ in range(3):
        for i in range(len(boxes)):
            for j in range(i + 1, len(boxes)):
                a, b = boxes[i], boxes[j]
                ow = min(a[2], b[2]) - max(a[0], b[0])
                oh = min(a[3], b[3]) - max(a[1], b[1])
                if ow <= 0.5 or oh <= 0.5:
                    continue
                k = 0 if ow <= oh else 1
                first, second = (a, b) if a[k] + a[k + 2] <= b[k] + b[k + 2] else (b, a)
                mid = (max(first[k], second[k]) + min(first[k + 2], second[k + 2])) / 2
                if mid - first[k] >= 8 and second[k + 2] - mid >= 8:
                    first[k + 2] = mid
                    second[k] = mid
    for r, b in zip(rooms, boxes):
        x0, y0, x1, y1 = [round(v, 1) for v in b]
        r['polygon'] = [[x0, y0], [x1, y0], [x1, y1], [x0, y1]]
    # стены контура, лежащие у стен комнат, совмещаются с ними
    ex_x = sorted({v for b in boxes for v in (b[0], b[2])})
    ex_y = sorted({v for b in boxes for v in (b[1], b[3])})

    def near(v, arr):
        best = min(arr, key=lambda a: abs(a - v))
        return round(best, 1) if abs(best - v) <= wall_tol else v

    for lp in outline:
        for p in lp:
            p[0], p[1] = near(p[0], ex_x), near(p[1], ex_y)


def raster_loops(loops, shape, scale):
    """Чётно-нечётная заливка набора контуров (координаты в единицах базового кадра)."""
    h, w = shape
    cross = [[] for _ in range(h)]
    for lp in loops:
        n = len(lp)
        for i in range(n):
            x0, y0 = lp[i]; x1, y1 = lp[(i + 1) % n]
            if y0 == y1:
                continue
            if y0 > y1:
                x0, y0, x1, y1 = x1, y1, x0, y0
            for row in range(max(0, math.ceil(y0 * scale)), min(h, math.ceil(y1 * scale))):
                cross[row].append((x0 + (x1 - x0) * (row / scale - y0) / (y1 - y0)) * scale)
    out = np.zeros(shape, dtype=bool)
    for row, xs in enumerate(cross):
        xs.sort()
        for a, b in zip(xs[0::2], xs[1::2]):
            out[row, max(0, int(round(a))):min(w, int(round(b)))] = True
    return out


if __name__ == '__main__':
    main(*sys.argv[1:5])
