import { ScrollView, StyleSheet, View } from 'react-native';
import type { SyncProgress } from '../../sync/syncService';
import { Banner, Body, Button, Card, Title } from '../common/components';
import { useAppStore, useStoreApi } from '../common/StoreContext';
import { useTheme } from '../common/theme';

const STAGE_TEXT: Record<SyncProgress['stage'], string> = {
  CHECKING: 'Скачиваю файлы расписания с сайта университета',
  PARSING: 'Разбираю файлы',
  BUILDING: 'Собираю расписание',
  SAVING: 'Сохраняю на устройство',
};

/** Первый запуск: расписания на устройстве ещё нет. Загрузка и разбор показывают, что именно сейчас происходит. */
export function OnboardingScreen({ onShowDiagnostics }: { onShowDiagnostics: () => void }) {
  const t = useTheme();
  const store = useStoreApi();
  const syncing = useAppStore((s) => s.syncing);
  const progress = useAppStore((s) => s.syncProgress);
  const last = useAppStore((s) => s.lastSync);
  const failed = !syncing && last !== null && last.outcome !== 'PUBLISHED' && last.outcome !== 'UNCHANGED' && last.outcome !== 'UP_TO_DATE';

  return (
    <ScrollView contentContainerStyle={styles.content} style={{ backgroundColor: t.background }}>
      <Title>Расписание ВГУИТ</Title>
      <Body>
        Приложение скачивает расписание с сайта университета, разбирает его и сохраняет на устройстве. Дальше оно работает без интернета, а обновления проверяет само.
      </Body>

      <Card>
        <Body>Первая загрузка берёт около минуты и нужна один раз. Нужен интернет.</Body>
      </Card>

      {syncing && (
        <Card>
          <Body>{progress ? STAGE_TEXT[progress.stage] : 'Начинаю…'}</Body>
          {progress && progress.total > 1 && (
            <View style={[styles.bar, { backgroundColor: t.accent }]}>
              <View style={[styles.barFill, { backgroundColor: t.primary, width: `${Math.round((progress.completed / progress.total) * 100)}%` }]} />
            </View>
          )}
          {progress?.sourceName && <Body muted>{progress.sourceName}</Body>}
        </Card>
      )}

      {failed && last && (
        <View style={styles.gap}>
          <Banner>{last.message}</Banner>
          {last.sources.filter((s) => s.status === 'FAILED').map((s) => (
            <Body key={s.sourceId} muted>• {s.name}: {s.message}</Body>
          ))}
          <Button kind="secondary" label="Подробности для разработчика" onPress={onShowDiagnostics} />
        </View>
      )}

      <Button testID="load" label={failed ? 'Повторить' : 'Загрузить расписание'} onPress={() => void store.getState().sync(true)} busy={syncing} />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  content: { padding: 20, gap: 16, paddingTop: 64 },
  gap: { gap: 10 },
  bar: { height: 8, borderRadius: 4, overflow: 'hidden', marginVertical: 8 },
  barFill: { height: 8, borderRadius: 4 },
});
