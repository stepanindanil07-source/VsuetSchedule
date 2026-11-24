#!/usr/bin/env python3
"""
Извлекает геометрию этажа из растрового изображения плана (цветной схемы): контур здания, помещения, проходимые клетки коридоров.

Нужен для ПРОТОТИПА: превращает картинку в данные формата mobile/src/plan/types.ts. Для выпуска планы берутся у университета.
Только numpy (без OpenCV и Pillow): изображение сначала конвертируется в BMP командой `sips -s format bmp in.webp --out out.bmp` (macOS).

Принцип: пол и стены здания окрашены иначе, чем фон (синевато-сиреневый против зелёного и тёмного); комнаты чуть «синее» коридоров;
перегородки светлее и тоньше. Контуры комнат обводятся по границам пикселей и упрощаются алгоритмом Дугласа-Пекера.

Запуск: extract.py floor.bmp labels.json out.json   (labels.json: [["105", x, y], ...] в пикселях исходного изображения)
"""
import collections
import json
import struct
import sys

import numpy as np

S = 2  # во сколько раз уменьшаем изображение для разметки компонент
THETA = 1.65  # на сколько градусов снимок повёрнут: горизонтали и вертикали стен наклонены одинаково (измерено по резкости линий)


def rotate_image(rgb, theta):
    """Выпрямляет изображение: поворот на theta градусов вокруг центра (обратное отображение, ближайший пиксель)."""
    H, W, _ = rgb.shape
    t = np.radians(theta)
    c, s_ = np.cos(t), np.sin(t)
    yy, xx = np.mgrid[0:H, 0:W]
    dx, dy = xx - W / 2, yy - H / 2
    # исходная точка = R^-1 (выходная - центр) + центр, где R = [[c, s], [-s, c]]
    sx = (c * dx - s_ * dy + W / 2).round().astype(int)
    sy = (s_ * dx + c * dy + H / 2).round().astype(int)
    ok = (sx >= 0) & (sx < W) & (sy >= 0) & (sy < H)
    out = np.zeros_like(rgb)
    out[ok] = rgb[sy[ok], sx[ok]]
    return out


def make_transform(W, H, theta):
    t = np.radians(theta)
    c, s_ = float(np.cos(t)), float(np.sin(t))

    def fwd(x, y):
        dx, dy = x - W / 2, y - H / 2
        return c * dx + s_ * dy + W / 2, -s_ * dx + c * dy + H / 2

    def inv(x, y):
        dx, dy = x - W / 2, y - H / 2
        return c * dx - s_ * dy + W / 2, s_ * dx + c * dy + H / 2

    return fwd, inv


def read_bmp(path):
    d = open(path, 'rb').read()
    off = struct.unpack('<I', d[10:14])[0]
    w, h = struct.unpack('<ii', d[18:26])
    bpp = struct.unpack('<H', d[28:30])[0]
    bytespp = bpp // 8
    rowsize = (w * bytespp + 3) // 4 * 4
    arr = np.frombuffer(d, dtype=np.uint8, offset=off).reshape(abs(h), rowsize)[:, : w * bytespp].reshape(abs(h), w, bytespp)
    if h > 0:
        arr = arr[::-1]
    return arr[:, :, [2, 1, 0]].astype(int)


def boxsum(mask, k):
    """Сумма значений маски в окне k×k (k нечётное)."""
    pad = k // 2
    m = np.pad(mask.astype(np.int32), pad)
    c = m.cumsum(0).cumsum(1)
    c = np.pad(c, ((1, 0), (1, 0)))
    h, w = mask.shape
    return c[k:k + h, k:k + w] - c[0:h, k:k + w] - c[k:k + h, 0:w] + c[0:h, 0:w]


def erode(mask, k):
    return boxsum(mask, k) == k * k


def dilate(mask, k):
    return boxsum(mask, k) > 0


