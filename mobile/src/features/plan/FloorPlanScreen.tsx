import { useEffect, useMemo, useRef, useState } from 'react';
import { PanResponder, StyleSheet, Text, View, type GestureResponderEvent, type HostInstance, type LayoutChangeEvent } from 'react-native';
import Svg, { G, Path, Polygon, Polyline, Text as SvgText } from 'react-native-svg';
import { ACTIVE_PLAN } from '../../plan/activePlan';
import { centroid, findRooms, pointInPolygon } from '../../plan/rooms';
import { buildGraph, routeBetween, type Route } from '../../plan/routing';
import type { PlanData, PlanRoom, Point } from '../../plan/types';
import { Banner, Body, Button } from '../common/components';
import { useTheme } from '../common/theme';

interface View2D {
  scale: number;
  tx: number;
  ty: number;
}

const MIN_SCALE_FACTOR = 0.8;

// цвета плана не зависят от темы: комнаты, коридоры и стены читаются одинаково днём и ночью
const PLAN_BACKGROUND = '#E6EBF4';
const WALL = '#6F7FA8';
const FLOOR = '#F7F9FD';
const MAX_SCALE = 6;

/**
 * План этажа: подсветка аудитории из расписания, указание «я здесь» нажатием на комнату и маршрут по коридорам.
 * Масштаб и сдвиг: двумя пальцами и одним, а также кнопками. Данные плана берутся из src/plan/activePlan.ts.
 */
