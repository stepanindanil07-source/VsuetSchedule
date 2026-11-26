#!/usr/bin/env python3
"""
Картинка этажа в том же виде, что в приложении, для сверки и пометок (PNG, macOS: qlmanage и sips).

Запуск: render_floor.py floor.json out.png [--context context.json]
  --context  серые контуры здания и соседних построек ({"polygons": [...]}): рисуются под этажом и задают общий охват, поэтому
             все этажи получаются в одном масштабе и положении
Цвет помещения без номера берётся из исходника: поле tone ('gray' или 'pink'); без него служебный сиреневый.
"""
import importlib.util
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
spec_ = importlib.util.spec_from_file_location('ex', os.path.join(HERE, 'extract.py'))
ex = importlib.util.module_from_spec(spec_)
spec_.loader.exec_module(ex)

SIDE = 4000
GRAY_FILL = '#D6D8DE'
GRAY_LINE = '#BCC0CB'
PINK_FILL = '#F5D2D8'


def write_png(path, rgb):
    import struct
    import zlib
    h, w, _ = rgb.shape
    raw = b''.join(b'\x00' + rgb[y].tobytes() for y in range(h))

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)

    with open(path, 'wb') as out:
        out.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 6)) + chunk(b'IEND', b''))


def bounds(floors):
    pts = [p for f in floors for lp in f.get('outline', []) for p in lp] + [p for f in floors for r in f['rooms'] for p in r['polygon']]
    pad = 30
    return min(p[0] for p in pts) - pad, min(p[1] for p in pts) - pad, max(p[0] for p in pts) + pad, max(p[1] for p in pts) + pad


def path_of(loop):
    return 'M' + 'L'.join(f'{x} {y}' for x, y in loop) + 'Z'


def area_centroid(poly):
    """Центр масс фигуры: у непрямоугольных помещений подпись не уезжает к стене, как среднее по вершинам."""
    a = cx = cy = 0.0
    for i, (x0, y0) in enumerate(poly):
        x1, y1 = poly[(i + 1) % len(poly)]
        cross = x0 * y1 - x1 * y0
        a += cross
        cx += (x0 + x1) * cross
        cy += (y0 + y1) * cross
    if not a:
        return sum(p[0] for p in poly) / len(poly), sum(p[1] for p in poly) / len(poly)
    return cx / (3 * a), cy / (3 * a)


GLYPH = 0.6  # средняя ширина знака в долях кегля (жирный шрифт)


def wrap(text, chars):
    """Перенос по словам: строки не длиннее chars знаков; None, если какое-то слово длиннее строки."""
    lines = ['']
    for word in text.split():
        if len(word) > chars:
            return None
        candidate = (lines[-1] + ' ' + word).strip()
        if len(candidate) <= chars:
            lines[-1] = candidate
        else:
            lines.append(word)
    return lines


def fit_block(text, width, height, largest, smallest):
    """Наибольший кегль, при котором текст с переносами помещается в прямоугольник: (кегль, строки) или None."""
    size = largest
    while size >= smallest:
        lines = wrap(text, int(width * 0.94 / (size * GLYPH)))
        if lines and len(lines) * size * 1.12 <= height * 0.94:
            return size, lines
        size -= 0.5
    return None


def label_svg(room):
    """Подпись помещения: крупный номер и под ним название; в узком высоком помещении название идёт вертикально."""
    ref, title = room['ref'], room.get('title') or ''
    if not ref and not title:
        return []
    xs = [p[0] for p in room['polygon']]
    ys = [p[1] for p in room['polygon']]
    cx, cy = area_centroid(room['polygon'])
    w_, h_ = max(xs) - min(xs), max(ys) - min(ys)

    def text(x, y, size, value, weight='bold', turn=False):
        rotate = f' transform="rotate(-90 {x} {y - size / 3})"' if turn else ''
        return f'<text x="{x}" y="{y}" font-size="{size}" font-weight="{weight}" text-anchor="middle" font-family="Helvetica" fill="#26304A"{rotate}>{value}</text>'

    def block(center_y, size, lines, weight):
        top = center_y - len(lines) * size * 1.12 / 2
        return [text(cx, top + size * (0.9 + 1.12 * n), size, line, weight) for n, line in enumerate(lines)]

    if ref and not title:
        size = max(7, min(20, w_ * 0.92 / (len(ref) * 0.58), h_ * 0.7))
        return [text(cx, cy + size / 3, size, ref)]
    if title and not ref:
        largest = 14 if room.get('office') else 20  # кабинеты без номера подписываются мельче служебных помещений
        flat = fit_block(title, w_, h_, largest, 8)
        # вертикально только там, где в строку не помещается читаемо
        upright = fit_block(title, h_, w_, largest, 8) if h_ > w_ * 1.3 and (not flat or flat[0] < 9) else None
        if upright and (not flat or upright[0] > flat[0]):
            size, lines = upright
            return [text(cx, cy + size / 3 + (n - (len(lines) - 1) / 2) * size * 1.12, size, line, turn=True) for n, line in enumerate(lines)] if len(lines) == 1 else \
                   [f'<g transform="rotate(-90 {cx} {cy})">' + ''.join(block(cy, size, lines, 'bold')) + '</g>']
        size, lines = flat or (7, [title])
        return block(cy, size, lines, 'bold')
    number = min(19, w_ * 0.9 / (len(ref) * 0.58), h_ * 0.34)
    rest = fit_block(title, w_, h_ - number * 1.25, 13, 3.5)
    if not rest:
        return [text(cx, cy + number / 3, number, ref)]
    size, lines = rest
    total = number * 1.15 + len(lines) * size * 1.12
    top = cy - total / 2
    return [text(cx, top + number * 0.92, number, ref)] + block(top + number * 1.15 + len(lines) * size * 1.12 / 2, size, lines, 'normal')