def components(mask):
    """Связные области (4-связность): метки 1..n, 0 для фона."""
    h, w = mask.shape
    labels = np.zeros((h, w), dtype=np.int32)
    n = 0
    ys, xs = np.nonzero(mask)
    for y0, x0 in zip(ys, xs):
        if labels[y0, x0]:
            continue
        n += 1
        labels[y0, x0] = n
        queue = collections.deque([(y0, x0)])
        while queue:
            y, x = queue.popleft()
            for ny, nx in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
                if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not labels[ny, nx]:
                    labels[ny, nx] = n
                    queue.append((ny, nx))
    return labels, n


def loops_of(mask):
    """Контуры области по границам пикселей: список замкнутых ломаных (внешние и внутренние), вершины в координатах пикселей."""
    h, w = mask.shape
    p = np.pad(mask, 1)
    edges = {}
    ys, xs = np.nonzero(mask)
    for y, x in zip(ys.tolist(), xs.tolist()):
        py, px = y + 1, x + 1
        if not p[py - 1, px]:
            edges.setdefault((x, y), []).append((x + 1, y))
        if not p[py, px + 1]:
            edges.setdefault((x + 1, y), []).append((x + 1, y + 1))
        if not p[py + 1, px]:
            edges.setdefault((x + 1, y + 1), []).append((x, y + 1))
        if not p[py, px - 1]:
            edges.setdefault((x, y + 1), []).append((x, y))
    loops = []
    while edges:
        start = next(iter(edges))
        loop = [start]
        cur = start
        prev_dir = None
        while True:
            outs = edges[cur]
            if len(outs) > 1 and prev_dir is not None:
                # на перекрёстке поворачиваем так, чтобы не склеивать разные области
                def turn(o):
                    d = (o[0] - cur[0], o[1] - cur[1])
                    return prev_dir[0] * d[1] - prev_dir[1] * d[0]
                nxt = min(outs, key=turn)
            else:
                nxt = outs[0]
            outs.remove(nxt)
            if not outs:
                del edges[cur]
            prev_dir = (nxt[0] - cur[0], nxt[1] - cur[1])
            cur = nxt
            if cur == start:
                break
            loop.append(cur)
        loops.append(loop)
    return loops


def area(loop):
    return sum(loop[i][0] * loop[(i + 1) % len(loop)][1] - loop[(i + 1) % len(loop)][0] * loop[i][1] for i in range(len(loop))) / 2


def simplify(points, tol):
    """Дуглас-Пекер для замкнутой ломаной."""
    if len(points) < 4:
        return points
    pts = points + [points[0]]

    def dp(a, b, lo, hi):
        if hi <= lo + 1:
            return []
        ax, ay = pts[lo]
        bx, by = pts[hi]
        dx, dy = bx - ax, by - ay
        norm = (dx * dx + dy * dy) ** 0.5 or 1e-9
        best, idx = -1, -1
        for i in range(lo + 1, hi):
            d = abs(dy * (pts[i][0] - ax) - dx * (pts[i][1] - ay)) / norm
            if d > best:
                best, idx = d, i
        if best <= tol:
            return []
        return dp(a, idx, lo, idx) + [idx] + dp(idx, b, idx, hi)

    # разрезаем замкнутую ломаную по самой удалённой от начала точке
    far = max(range(len(points)), key=lambda i: (points[i][0] - points[0][0]) ** 2 + (points[i][1] - points[0][1]) ** 2)
    keep = sorted(set([0, far, len(points)] + dp(0, far, 0, far) + dp(far, len(points), far, len(points))))
    return [pts[i] for i in keep if i < len(points)]


def box_of(xs, ys):
    """Прямоугольник по границам области (в упрощённой схеме комнаты прямоугольные)."""
    x0, x1, y0, y1 = int(xs.min()), int(xs.max()) + 1, int(ys.min()), int(ys.max()) + 1
    return [[x0, y0], [x1, y0], [x1, y1], [x0, y1]]


