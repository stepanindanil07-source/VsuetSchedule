#!/usr/bin/env python3
"""
Сшивка крупных кадров плана (разные повороты и масштабы) в общую систему координат базового кадра.

Для каждого кадра: классы цветов (комнаты, коридоры, стены, серые блоки, фон) -> выпрямление поворотом -> подписи (OCR, ocr/ocr.swift)
-> подобие (поворот, масштаб, сдвиг) по общим номерам аудиторий методом наименьших квадратов (Умеяма) с отбраковкой выбросов.

Использование как библиотеки: tiles.load_tile(...) / tiles.register(...). Запускать из каталога с данными (см. build_plan.py).
"""
import json
import math
import re

import numpy as np

LATIN = {'a': 'а', 'b': 'в', 'c': 'с', 'e': 'е', 'h': 'н', 'k': 'к', 'm': 'м', 'o': 'о', 'p': 'р', 't': 'т', 'x': 'х', 'y': 'у'}


def norm(text):
    t = ''.join(LATIN.get(ch, ch) for ch in text.lower())
    return re.sub(r'[^0-9а-яё]', '', t)


def classes(rgb):
    """Цветовые классы кадра (карта 2ГИС в плоском виде)."""
    R, G, B = [rgb[:, :, i].astype(int) for i in range(3)]
    gray = (abs(R - G) <= 3) & (abs(G - B) <= 3) & (R >= 190) & (R <= 228)
    cream = (B - R <= -5) & (R >= 240) & (G >= 240)
    green = (G - B >= 25) & (G >= 215)
    pink = (R - B >= 12) & (R >= 240) & (G < 240)
    room = (B - R >= 9) & (B >= 238) & (G >= 225)
    # коридоры светло-сиреневые/белые, стены между комнатами чисто белые, тонкие
    corridor = (R >= 236) & (G >= 238) & (B >= 244) & (B - R >= 2) & (B - R <= 12) & ~room
    wall = (R >= 252) & (G >= 252) & (B >= 252)
    return dict(gray=gray, cream=cream, green=green, pink=pink, room=room, corridor=corridor, wall=wall)


def rotate_image(rgb, theta):
    H, W = rgb.shape[:2]
    t = np.radians(theta)
    c, s_ = np.cos(t), np.sin(t)
    yy, xx = np.mgrid[0:H, 0:W]
    dx, dy = xx - W / 2, yy - H / 2
    sx = (c * dx - s_ * dy + W / 2).round().astype(int)
    sy = (s_ * dx + c * dy + H / 2).round().astype(int)
    ok = (sx >= 0) & (sx < W) & (sy >= 0) & (sy < H)
    out = np.zeros_like(rgb)
    out[ok] = rgb[sy[ok], sx[ok]]
    return out


def rot_point(x, y, W, H, theta):
    t = np.radians(theta)
    c, s_ = float(np.cos(t)), float(np.sin(t))
    dx, dy = x - W / 2, y - H / 2
    return c * dx + s_ * dy + W / 2, -s_ * dx + c * dy + H / 2


def estimate_theta(mask, span=8.0, step=0.1):
    """Угол, при котором границы области становятся горизонтальными и вертикальными (максимум резкости профилей)."""
    edge = mask & ~(np.roll(mask, 1, 0) & np.roll(mask, -1, 0) & np.roll(mask, 1, 1) & np.roll(mask, -1, 1))
    ys, xs = np.nonzero(edge)
    best, best_score = 0.0, -1
    for theta in np.arange(-span, span + 1e-9, step):
        t = np.radians(theta)
        yp = ys * np.cos(t) - xs * np.sin(t)
        xp = xs * np.cos(t) + ys * np.sin(t)
        sc = (np.bincount(np.round(yp - yp.min()).astype(int)).astype(float) ** 2).sum() + (np.bincount(np.round(xp - xp.min()).astype(int)).astype(float) ** 2).sum()
        if sc > best_score:
            best, best_score = float(theta), sc
    return best


def umeyama(P, Q):
    """Подобие Q ≈ s·R·P + t по наименьшим квадратам. Возвращает (s, R(2x2), t)."""
    P, Q = np.asarray(P, float), np.asarray(Q, float)
    mp, mq = P.mean(0), Q.mean(0)
    Pc, Qc = P - mp, Q - mq
    cov = Qc.T @ Pc / len(P)
    U, D, Vt = np.linalg.svd(cov)
    S = np.eye(2)
    if np.linalg.det(U) * np.linalg.det(Vt) < 0:
        S[1, 1] = -1
    R = U @ S @ Vt
    var = (Pc ** 2).sum() / len(P)
    s = (D * np.diag(S)).sum() / var
    t = mq - s * R @ mp
    return s, R, t


def register(tile_labels, base_labels, iterations=6, gate=60):
    """
    tile_labels, base_labels: списки (текст, x, y). Совпадения по нормализованному тексту; повторы (одинаковые номера) подбираются по ближайшему
    после первой оценки. Возвращает (s, R, t, пары, rms) или None.
    """
    def group(lst):
        d = {}
        for text, x, y in lst:
            k = norm(text)
            if k:
                d.setdefault(k, []).append((x, y))
        return d

    tg, bg = group(tile_labels), group(base_labels)
    keys = [k for k in tg if k in bg]
    uniq = [k for k in keys if len(tg[k]) == 1 and len(bg[k]) == 1]
    if len(uniq) < 3:
        return None
    pairs = [(tg[k][0], bg[k][0], k) for k in uniq]
    for _ in range(iterations):
        P = [p[0] for p in pairs]
        Q = [p[1] for p in pairs]
        s, R, t = umeyama(P, Q)
        # отбраковка выбросов по остатку
        res = [np.hypot(*(s * R @ np.array(p) + t - np.array(q))) for p, q, _ in pairs]
        med = float(np.median(res))
        keep = [pr for pr, r in zip(pairs, res) if r <= max(3 * med, 12)]
        if len(keep) >= 3:
            pairs = keep
        # добавляем повторы и остальные совпадения по ближайшему
        s, R, t = umeyama([p[0] for p in pairs], [p[1] for p in pairs])
        have = {p[2] for p in pairs}
        extra = []
        for k in keys:
            if k in have:
                continue
            for tp in tg[k]:
                proj = s * R @ np.array(tp) + t
                cand = min(bg[k], key=lambda q: np.hypot(*(proj - np.array(q))))
                if np.hypot(*(proj - np.array(cand))) <= gate:
                    extra.append((tp, cand, k))
        pairs = pairs + extra if len(extra) else pairs
    s, R, t = umeyama([p[0] for p in pairs], [p[1] for p in pairs])
    res = [np.hypot(*(s * R @ np.array(p) + t - np.array(q))) for p, q, _ in pairs]
    return s, R, t, pairs, float(np.sqrt(np.mean(np.square(res))))


def apply(s, R, t, x, y):
    v = s * R @ np.array([x, y]) + t
    return float(v[0]), float(v[1])


def load_labels(path, W=None, H=None):
    d = json.load(open(path))
    return [(i['text'], i['x'] + i['w'] / 2, i['y'] + i['h'] / 2) for i in d['items'] if re.search(r'[0-9]', i['text']) and len(i['text']) <= 5]