def main(argv):
    src, dst = argv[0], argv[1]
    opts = dict(zip(argv[2::2], argv[3::2]))
    f = json.load(open(src))
    context = json.load(open(opts['--context']))['polygons'] if '--context' in opts else []
    x0, y0, x1, y1 = bounds([f] if not context else [{'rooms': [], 'outline': context}])
    W, H = x1 - x0, y1 - y0
    svg = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{x0} {y0} {W} {W}" width="{SIDE}" height="{SIDE}"><rect x="{x0}" y="{y0}" width="{W}" height="{W}" fill="#E6EBF4"/>']
    for lp in context:
        svg.append(f'<path d="{path_of(lp)}" fill="{GRAY_FILL}" stroke="{GRAY_LINE}" stroke-width="3" stroke-linejoin="round"/>')
    # все контуры этажа одним путём с чётно-нечётной заливкой (как в приложении): внутренние вырезы остаются вырезами
    shape = ''.join(path_of(lp) for lp in f.get('outline', []))
    if shape:
        svg.append(f'<path d="{shape}" fill="#F7F9FD" fill-rule="evenodd" stroke="#6F7FA8" stroke-width="3.5" stroke-linejoin="round"/>')
    for r in f['rooms']:
        if r.get('tone') == 'gray':
            fill, line = GRAY_FILL, '#9BA1B3'
        elif r.get('tone') == 'pink':
            fill, line = PINK_FILL, '#7F90C0'
        elif r['ref'] or r.get('office'):
            fill, line = '#D3DEF8', '#7F90C0'
        else:
            fill, line = '#E3DDF1', '#7F90C0'
        svg.append(f'<polygon points="{" ".join(f"{x},{y}" for x, y in r["polygon"])}" fill="{fill}" stroke="{line}" stroke-width="1.5"/>')
    for (ax, ay), (bx, by) in f.get('walls', []):
        svg.append(f'<path d="M{ax} {ay}L{bx} {by}" stroke="#6F7FA8" stroke-width="3.5" stroke-linecap="square"/>')
    for sx, sy in f.get('stairs', []):
        svg.append(f'<path d="M{sx - 9} {sy + 9}H{sx - 3}V{sy + 3}H{sx + 3}V{sy - 3}H{sx + 9}V{sy - 9}" fill="none" stroke="#3B6FE6" stroke-width="3.5"/>')
    for r in f['rooms']:
        svg.extend(label_svg(r))
    svg.append(f'<text x="{x0 + W / 2}" y="{y0 + H - 14}" font-size="26" font-weight="bold" text-anchor="middle" font-family="Helvetica" fill="#6F7FA8">{f["title"]}</text>')
    svg.append('</svg>')

    with tempfile.TemporaryDirectory() as tmp:
        svg_path = os.path.join(tmp, 'floor.svg')
        open(svg_path, 'w').write('\n'.join(svg))
        subprocess.run(['qlmanage', '-t', '-s', str(SIDE), '-o', tmp, svg_path], capture_output=True, check=True)
        bmp = os.path.join(tmp, 'floor.bmp')
        subprocess.run(['sips', '-s', 'format', 'bmp', svg_path + '.png', '--out', bmp], capture_output=True, check=True)
        rgb = ex.read_bmp(bmp)
        crop = int(round(rgb.shape[1] * H / W))
        write_png(dst, rgb[:crop].astype('uint8'))
    print(dst, rgb.shape[1], crop)


if __name__ == '__main__':
    main(sys.argv[1:])