export function FloorPlanScreen({ roomRef, plan = ACTIVE_PLAN }: { roomRef: string; plan?: PlanData }) {
  const t = useTheme();
  const hits = useMemo(() => findRooms(plan, roomRef), [plan, roomRef]);
  const hit = hits[0] ?? null;
  const floor = hit?.floor ?? plan.buildings[0].floors[0];
  const graph = useMemo(() => buildGraph(floor), [floor]);

  const [size, setSize] = useState({ width: 0, height: 0 });
  // границы занятой части плана: пустые края не тратят место на экране
  const bounds = useMemo(() => {
    const shapes = [...floor.rooms.map((r) => r.polygon), ...(floor.outline ?? [])];
    const xs = shapes.flatMap((poly) => poly.map((p) => p[0]));
    const ys = shapes.flatMap((poly) => poly.map((p) => p[1]));
    const pad = 40;
    return { x0: Math.min(...xs) - pad, y0: Math.min(...ys) - pad, x1: Math.max(...xs) + pad, y1: Math.max(...ys) + pad };
  }, [floor]);
  const boundsWidth = bounds.x1 - bounds.x0;
  const boundsHeight = bounds.y1 - bounds.y0;
  const fit = size.width === 0 ? 1 : Math.min(size.width / boundsWidth, size.height / boundsHeight);
  const [view, setView] = useState<View2D | null>(null);
  const current: View2D = view ?? {
    scale: fit,
    tx: (size.width - boundsWidth * fit) / 2 - bounds.x0 * fit,
    ty: (size.height - boundsHeight * fit) / 2 - bounds.y0 * fit,
  };
  const viewRef = useRef(current);
  viewRef.current = current;
  const [start, setStart] = useState<PlanRoom | null>(null);

  const targets = useMemo(() => hits.filter((h) => h.floor === floor).map((h) => h.room), [hits, floor]);
  const route: Route | null = useMemo(() => {
    if (start === null || targets.length === 0) return null;
    let best: Route | null = null;
    for (const target of targets) {
      const r = routeBetween(floor, start, target, graph);
      if (r !== null && (best === null || r.length < best.length)) best = r;
    }
    return best;
  }, [start, targets, floor, graph]);

  const container = useRef<HostInstance>(null);
  const gesture = useRef({ moved: 0, lastX: 0, lastY: 0, lastDistance: 0, touches: 0 });

  const onTap = (pageX: number, pageY: number): void => {
    container.current?.measureInWindow((ox, oy) => {
      const v = viewRef.current;
      const p: Point = [(pageX - ox - v.tx) / v.scale, (pageY - oy - v.ty) / v.scale];
      const tapped = floor.rooms.find((r) => r.ref !== '' && pointInPolygon(p, r.polygon));
      if (tapped) setStart((prev) => (prev === tapped ? null : tapped));
    });
  };

  const responder = useMemo(
    () =>
      PanResponder.create({
        onStartShouldSetPanResponder: () => true,
        onMoveShouldSetPanResponder: () => true,
        onPanResponderGrant: (e: GestureResponderEvent) => {
          const touches = e.nativeEvent.touches;
          gesture.current = { moved: 0, lastX: touches[0].pageX, lastY: touches[0].pageY, lastDistance: 0, touches: touches.length };
        },
        onPanResponderMove: (e: GestureResponderEvent) => {
          const touches = e.nativeEvent.touches;
          const g = gesture.current;
          if (touches.length >= 2) {
            const [a, b] = [touches[0], touches[1]];
            const distance = Math.hypot(a.pageX - b.pageX, a.pageY - b.pageY);
            const mx = (a.pageX + b.pageX) / 2;
            const my = (a.pageY + b.pageY) / 2;
            if (g.lastDistance > 0) {
              const factor = distance / g.lastDistance;
              container.current?.measureInWindow((ox, oy) => zoomAt(factor, mx - ox, my - oy));
            }
            g.lastDistance = distance;
            g.moved += 20;
            g.lastX = mx;
            g.lastY = my;
            return;
          }
          g.lastDistance = 0;
          const dx = touches[0].pageX - g.lastX;
          const dy = touches[0].pageY - g.lastY;
          g.moved += Math.abs(dx) + Math.abs(dy);
          g.lastX = touches[0].pageX;
          g.lastY = touches[0].pageY;
          setView((v) => ({ ...(v ?? viewRef.current), tx: (v ?? viewRef.current).tx + dx, ty: (v ?? viewRef.current).ty + dy }));
        },
        onPanResponderRelease: (e: GestureResponderEvent) => {
          const g = gesture.current;
          if (g.moved < 10 && g.touches === 1) onTap(e.nativeEvent.pageX, e.nativeEvent.pageY);
        },
      }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [floor],
  );

  /** Масштаб вокруг точки экрана (x, y в системе контейнера). */
  function zoomAt(factor: number, x: number, y: number): void {
    setView((v) => {
      const c = v ?? viewRef.current;
      const scale = Math.min(MAX_SCALE * fit, Math.max(fit * MIN_SCALE_FACTOR, c.scale * factor));
      const k = scale / c.scale;
      return { scale, tx: x - (x - c.tx) * k, ty: y - (y - c.ty) * k };
    });
  }

  const centerOnTarget = (): void => {
    if (targets.length === 0) return;
    const [cx, cy] = centroid(targets[0].polygon);
    const scale = fit * 2.2;
    setView({ scale, tx: size.width / 2 - cx * scale, ty: size.height / 2 - cy * scale });
  };

  // при открытии из расписания план сразу приближен к нужной аудитории (один раз); «Весь этаж» возвращает общий вид
  const focused = useRef(false);
  useEffect(() => {
    if (focused.current || size.width === 0 || targets.length === 0) return;
    focused.current = true;
    centerOnTarget();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [size.width, size.height, targets.length]);

  const onLayout = (e: LayoutChangeEvent): void => setSize({ width: e.nativeEvent.layout.width, height: e.nativeEvent.layout.height });

  const fontSize = 22;

  return (
    <View style={[styles.screen, { backgroundColor: t.background }]}>
      <View style={styles.head}>
        <Text style={{ color: t.text, fontSize: 18, fontWeight: '700' }}>
          {hit ? `Аудитория ${hit.room.ref}` : `Аудитория ${roomRef}`}
        </Text>
        <Body muted>
          {hit ? `${hit.building.name}, ${hit.floor.title}` : 'На плане не найдена'}
          {hits.length > 1 ? `. На плане ${hits.length} помещения с таким номером, подсвечены все` : ''}
        </Body>
      </View>

      {hit?.approximate === true && <Banner tone="info">В расписании аудитория «{roomRef}», на плане найдена «{hit.room.ref}». Это вероятное совпадение: проверьте номер на двери.</Banner>}
      {targets.some((r) => r.access === 'UPPER_FLOOR') && <Banner>На 1 этаже в эту аудиторию прохода нет: попасть можно только со 2 этажа. Маршрут на этом этаже не строится.</Banner>}
      {hit === null && <Banner>Эта аудитория пока не размечена на плане. Сейчас размечен только первый этаж главного корпуса.</Banner>}

      <View ref={container} onLayout={onLayout} style={[styles.canvas, { borderColor: t.border, backgroundColor: PLAN_BACKGROUND }]} {...responder.panHandlers}>
        {size.width > 0 && (
          <Svg width={size.width} height={size.height}>
            <G transform={`translate(${current.tx} ${current.ty}) scale(${current.scale})`}>
              {/* контур здания: если стен нет в данных, приблизительный: комнаты и коридоры, расширенные сначала цветом стен, затем цветом пола */}
              {floor.outline
                ? <Path d={floor.outline.map((o) => `M${o.map((p) => p.join(' ')).join('L')}Z`).join('')} fill={FLOOR} fillRule="evenodd" stroke={WALL} strokeWidth={5} strokeLinejoin="round" />
                : (
                  <>
                    {floor.rooms.map((r, i) => (
                      <Polygon key={`w${i}`} points={r.polygon.map((p) => p.join(',')).join(' ')} fill={WALL} stroke={WALL} strokeWidth={46} strokeLinejoin="round" />
                    ))}
                    {floor.corridors.map((c, i) => (
                      <Polyline key={`wc${i}`} points={c.map((p) => p.join(',')).join(' ')} stroke={WALL} strokeWidth={96} strokeLinecap="round" strokeLinejoin="round" fill="none" />
                    ))}
                    {floor.rooms.map((r, i) => (
                      <Polygon key={`f${i}`} points={r.polygon.map((p) => p.join(',')).join(' ')} fill={FLOOR} stroke={FLOOR} strokeWidth={36} strokeLinejoin="round" />
                    ))}
                    {floor.corridors.map((c, i) => (
                      <Polyline key={`fc${i}`} points={c.map((p) => p.join(',')).join(' ')} stroke={FLOOR} strokeWidth={86} strokeLinecap="round" strokeLinejoin="round" fill="none" />
                    ))}
                  </>
                )}
              {floor.rooms.map((r, i) => {
                const isTarget = targets.includes(r);
                const isStart = r === start;
                return (
                  <Polygon
                    key={i}
                    points={r.polygon.map((p) => p.join(',')).join(' ')}
                    fill={isTarget ? '#FFB020' : isStart ? '#5BD38F' : r.ref === '' ? '#E3DDF1' : '#D3DEF8'}
                    stroke="#7F90C0"
                    strokeWidth={2}
                  />
                );
              })}
              {floor.walls?.map(([a, b], i) => (
                <Polyline key={`iw${i}`} points={`${a.join(',')} ${b.join(',')}`} stroke={WALL} strokeWidth={5} strokeLinecap="square" fill="none" />
              ))}
              {floor.stairs.map((s, i) => (
                // значок лестницы: ступеньки в квадрате 18×18, чтобы помещался в узкие ниши
                <Path key={`s${i}`} d={`M${s[0] - 9} ${s[1] + 9}H${s[0] - 3}V${s[1] + 3}H${s[0] + 3}V${s[1] - 3}H${s[0] + 9}V${s[1] - 9}`} stroke="#3B6FE6" strokeWidth={3.5} fill="none" strokeLinejoin="round" />
              ))}
              {floor.rooms.filter((r) => r.ref !== '' || r.title).map((r, i) => {
                const [cx, cy] = centroid(r.polygon);
                const xs = r.polygon.map((p) => p[0]);
                const ys = r.polygon.map((p) => p[1]);
                const width = Math.max(...xs) - Math.min(...xs);
                const height = Math.max(...ys) - Math.min(...ys);
                // подпись вписывается в комнату: размер по ширине и высоте, слишком мелкие не рисуются
                const fit = (lines: string[]): number =>
                  Math.min(fontSize, (width * 0.92) / (Math.max(1, ...lines.map((l) => l.length)) * 0.58), (height * 0.8) / (lines.length * 1.15));
                // номер с названием («101 Медпункт»), если помещается; название из двух слов в узкой комнате переносится
                const full = r.ref && r.title ? `${r.ref} ${r.title}` : r.ref || (r.title ?? '');
                const words = full.split(' ');
                let lines = words.length === 2 && fit(words) > fit([full]) ? words : [full];
                if (r.ref && r.title && fit(lines) < 9) lines = [r.ref];
                const size = fit(lines);
                if (size < 8) return null;
                return lines.map((line, n) => (
                  <SvgText key={`t${i}-${n}`} x={cx} y={cy + size / 3 + (n - (lines.length - 1) / 2) * size * 1.15} fontSize={size} fill="#26304A" textAnchor="middle" fontWeight="600">
                    {line}
                  </SvgText>
                ));
              })}
              {route && <Polyline points={route.points.map((p) => p.join(',')).join(' ')} stroke="#E5391F" strokeWidth={7} strokeLinejoin="round" strokeLinecap="round" fill="none" />}
            </G>
          </Svg>
        )}
      </View>

      <View style={styles.controls}>
        <Button kind="secondary" label="+" onPress={() => zoomAt(1.5, size.width / 2, size.height / 2)} />
        <Button kind="secondary" label="−" onPress={() => zoomAt(1 / 1.5, size.width / 2, size.height / 2)} />
        <Button kind="secondary" label="К аудитории" onPress={centerOnTarget} disabled={targets.length === 0} />
        <Button kind="secondary" label="Весь этаж" onPress={() => setView(null)} />
      </View>

      <Body muted>
        {start === null
          ? 'Нажмите на комнату, где вы находитесь, и появится маршрут до аудитории (зелёная это вы, оранжевая цель).'
          : route === null
            ? targets.some((r) => r.access !== undefined) || start.access !== undefined
              ? 'Маршрут на этом этаже не строится: в северо-восточное крыло можно попасть только со 2 этажа.'
              : `Из ${start.ref} до аудитории маршрут не построен.`
            : `Маршрут от ${start.ref}. Схематично: масштаба в метрах на плане нет.`}
      </Body>
      <Text style={{ color: t.muted, fontSize: 11 }}>{plan.provenance}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, padding: 12, gap: 8 },
  head: { gap: 2 },
  canvas: { flex: 1, borderWidth: StyleSheet.hairlineWidth, borderRadius: 12, overflow: 'hidden' },
  controls: { flexDirection: 'row', gap: 6, flexWrap: 'wrap' },
});