def manhattan(points, ang_tol=11):
    """Выравнивает почти горизонтальные и почти вертикальные рёбра по осям; наклонные (стена под углом) остаются как есть."""
    import math

    def kind(a, b):
        ang = abs(math.degrees(math.atan2(b[1] - a[1], b[0] - a[0]))) % 180
        if ang < ang_tol or ang > 180 - ang_tol:
            return 'H'
        if abs(ang - 90) < ang_tol:
            return 'V'
        return 'D'

    pts = [list(p) for p in points]
    changed = True
    while changed and len(pts) > 3:
        changed = False
        n = len(pts)
        kinds = [kind(pts[i], pts[(i + 1) % n]) for i in range(n)]
        for i in range(n):
            if kinds[i - 1] == kinds[i] and kinds[i] in 'HV':
                del pts[i]
                changed = True
                break
    n = len(pts)
    if n < 3:
        return points
    kinds = [kind(pts[i], pts[(i + 1) % n]) for i in range(n)]
    ys, xs = {}, {}
    for i in range(n):
        a, b = pts[i], pts[(i + 1) % n]
        if kinds[i] == 'H':
            ys[i] = (a[1] + b[1]) / 2
        elif kinds[i] == 'V':
            xs[i] = (a[0] + b[0]) / 2
    out = []
    for i in range(n):
        x, y = pts[i]
        for e in (i - 1, i):  # рёбра, сходящиеся в вершине i
            e %= n
            if e in xs:
                x = xs[e]
            if e in ys:
                y = ys[e]
        out.append([x, y])
    return remove_spikes(out)


def remove_spikes(pts, short=26, side=48):
    """Убирает «усики» и «зубцы»: короткое ребро между двумя противоположными короткими рёбрами (значок или подпись у стены)."""
    import math

    def vec(a, b):
        return (b[0] - a[0], b[1] - a[1])

    changed = True
    while changed and len(pts) > 6:
        changed = False
        n = len(pts)
        for i in range(n):
            a, b, c, d = pts[i], pts[(i + 1) % n], pts[(i + 2) % n], pts[(i + 3) % n]
            e1, e2, e3 = vec(a, b), vec(b, c), vec(c, d)
            l1, l2, l3 = math.hypot(*e1), math.hypot(*e2), math.hypot(*e3)
            if l2 < short and l1 < side and l3 < side and e1[0] * e3[0] + e1[1] * e3[1] < 0:
                for idx in sorted([(i + 1) % n, (i + 2) % n], reverse=True):
                    del pts[idx]
                changed = True
                break
    return pts


def snap_polygon(poly, tol_area=0.4):
    """Привязывает многоугольник к осям; если форма сильно изменилась, берётся прямоугольник по границам."""
    snapped = manhattan(poly)
    a0, a1 = abs(area(poly)), abs(area(snapped))
    if len(snapped) < 4 or a0 == 0 or abs(a1 - a0) / a0 > tol_area:
        xs = [p[0] for p in poly]
        ys = [p[1] for p in poly]
        return [[min(xs), min(ys)], [max(xs), min(ys)], [max(xs), max(ys)], [min(xs), max(ys)]]
    return snapped


