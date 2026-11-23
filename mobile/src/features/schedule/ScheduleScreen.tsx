import { useMemo, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { addDays, dayMonth, mondayOf, todayIso, weekdayName } from '../../domain/dates';
import { lessonsOn, weekDates } from '../../domain/schedule';
import { weekTypeLabel } from '../../domain/weeks';
import { Body, Card } from '../common/components';
import { LessonCard } from '../common/LessonCard';
import { useAppStore } from '../common/StoreContext';
import { useTheme } from '../common/theme';
import { WeekTypePrompt } from '../common/WeekTypePrompt';
import { useWeekResolver } from '../common/week';

/** «Расписание»: выбранная неделя целиком, переключение недель, тип недели в заголовке. */
export function ScheduleScreen() {
  const t = useTheme();
  const entries = useAppStore((s) => s.entries);
  const group = useAppStore((s) => s.group);
  const subgroup = useAppStore((s) => s.subgroup);
  const resolve = useWeekResolver();
  const [monday, setMonday] = useState(() => mondayOf(todayIso()));
  const today = todayIso();

  const days = useMemo(() => weekDates(monday), [monday]);
  const week = resolve(monday);

  return (
    <ScrollView contentContainerStyle={styles.content} style={{ backgroundColor: t.background }}>
      <Body muted>
        {group?.name}
        {subgroup !== null ? ` · ${subgroup} подгруппа` : ''}
      </Body>

      <View style={styles.nav}>
        <NavButton label="‹" hint="Предыдущая неделя" onPress={() => setMonday(addDays(monday, -7))} />
        <Pressable accessibilityRole="button" onPress={() => setMonday(mondayOf(today))} style={styles.navCenter}>
          <Text style={{ color: t.text, fontWeight: '700', fontSize: 16 }}>
            {dayMonth(days[0])} – {dayMonth(days[6])}
          </Text>
          <Text style={{ color: t.muted, fontSize: 13 }}>{weekTypeLabel(week.type)}{monday === mondayOf(today) ? ' · текущая неделя' : ' · к текущей'}</Text>
        </Pressable>
        <NavButton label="›" hint="Следующая неделя" onPress={() => setMonday(addDays(monday, 7))} />
      </View>

      {week.type === null && <WeekTypePrompt />}

      {days.map((date) => {
        const lessons = lessonsOn(entries, date, resolve(date).type, subgroup);
        const isToday = date === today;
        if (lessons.length === 0 && date > addDays(monday, 5)) return null; // воскресенье без занятий не показываем
        return (
          <View key={date} style={styles.day}>
            <Text style={{ color: isToday ? t.primary : t.text, fontWeight: '700', fontSize: 16 }}>
              {capitalize(weekdayName(days.indexOf(date) + 1))}, {dayMonth(date)}
              {isToday ? ' · сегодня' : ''}
            </Text>
            {lessons.length === 0 ? (
              <Card>
                <Body muted>Занятий нет</Body>
              </Card>
            ) : (
              lessons.map((l) => <LessonCard key={l.entry.id} lesson={l} showSubgroup={subgroup === null} />)
            )}
          </View>
        );
      })}
    </ScrollView>
  );
}

const capitalize = (text: string): string => text.charAt(0).toUpperCase() + text.slice(1);

function NavButton({ label, hint, onPress }: { label: string; hint: string; onPress: () => void }) {
  const t = useTheme();
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={hint} onPress={onPress} style={[styles.navButton, { backgroundColor: t.accent }]}>
      <Text style={{ color: t.primary, fontSize: 24, fontWeight: '700' }}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  content: { padding: 16, gap: 12 },
  nav: { flexDirection: 'row', alignItems: 'center', gap: 10, marginVertical: 4 },
  navButton: { width: 48, height: 48, borderRadius: 12, alignItems: 'center', justifyContent: 'center' },
  navCenter: { flex: 1, alignItems: 'center', minHeight: 48, justifyContent: 'center' },
  day: { gap: 8, marginTop: 6 },
});
