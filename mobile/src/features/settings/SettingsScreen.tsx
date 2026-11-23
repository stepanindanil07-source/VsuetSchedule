import { useEffect, useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { dayMonth, todayIso } from '../../domain/dates';
import { subgroupNumbers } from '../../domain/schedule';
import { weekTypeLabel } from '../../domain/weeks';
import type { UnitRow } from '../../storage/datasetRepo';
import { Banner, Body, Button, Card, Segmented } from '../common/components';
import { useAppStore, useServices, useStoreApi } from '../common/StoreContext';
import { useTheme } from '../common/theme';
import { useWeekResolver } from '../common/week';


export function SettingsScreen({ onChangeGroup, onDiagnostics }: { onChangeGroup: () => void; onDiagnostics: () => void }) {
  const t = useTheme();
  const store = useStoreApi();
  const { datasets } = useServices();
  const group = useAppStore((s) => s.group);
  const entries = useAppStore((s) => s.entries);
  const subgroup = useAppStore((s) => s.subgroup);
  const dataset = useAppStore((s) => s.dataset);
  const override = useAppStore((s) => s.weekOverride);
  const syncing = useAppStore((s) => s.syncing);
  const progress = useAppStore((s) => s.syncProgress);
  const last = useAppStore((s) => s.lastSync);
  const resolve = useWeekResolver();
  const [unit, setUnit] = useState<UnitRow | null>(null);

  useEffect(() => {
    if (group === null) return;
    void datasets.units().then((units) => setUnit(units.find((u) => u.id === group.unitId) ?? null));
  }, [datasets, group]);

  const week = resolve(todayIso());
  const subgroups = group === null ? [] : subgroupNumbers(entries, group.subgroupCount);
  const gaps = dataset?.manifest.coverage.knownGaps ?? [];

  return (
    <ScrollView contentContainerStyle={styles.content} style={{ backgroundColor: t.background }}>

      <Card style={styles.card}>
        <Heading>Группа</Heading>
        <Body>
          {group?.name ?? '—'}
          {group?.course ? ` · ${group.course} курс` : ''}
        </Body>
        {unit && <Body muted>{unit.name}</Body>}
        <Button kind="secondary" label="Изменить группу" onPress={onChangeGroup} />
        {subgroups.length > 0 && (
          <>
            <Heading>Подгруппа</Heading>
            <Segmented options={[{ label: 'Все', value: null as number | null }, ...subgroups.map((n) => ({ label: `${n}`, value: n as number | null }))]} value={subgroup} onChange={(v) => void store.getState().setSubgroup(v)} />
          </>
        )}
      </Card>

      <Card style={styles.card}>
        <Heading>Числитель и знаменатель</Heading>
        <Body>Сейчас: {weekTypeLabel(week.type)}{week.origin === 'MANUAL' ? ' (указано вручную)' : week.origin === 'CALENDAR' ? ' (по календарю семестра)' : ''}</Body>
        <Segmented
          options={[
            { label: 'Автоматически', value: 'AUTO' },
            { label: 'Сейчас числитель', value: 'NUMERATOR' },
            { label: 'Сейчас знаменатель', value: 'DENOMINATOR' },
          ]}
          value={override === null ? 'AUTO' : override.type}
          onChange={(v) => void store.getState().setWeekType(v === 'AUTO' ? null : v)}
        />
        <Body muted>Университет не публикует, какая неделя числитель. Календарь семестра введён вручную; если он не совпал с жизнью, укажите тип текущей недели: дальше недели будут чередоваться от неё.</Body>
      </Card>

      <Card style={styles.card}>
        <Heading>Обновление расписания</Heading>
        {dataset ? (
          <>
            <Body>Версия расписания: {dataset.datasetVersion}</Body>
            <Body muted>
              Период: {dayMonth(dataset.manifest.academicPeriod.startDate)} – {dayMonth(dataset.manifest.academicPeriod.endDate)} ({dataset.manifest.academicPeriod.academicYear}). Собрано {dataset.generatedAt.replace('T', ' ').replace('Z', ' UTC')}.
            </Body>
          </>
        ) : (
          <Body muted>Расписание ещё не загружено.</Body>
        )}
        {last && !syncing && <Body muted>Последняя проверка: {last.message}</Body>}
        {syncing && <Body muted>{progress ? `Идёт обновление: ${progress.completed}/${progress.total}` : 'Идёт обновление…'}</Body>}
        {last?.usedOfflineCopies && <Banner>Сайт университета недоступен или не ответил, показаны сохранённые на устройстве файлы.</Banner>}
        {last?.outcome === 'REJECTED' && <Banner>Новое расписание не принято (например, один из файлов не разобрался), поэтому показано прежнее. Подробности в диагностике.</Banner>}
        <Button testID="check-updates" label="Проверить обновления" onPress={() => void store.getState().sync(true)} busy={syncing} />
        {gaps.length > 0 && <Banner tone="info">Расписание охватывает не все файлы университета: не подключено {gaps.length}. Если вашей группы нет в списке, её расписания приложение пока не читает.</Banner>}
      </Card>

      <Card style={styles.card}>
        <Heading>Диагностика</Heading>
        <Body muted>Какие файлы скачаны, как разобраны и что парсеры не смогли прочитать. Можно отправить разработчику.</Body>
        <Button kind="secondary" label="Открыть диагностику" onPress={onDiagnostics} />
      </Card>

      <View style={styles.footer}>
        <Text style={{ color: t.muted, fontSize: 12, lineHeight: 17 }}>
          Приложение работает без сервера: файлы берутся напрямую с vsuet.ru. Если университет изменит формат файлов или адреса, потребуется обновление приложения.
        </Text>
      </View>
    </ScrollView>
  );
}

function Heading({ children }: { children: string }) {
  const t = useTheme();
  return <Text style={{ color: t.muted, fontSize: 13, fontWeight: '700', textTransform: 'uppercase', marginTop: 4 }}>{children}</Text>;
}

const styles = StyleSheet.create({
  content: { padding: 16, gap: 14 },
  card: { gap: 10 },
  footer: { paddingHorizontal: 4, paddingBottom: 24 },
});