def main(image, labels_path, out_path):
    rgb = read_bmp(image)
    H, W, _ = rgb.shape
    fwd, inv = make_transform(W, H, THETA)
    rgb = rotate_image(rgb, THETA)
    sub = rgb[::S, ::S]
    R, G, B = sub[:, :, 0], sub[:, :, 1], sub[:, :, 2]
    h, w = R.shape

    footprint = (B - G > 25) & (R > 110) & (B > 150)
    dark = R < 110
    # тёмные пиксели подписей внутри здания относим к зданию, если вокруг в основном пол
    foot_text = dark & (boxsum(footprint, 7) >= 22)
    foot = footprint | foot_text

    rooms_raw = footprint & ((B - R) >= 43)
    room_text = dark & (boxsum(rooms_raw, 7) >= 22)
    rooms = rooms_raw | room_text

    # отдельные комнаты: области комнатного цвета, разделённые перегородками
    labels, n = components(rooms)
    sizes = np.bincount(labels.ravel())
    keep = [i for i in range(1, n + 1) if sizes[i] >= 60]
    print('room components', n, 'kept', len(keep))

    # здание: крупные связные области пола и стен (отбрасывает дорожки, кусты и мелкие пятна)
    fl, fn = components(dilate(foot, 3))
    fsizes = np.bincount(fl.ravel())
    foot_keep = np.zeros_like(foot)
    for i in range(1, fn + 1):
        if fsizes[i] >= 4000:
            ys, xs = np.nonzero(fl == i)
            print('building component', i, 'area', fsizes[i], 'bbox x', xs.min() * S, xs.max() * S, 'y', ys.min() * S, ys.max() * S)
            foot_keep |= (fl == i)
    foot_keep &= dilate(foot, 3)
    # убираем узкие выступы и мелкие впадины (значки, подписи, кусты у стены), стены остаются
    foot_keep = dilate(erode(foot_keep, 7), 7) & foot_keep | foot_keep & ~dilate(~foot_keep, 7)
    foot_keep = erode(dilate(foot_keep, 5), 5) | foot_keep

    # проходимые клетки: пол здания без комнат, без тонких перегородок
    corridor = foot_keep & ~dilate(rooms, 1)
    corridor = dilate(erode(corridor, 5), 5) & foot_keep & ~rooms

    # дополнительные данные (в координатах исходного снимка) переводим в координаты выпрямленного плана
    cfg = json.load(open(labels_path.replace('labels.json', 'extra.json'))) if labels_path.endswith('labels.json') else {}
    pt = lambda p: [round(v, 1) for v in fwd(p[0], p[1])]
    def straight_box(poly):
        q = [pt(p) for p in poly]
        xs_, ys_ = [a[0] for a in q], [a[1] for a in q]
        return [[min(xs_), min(ys_)], [max(xs_), min(ys_)], [max(xs_), max(ys_)], [min(xs_), max(ys_)]]

    extra_rooms = [{**e, 'polygon': straight_box(e['polygon'])} for e in cfg.get('extraRooms', [])]
    extra_corridors = [[pt(p) for p in line] for line in cfg.get('extraCorridors', [])]
    stairs = [pt(p) for p in cfg.get('stairs', [])]
    for e in extra_rooms:
        xs_ = [p[0] for p in e['polygon']]; ys_ = [p[1] for p in e['polygon']]
        corridor[int(min(ys_)) // S:int(max(ys_)) // S + 1, int(min(xs_)) // S:int(max(xs_)) // S + 1] = False

    out = {'size': [W, H], 'rooms': [], 'outline': [], 'walkable': corridor.astype(np.uint8).tolist() if False else None}

    room_polys = {}
    for i in keep:
        comp = labels == i
        lps = loops_of(comp)
        outer = max(lps, key=lambda l: abs(area(l)))
        ys, xs = np.nonzero(comp)
        poly = box_of(xs, ys)
        room_polys[i] = {'polygon': [[x * S, y * S] for x, y in poly], 'cx': float(xs.mean() * S), 'cy': float(ys.mean() * S), 'area': int(len(xs) * S * S)}

    labs = [[ref, *fwd(x, y)] for ref, x, y in json.load(open(labels_path))]
    assigned = collections.defaultdict(list)
    for ref, x, y in labs:
        xi, yi = int(x // S), int(y // S)
        lid = labels[yi, xi] if 0 <= yi < h and 0 <= xi < w else 0
        if lid == 0 or lid not in room_polys:
            win = labels[max(0, yi - 6): yi + 7, max(0, xi - 6): xi + 7]
            vals, cnts = np.unique(win, return_counts=True)
            cand = [(c, v) for v, c in zip(vals, cnts) if v in room_polys]
            lid = max(cand)[1] if cand else 0  # комната, которой в окне больше всего
        if lid:
            assigned[lid].append((ref, xi, yi))
        else:
            print('метка без комнаты', ref, x, y)

    rooms_out = []
    for i, rp in room_polys.items():
        refs = assigned.get(i, [])
        if len(refs) <= 1:
            rooms_out.append({'ref': refs[0][0] if refs else '', 'polygon': rp['polygon'], 'area': rp['area']})
            continue
        # слипшиеся комнаты (один ряд или столбец, перегородки не видны): режем область полосами посередине между подписями
        comp = labels == i
        ys, xs = np.nonzero(comp)
        axis = 0 if (max(r[1] for r in refs) - min(r[1] for r in refs)) >= (max(r[2] for r in refs) - min(r[2] for r in refs)) else 1
        order = sorted(range(len(refs)), key=lambda k: refs[k][1 + axis])
        cuts = [(refs[order[k]][1 + axis] + refs[order[k + 1]][1 + axis]) / 2 for k in range(len(order) - 1)]
        coord = xs if axis == 0 else ys
        for rank, k in enumerate(order):
            lo = cuts[rank - 1] if rank > 0 else -1e9
            hi = cuts[rank] if rank < len(cuts) else 1e9
            sel = (coord >= lo) & (coord < hi)
            if not sel.any():
                continue
            rooms_out.append({'ref': refs[k][0], 'polygon': [[x * S, y * S] for x, y in box_of(xs[sel], ys[sel])], 'area': int(sel.sum() * S * S)})
    # ручные границы для комнат, которые автоматика определила неверно (координаты исходного снимка)
    for fix in cfg.get('fixRooms', []):
        x0, y0, x1, y1 = fix['rect']
        box = straight_box([[x0, y0], [x1, y0], [x1, y1], [x0, y1]])
        rooms_out = [r for r in rooms_out if r['ref'] != fix['ref'] or fix['ref'] == '']
        rooms_out.append({'ref': fix['ref'], 'polygon': box, 'area': int((box[1][0] - box[0][0]) * (box[2][1] - box[1][1]))})

    fix_boxes = [r['polygon'] for r in rooms_out if r['ref'] in {f['ref'] for f in cfg.get('fixRooms', [])}]

    def overlap(a, b):
        ax = [p[0] for p in a]; ay = [p[1] for p in a]; bx = [p[0] for p in b]; by = [p[1] for p in b]
        w_ = min(max(ax), max(bx)) - max(min(ax), min(bx)); h_ = min(max(ay), max(by)) - max(min(ay), min(by))
        inter = max(0, w_) * max(0, h_)
        return inter / max(1, (max(ax) - min(ax)) * (max(ay) - min(ay)))

    rooms_out = [r for r in rooms_out if not (r['ref'] == '' and (any(overlap(r['polygon'], e['polygon']) > 0.4 for e in extra_rooms) or any(overlap(r['polygon'], b) > 0.3 for b in fix_boxes)))]
    for e in extra_rooms:
        rooms_out.append({'ref': e.get('ref', ''), 'title': e.get('title'), 'polygon': e['polygon'], 'area': 0})
    for r in rooms_out:
        r['polygon'] = [[round(x, 1), round(y, 1)] for x, y in r['polygon']]
    out['rooms'] = rooms_out

    # контуры здания (внешние и внутренние дворы), чётно-нечётная заливка
    for lp in loops_of(foot_keep):
        if abs(area(lp)) * S * S >= 3000:
            out['outline'].append([[x * S, y * S] for x, y in manhattan([list(p) for p in simplify(lp, 4.0)])])

    # проходимые клетки коридоров: шаг 12 пикселей, клетка проходима, если почти вся она коридор
    cell = 12
    walk = []
    for y in range(0, H - cell, cell):
        for x in range(0, W - cell, cell):
            block = corridor[y // S:(y + cell) // S, x // S:(x + cell) // S]
            if block.size and block.mean() > 0.9:
                walk.append([x + cell // 2, y + cell // 2])
    out['walkable'] = walk
    out['cell'] = cell
    out['theta'] = THETA
    out['stairs'] = stairs
    out['extraCorridors'] = extra_corridors
    json.dump(out, open(out_path, 'w'), ensure_ascii=False)
    print('rooms', len(rooms_out), 'labelled', sum(1 for r in rooms_out if r['ref']), 'outline loops', len(out['outline']), 'walkable cells', len(walk))

    # картинка для проверки: комнаты цветом, коридоры серым, контур
    sys.path.insert(0, '/tmp/plan')
    from png import write_png
    viz = np.zeros((h, w, 3), dtype=np.uint8)
    viz[foot_keep] = (225, 225, 225)
    viz[corridor] = (170, 200, 170)
    rng = np.random.RandomState(3)
    for i in keep:
        viz[labels == i] = rng.randint(60, 200, 3)
    write_png('/tmp/plan/viz.png', viz)


if __name__ == '__main__':
    main(*sys.argv[1:4])
