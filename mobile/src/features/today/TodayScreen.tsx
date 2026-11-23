import { useEffect, useMemo, useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { dayMonth, longDate, minutesOfDay, todayIso, weekdayOf } from '../../domain/dates';
import { lessonsOn, nextLessonDay, phasesAt } from '../../domain/schedule';
import { weekTypeLabel } from '../../domain/weeks';
import { Banner, Body, Card } from '../common/components';
import { LessonCard } from '../common/LessonCard';
import { useAppStore } from '../common/StoreContext';
import { useTheme } from '../common/theme';
import { WeekTypePrompt } from '../common/WeekTypePrompt';
import { useWeekResolver } from '../common/week';

/** «Сегодня»: занятия на сегодня с подсветкой текущего и ближайшего. Время обновляется раз в минуту. */
export function TodayScreen() {
  const t = useTheme();
  const entries = useAppStore((s) => s.entries);
  const group = useAppStore((s) => s.group);
  const subgroup = useAppStore((s) => s.subgroup);
  const dataset = useAppStore((s) => s.dataset);
  const syncing = useAppStore((s) => s.syncing);
  const resolve = useWeekResolver();
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 60_000);
    return () => clearInterval(timer);
  }, []);

  const today = todayIso(now);
  const week = resolve(today);
  const lessons = useMemo(() => lessonsOn(entries, today, week.type, subgroup), [entries, today, week.type, subgroup]);
  const phases = phasesAt(lessons, minutesOfDay(now));
  const period = dataset?.manifest.academicPeriod;
  const outsidePeriod = period !== undefined && (today < period.startDate || today > period.endDate);
  const nextDay = useMemo(
    () => (lessons.length === 0 ? nextLessonDay(entries, today, (d) => resolve(d).type, subgroup) : null),
    [lessons.length, entries, today, resolve, subgroup],
  );

  return (
    <ScrollView contentContainerStyle={styles.content} style={{ backgroundColor: t.background }}>
      <View style={styles.header}>
          <Body muted>
          {longDate(today)} · {group?.name}
          {subgroup !== null ? ` · ${subgroup} подгруппа` : ''}
        </Body>
        <Body muted>Неделя: {weekTypeLabel(week.type)}</Body>
      </View>

      {week.type === null && <WeekTypePrompt />}
      {outsidePeriod && period && <Banner>Сегодня вне периода загруженного расписания ({dayMonth(period.startDate)} – {dayMonth(period.endDate)}). Занятия могут быть неактуальны.</Banner>}
      {syncing && <Banner tone="info">Проверяю обновления расписания…</Banner>}

      {lessons.length === 0 ? (
        <Card>
          <Body>{weekdayOf(today) === 7 ? 'Сегодня воскресенье, занятий нет.' : 'Сегодня занятий нет.'}</Body>
          {nextDay !== null && nextDay !== today && <Text style={{ color: t.muted, marginTop: 6 }}>Ближайшие занятия: {longDate(nextDay)}.</Text>}
        </Card>
      ) : (
        lessons.map((lesson, i) => <LessonCard key={lesson.entry.id} lesson={lesson} phase={phases[i]} showSubgroup={subgroup === null} />)
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  content: { padding: 16, gap: 12 },
  header: { gap: 2, marginBottom: 4 },
});
