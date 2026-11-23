import { useEffect, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { subgroupNumbers } from '../../domain/schedule';
import type { GroupRow, UnitRow } from '../../storage/datasetRepo';
import { Body, Button, Card, Segmented, Title } from '../common/components';
import { useServices, useStoreApi } from '../common/StoreContext';
import { useTheme } from '../common/theme';

/**
 * Выбор факультета, курса, группы и подгруппы. Данные читаются из локальной базы, интернет не нужен.
 * Курс «не определён» показывается отдельным пунктом: группа с неизвестным курсом не должна пропадать из выбора.
 */
export function GroupPicker({ onDone, initialGroupId }: { onDone: () => void; initialGroupId?: string | null }) {
  const t = useTheme();
  const { datasets } = useServices();
  const store = useStoreApi();
  const [units, setUnits] = useState<UnitRow[] | null>(null);
  const [unitId, setUnitId] = useState<string | null>(null);
  const [courses, setCourses] = useState<Array<number | null>>([]);
  const [course, setCourse] = useState<number | null | undefined>(undefined);
  const [groups, setGroups] = useState<GroupRow[]>([]);
  const [group, setGroup] = useState<GroupRow | null>(null);
  const [subgroups, setSubgroups] = useState<number[]>([]);
  const [subgroup, setSubgroup] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);
  const [unitsOpen, setUnitsOpen] = useState(true);

  useEffect(() => {
    void datasets.units().then(setUnits);
  }, [datasets]);

  // подсказка: открыть выбор на прежней группе
  useEffect(() => {
    if (!initialGroupId) return;
    void datasets.group(initialGroupId).then((g) => {
      if (g) {
        setUnitId(g.unitId);
        setCourse(g.course);
      }
    });
  }, [datasets, initialGroupId]);

  useEffect(() => {
    if (unitId === null) return;
    void datasets.courses(unitId).then(setCourses);
  }, [datasets, unitId]);

  useEffect(() => {
    if (unitId === null || course === undefined) return;
    void datasets.groups(unitId, course).then(setGroups);
  }, [datasets, unitId, course]);

  useEffect(() => {
    setSubgroup(null);
    if (group === null) {
      setSubgroups([]);
      return;
    }
    void datasets.entries(group.id).then((entries) => setSubgroups(subgroupNumbers(entries, group.subgroupCount)));
  }, [datasets, group]);

  if (units === null) return <ActivityIndicator style={{ marginTop: 40 }} />;

  const save = async (): Promise<void> => {
    if (group === null) return;
    setSaving(true);
    try {
      await store.getState().chooseGroup(group.id, subgroup);
      onDone();
    } finally {
      setSaving(false);
    }
  };

  return (
    <ScrollView contentContainerStyle={styles.content} style={{ backgroundColor: t.background }} keyboardShouldPersistTaps="handled">
      <Title>Выберите группу</Title>

      <Section title="Факультет">
        {units
          .filter((u) => unitsOpen || u.id === unitId)
          .map((u) => (
            <Choice
              key={u.id}
              label={u.name}
              selected={u.id === unitId}
              onPress={() => {
                if (u.id === unitId && !unitsOpen) {
                  setUnitsOpen(true); // нажатие на выбранный факультет снова открывает список
                  return;
                }
                setUnitId(u.id);
                setUnitsOpen(false);
                setCourse(undefined);
                setGroup(null);
                setGroups([]);
              }}
            />
          ))}
        {!unitsOpen && <Body muted>Нажмите на факультет, чтобы выбрать другой.</Body>}
      </Section>

      {unitId !== null && (
        <Section title="Курс">
          <Segmented
            options={courses.map((c) => ({ label: c === null ? 'Курс не определён' : `${c} курс`, value: c as number | null }))}
            value={course === undefined ? (undefined as unknown as number | null) : course}
            onChange={(c) => { setCourse(c); setGroup(null); }}
          />
        </Section>
      )}

      {course !== undefined && unitId !== null && (
        <Section title="Группа">
          <View style={styles.groupGrid}>
            {groups.map((g) => (
              <Pressable
                key={g.id}
                accessibilityRole="button"
                accessibilityState={{ selected: group?.id === g.id }}
                onPress={() => setGroup(g)}
                style={[styles.groupChip, { backgroundColor: group?.id === g.id ? t.primary : t.card, borderColor: t.border }]}
              >
                <Text style={{ color: group?.id === g.id ? t.onPrimary : t.text, fontWeight: '600' }}>{g.name}</Text>
              </Pressable>
            ))}
            {groups.length === 0 && <Body muted>Групп нет</Body>}
          </View>
        </Section>
      )}

      {group !== null && subgroups.length > 0 && (
        <Section title="Подгруппа">
          <Segmented options={[{ label: 'Все', value: null as number | null }, ...subgroups.map((n) => ({ label: `${n}`, value: n as number | null }))]} value={subgroup} onChange={setSubgroup} />
          <Body muted>В расписании группы есть занятия по подгруппам. «Все» покажет занятия всех подгрупп с пометкой.</Body>
        </Section>
      )}

      <Button testID="save-group" label="Готово" onPress={() => void save()} disabled={group === null} busy={saving} />
    </ScrollView>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  const t = useTheme();
  return (
    <View style={styles.section}>
      <Text style={{ color: t.muted, fontSize: 13, fontWeight: '700', textTransform: 'uppercase' }}>{title}</Text>
      {children}
    </View>
  );
}

function Choice({ label, selected, onPress }: { label: string; selected: boolean; onPress: () => void }) {
  const t = useTheme();
  return (
    <Pressable accessibilityRole="button" accessibilityState={{ selected }} onPress={onPress}>
      <Card style={selected ? { borderColor: t.primary, borderWidth: 2 } : undefined}>
        <Text style={{ color: t.text, fontSize: 15, fontWeight: selected ? '700' : '500' }}>{label}</Text>
      </Card>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  content: { padding: 16, gap: 18 },
  section: { gap: 10 },
  groupGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  groupChip: { minHeight: 44, paddingHorizontal: 14, borderRadius: 10, borderWidth: StyleSheet.hairlineWidth, alignItems: 'center', justifyContent: 'center' },
});
