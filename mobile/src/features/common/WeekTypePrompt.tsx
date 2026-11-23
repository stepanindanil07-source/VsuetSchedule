import { StyleSheet, View } from 'react-native';
import { Banner, Button } from './components';
import { useAppStore } from './StoreContext';

/** Показывается, когда тип недели неизвестен (календаря нет или дата вне него): тип выбирается вручную. */
export function WeekTypePrompt() {
  const setWeekType = useAppStore((s) => s.setWeekType);
  return (
    <View style={styles.box}>
      <Banner>В расписании есть занятия «только по числителю» и «только по знаменателю», а университет не публикует, какая сейчас неделя. Укажите тип текущей недели, дальше недели будут чередоваться сами.</Banner>
      <View style={styles.buttons}>
        <View style={styles.flex}>
          <Button testID="week-numerator" kind="secondary" label="Сейчас числитель" onPress={() => void setWeekType('NUMERATOR')} />
        </View>
        <View style={styles.flex}>
          <Button testID="week-denominator" kind="secondary" label="Сейчас знаменатель" onPress={() => void setWeekType('DENOMINATOR')} />
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  box: { gap: 10 },
  buttons: { flexDirection: 'row', gap: 10 },
  flex: { flex: 1 },
});
