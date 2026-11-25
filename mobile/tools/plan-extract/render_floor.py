#!/usr/bin/env python3
"""
Картинка этажа в том же виде, что в приложении, для сверки и пометок (PNG, macOS: qlmanage и sips).

Запуск: render_floor.py floor.json out.png [--ghost other_floor.json] [--ref reference_floor.json]
  --ghost  бледным пунктиром показать контур другого этажа (ориентир)
  --ref    взять охват картинки у этого этажа, чтобы разные этажи были в одном масштабе и положении
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


def main(argv):
    src, dst = argv[0], argv[1]
    opts = dict(zip(argv[2::2], argv[3::2]))
    f = json.load(open(src))
    ghost = json.load(open(opts['--ghost'])) if '--ghost' in opts else None
    frame = [f] + [json.load(open(opts[k])) for k in ('--ref', '--ghost') if k in opts]
    x0, y0, x1, y1 = bounds(frame)
    W, H = x1 - x0, y1 - y0
    svg = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{x0} {y0} {W} {W}" width="{SIDE}" height="{SIDE}"><rect x="{x0}" y="{y0}" width="{W}" height="{W}" fill="#E6EBF4"/>']
    if ghost:
        for lp in ghost.get('outline', []):
            svg.append(f'<path d="{path_of(lp)}" fill="none" stroke="#B4BDD4" stroke-width="2" stroke-dasharray="7 6"/>')
    for lp in f.get('outline', []):
        svg.append(f'<path d="{path_of(lp)}" fill="#F7F9FD" stroke="#6F7FA8" stroke-width="3.5" stroke-linejoin="round"/>')
    for r in f['rooms']:
        fill = '#E3DDF1' if not r['ref'] else '#D3DEF8'
        svg.append(f'<polygon points="{" ".join(f"{x},{y}" for x, y in r["polygon"])}" fill="{fill}" stroke="#7F90C0" stroke-width="1.5"/>')
    for (ax, ay), (bx, by) in f.get('walls', []):
        svg.append(f'<path d="M{ax} {ay}L{bx} {by}" stroke="#6F7FA8" stroke-width="3.5" stroke-linecap="square"/>')
    for sx, sy in f.get('stairs', []):
        svg.append(f'<path d="M{sx - 9} {sy + 9}H{sx - 3}V{sy + 3}H{sx + 3}V{sy - 3}H{sx + 9}V{sy - 9}" fill="none" stroke="#3B6FE6" stroke-width="3.5"/>')
    for r in f['rooms']:
        text = f"{r['ref']} {r['title']}" if r['ref'] and r.get('title') else (r['ref'] or r.get('title') or '')
        if not text:
            continue
        xs = [p[0] for p in r['polygon']]
        ys = [p[1] for p in r['polygon']]
        cx, cy = area_centroid(r['polygon'])
        w_, h_ = max(xs) - min(xs), max(ys) - min(ys)

        def fit(lines):
            return min(20, w_ * 0.92 / (max(len(line) for line in lines) * 0.58), h_ * 0.8 / (len(lines) * 1.15))

        words = text.split(' ')
        lines = words if len(words) == 2 and fit(words) > fit([text]) else [text]
        size = max(fit(lines), 7)
        for n, line in enumerate(lines):
            svg.append(f'<text x="{cx}" y="{cy + size / 3 + (n - (len(lines) - 1) / 2) * size * 1.15}" font-size="{size}" font-weight="bold" text-anchor="middle" font-family="Helvetica" fill="#26304A">{line}</text>')
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
