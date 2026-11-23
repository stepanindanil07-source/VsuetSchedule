import type { ReactNode } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View, type StyleProp, type ViewStyle } from 'react-native';
import { useTheme } from './theme';

export function Card({ children, style }: { children: ReactNode; style?: StyleProp<ViewStyle> }) {
  const t = useTheme();
  return <View style={[styles.card, { backgroundColor: t.card, borderColor: t.border }, style]}>{children}</View>;
}

export function Title({ children }: { children: ReactNode }) {
  const t = useTheme();
  return <Text style={[styles.title, { color: t.text }]}>{children}</Text>;
}

export function Body({ children, muted, style }: { children: ReactNode; muted?: boolean; style?: StyleProp<ViewStyle> }) {
  const t = useTheme();
  return <Text style={[styles.body, { color: muted ? t.muted : t.text }, style as never]}>{children}</Text>;
}

export function Button({ label, onPress, kind = 'primary', disabled, busy, testID }: { label: string; onPress: () => void; kind?: 'primary' | 'secondary'; disabled?: boolean; busy?: boolean; testID?: string }) {
  const t = useTheme();
  const primary = kind === 'primary';
  return (
    <Pressable
      testID={testID}
      accessibilityRole="button"
      accessibilityState={{ disabled: !!disabled || !!busy, busy: !!busy }}
      disabled={disabled || busy}
      onPress={onPress}
      style={({ pressed }) => [
        styles.button,
        { backgroundColor: primary ? t.primary : t.accent, opacity: disabled ? 0.5 : pressed ? 0.8 : 1 },
      ]}
    >
      {busy ? <ActivityIndicator color={primary ? t.onPrimary : t.primary} /> : <Text style={[styles.buttonText, { color: primary ? t.onPrimary : t.primary }]}>{label}</Text>}
    </Pressable>
  );
}

/** Строка выбора из нескольких вариантов (числитель/знаменатель, подгруппа). */
export function Segmented<T extends string | number | null>({ options, value, onChange }: { options: Array<{ label: string; value: T }>; value: T; onChange: (value: T) => void }) {
  const t = useTheme();
  return (
    <View style={styles.segmented}>
      {options.map((o) => {
        const selected = o.value === value;
        return (
          <Pressable
            key={String(o.value)}
            accessibilityRole="button"
            accessibilityState={{ selected }}
            onPress={() => onChange(o.value)}
            style={[styles.segment, { backgroundColor: selected ? t.primary : t.accent }]}
          >
            <Text style={{ color: selected ? t.onPrimary : t.primary, fontWeight: '600' }}>{o.label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

export function Banner({ children, tone = 'warning' }: { children: ReactNode; tone?: 'warning' | 'info' }) {
  const t = useTheme();
  return (
    <View style={[styles.banner, { backgroundColor: tone === 'warning' ? t.warningBg : t.accent }]}>
      <Text style={{ color: tone === 'warning' ? t.warningText : t.text, fontSize: 14, lineHeight: 20 }}>{children}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  card: { borderRadius: 14, borderWidth: StyleSheet.hairlineWidth, padding: 14 },
  title: { fontSize: 24, fontWeight: '700' },
  body: { fontSize: 15, lineHeight: 21 },
  button: { minHeight: 48, borderRadius: 12, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 18 },
  buttonText: { fontSize: 16, fontWeight: '600' },
  segmented: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  segment: { minHeight: 40, borderRadius: 10, paddingHorizontal: 14, alignItems: 'center', justifyContent: 'center' },
  banner: { borderRadius: 12, padding: 12 },
});
