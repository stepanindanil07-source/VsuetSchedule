import { Pressable, StyleSheet, Text, View } from 'react-native';
import type { DayLesson, LessonPhase } from '../../domain/schedule';
import { lessonTypeLabel } from '../../domain/schedule';
import { extractRoomRefs, findRooms } from '../../plan/rooms';
import { ACTIVE_PLAN } from '../../plan/activePlan';
import { Card } from './components';
import { usePlanOpener } from './PlanContext';
import { useTheme } from './theme';

/** Карточка занятия: время, предмет, тип, преподаватель, аудитория, подгруппа и неоднозначности. */
export function LessonCard({ lesson, phase, showSubgroup }: { lesson: DayLesson; phase?: LessonPhase; showSubgroup: boolean }) {
  const t = useTheme();
  const planOpener = usePlanOpener();
  const e = lesson.entry;
  // аудитория, которую можно показать на плане (первая найденная из поля «аудитория»)
  const planRef = planOpener === null ? null : (extractRoomRefs(e.classroom).find((ref) => findRooms(ACTIVE_PLAN, ref).length > 0) ?? null);
  const typeText = lessonTypeLabel(e.lessonType) || (e.rawLessonType ?? '');
  const tags: string[] = [];
  if (typeText) tags.push(typeText);
  if (showSubgroup && e.subgroupNumber !== null) tags.push(`${e.subgroupNumber} подгруппа`);
  if (e.weekType === 'NUMERATOR' && e.specificDate === null) tags.push('числитель');
  if (e.weekType === 'DENOMINATOR' && e.specificDate === null) tags.push('знаменатель');
  const place = [e.classroom, e.teacher].filter((x): x is string => !!x).join(' · ');
  const highlighted = phase === 'NOW' || phase === 'NEXT';
  return (
    <Card style={[phase === 'PAST' && styles.past, highlighted && { borderColor: t.primary, borderWidth: 2 }]}>
      <View style={styles.row}>
        <View style={styles.time}>
          <Text style={[styles.start, { color: t.text }]}>{e.startTime}</Text>
          <Text style={{ color: t.muted, fontSize: 13 }}>{e.endTime}</Text>
        </View>
        <View style={styles.main}>
          {phase === 'NOW' && <Text style={[styles.badge, { color: t.primary }]}>Сейчас идёт</Text>}
          {phase === 'NEXT' && <Text style={[styles.badge, { color: t.primary }]}>Следующее</Text>}
          <Text style={[styles.subject, { color: t.text }]}>{e.subject}</Text>
          {tags.length > 0 && <Text style={{ color: t.muted, fontSize: 13 }}>{tags.join(' · ')}</Text>}
          {place !== '' && <Text style={{ color: t.text, fontSize: 14, marginTop: 2 }}>{place}</Text>}
          {planRef !== null && planOpener !== null && (
            <Pressable accessibilityRole="button" onPress={() => planOpener.openPlan(planRef)} style={{ alignSelf: 'flex-start', marginTop: 6 }}>
              <Text style={{ color: t.primary, fontWeight: '700', fontSize: 14 }}>На плане ›</Text>
            </Pressable>
          )}
          {lesson.weekUncertain && <Text style={{ color: t.warningText, fontSize: 12, marginTop: 4 }}>Тип недели неизвестен: занятие бывает не каждую неделю</Text>}
        </View>
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', gap: 14 },
  time: { width: 52 },
  start: { fontSize: 17, fontWeight: '700' },
  main: { flex: 1, gap: 2 },
  subject: { fontSize: 16, fontWeight: '600' },
  badge: { fontSize: 12, fontWeight: '700', textTransform: 'uppercase' },
  past: { opacity: 0.55 },
});
