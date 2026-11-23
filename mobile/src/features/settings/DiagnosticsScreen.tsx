import { useEffect, useState } from 'react';
import { ScrollView, Share, StyleSheet, Text, View } from 'react-native';
import type { DiagnosticRecord, SourceMeta, SyncRecord } from '../../storage/appRepo';
import { Body, Button, Card, Title } from '../common/components';
import { useAppStore, useServices } from '../common/StoreContext';
import { useTheme } from '../common/theme';

/** Что приложение знает о себе: версия набора, файлы, журнал синхронизаций и сообщения парсеров. Всё это можно отправить текстом. */
export function DiagnosticsScreen() {
  const t = useTheme();
  const { app } = useServices();
  const dataset = useAppStore((s) => s.dataset);
  const revision = useAppStore((s) => s.revision);
  const syncing = useAppStore((s) => s.syncing);
  const [syncs, setSyncs] = useState<SyncRecord[]>([]);
  const [metas, setMetas] = useState<SourceMeta[]>([]);
  const [issues, setIssues] = useState<DiagnosticRecord[]>([]);

  useEffect(() => {
    void (async () => {
      const recent = await app.recentSyncs(5);
      setSyncs(recent);
      setMetas(await app.listSourceMeta());
      // сообщения последней синхронизации, которая что-то разбирала
      for (const s of recent) {
        const list = await app.diagnosticsOf(s.id);
        if (list.length > 0) {
          setIssues(list);
          return;
        }
      }
      setIssues([]);
    })();
  }, [app, revision, syncing]);

  const report = (): string => {
    const lines: string[] = ['Диагностика расписания ВГУИТ'];
    if (dataset) lines.push(`Набор: версия ${dataset.datasetVersion}, хеш ${dataset.contentHash.slice(0, 12)}, собран ${dataset.generatedAt}`);
    lines.push('', 'Файлы:');
    for (const m of metas) lines.push(`- ${m.sourceId}: ${m.entries ?? '?'} занятий, sha ${m.fileSha256?.slice(0, 10) ?? '-'}, парсер ${m.parserId}@${m.parserVersion}, проверен ${m.lastCheckedAt ?? '-'}`);
    lines.push('', 'Синхронизации:');
    for (const s of syncs) lines.push(`- #${s.id} ${s.startedAt} ${s.outcome}${s.message ? `: ${s.message}` : ''}`);
    lines.push('', `Сообщения парсеров (${issues.length}):`);
    for (const i of issues.slice(0, 200)) lines.push(`- [${i.severity}] ${i.sourceId} ${i.code}${i.sheet ? ` ${i.sheet}!${i.range ?? ''}` : ''}: ${i.message}${i.rawText ? ` | «${i.rawText.slice(0, 120)}»` : ''}`);
    return lines.join('\n');
  };

  const bySource = new Map<string, DiagnosticRecord[]>();
  for (const i of issues) bySource.set(i.sourceId, [...(bySource.get(i.sourceId) ?? []), i]);

  return (
    <ScrollView contentContainerStyle={styles.content} style={{ backgroundColor: t.background }}>
      <Title>Диагностика</Title>
      <Button kind="secondary" label="Отправить отчёт" onPress={() => void Share.share({ message: report() })} />

      <Card style={styles.card}>
        <Heading>Файлы</Heading>
        {metas.length === 0 && <Body muted>Файлы ещё не скачивались.</Body>}
        {metas.map((m) => (
          <Body key={m.sourceId}>
            {m.sourceId}: {m.entries ?? '?'} занятий{m.skipped ? `, пропущено ${m.skipped}` : ''}
            {'\n'}
            <Text style={{ color: t.muted, fontSize: 12 }}>
              sha {m.fileSha256?.slice(0, 10) ?? '-'} · парсер {m.parserId}@{m.parserVersion} · проверен {m.lastCheckedAt ?? '-'}
            </Text>
          </Body>
        ))}
      </Card>

      <Card style={styles.card}>
        <Heading>Последние синхронизации</Heading>
        {syncs.map((s) => (
          <Body key={s.id} muted>
            #{s.id} {s.startedAt.slice(0, 19).replace('T', ' ')} · {s.outcome}
            {s.message ? ` · ${s.message}` : ''}
          </Body>
        ))}
      </Card>

      <Card style={styles.card}>
        <Heading>{`Сообщения парсеров (${issues.length})`}</Heading>
        {[...bySource.entries()].map(([sourceId, list]) => (
          <View key={sourceId} style={{ gap: 4 }}>
            <Text style={{ color: t.text, fontWeight: '700' }}>{sourceId}: {list.length}</Text>
            {list.slice(0, 40).map((i, n) => (
              <Text key={n} style={{ color: i.severity === 'ERROR' ? t.errorText : t.muted, fontSize: 12, lineHeight: 17 }}>
                [{i.code}] {i.message}
                {i.range ? ` (${i.sheet ?? ''} ${i.range})` : ''}
              </Text>
            ))}
            {list.length > 40 && <Text style={{ color: t.muted, fontSize: 12 }}>…и ещё {list.length - 40}</Text>}
          </View>
        ))}
        {issues.length === 0 && <Body muted>Сообщений нет.</Body>}
      </Card>
    </ScrollView>
  );
}

function Heading({ children }: { children: string }) {
  const t = useTheme();
  return <Text style={{ color: t.muted, fontSize: 13, fontWeight: '700', textTransform: 'uppercase' }}>{children}</Text>;
}

const styles = StyleSheet.create({
  content: { padding: 16, gap: 14 },
  card: { gap: 8 },
});
