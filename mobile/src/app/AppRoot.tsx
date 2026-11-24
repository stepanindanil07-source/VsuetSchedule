import { useEffect, useState } from 'react';
import { ActivityIndicator, AppState, View } from 'react-native';
import { DarkTheme, DefaultTheme, NavigationContainer, type Theme as NavTheme } from '@react-navigation/native';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { StatusBar } from 'expo-status-bar';
import { GroupPicker } from '../features/onboarding/GroupPicker';
import { OnboardingScreen } from '../features/onboarding/OnboardingScreen';
import { ScheduleScreen } from '../features/schedule/ScheduleScreen';
import { DiagnosticsScreen } from '../features/settings/DiagnosticsScreen';
import { SettingsScreen } from '../features/settings/SettingsScreen';
import { TodayScreen } from '../features/today/TodayScreen';
import { FloorPlanScreen } from '../features/plan/FloorPlanScreen';
import { PlanContext } from '../features/common/PlanContext';
import { Banner, Body } from '../features/common/components';
import { StoreProvider, useAppStore, useStoreApi } from '../features/common/StoreContext';
import { useTheme } from '../features/common/theme';
import type { AppStore, Services } from '../state/store';

type TabParams = { Today: undefined; Schedule: undefined; Settings: undefined };
type RootParams = { Tabs: undefined; GroupPicker: undefined; Diagnostics: undefined; FloorPlan: { roomRef: string } };

const Tabs = createBottomTabNavigator<TabParams>();
const Stack = createNativeStackNavigator<RootParams>();

export function AppRoot({ store, services }: { store: AppStore; services: Services }) {
  return (
    <StoreProvider store={store} services={services}>
      <SafeAreaProvider>
        <Gate />
      </SafeAreaProvider>
    </StoreProvider>
  );
}

/** Выбирает, что показывать: загрузка, первый запуск, выбор группы или основное приложение. */
function Gate() {
  const t = useTheme();
  const store = useStoreApi();
  const phase = useAppStore((s) => s.phase);
  const fatalError = useAppStore((s) => s.fatalError);
  const groupMissing = useAppStore((s) => s.groupMissing);
  const [showDiagnostics, setShowDiagnostics] = useState(false);

  useEffect(() => {
    void store.getState().boot();
  }, [store]);

  // при возвращении в приложение проверяем обновления (не чаще, чем разрешает интервал синхронизации)
  useEffect(() => {
    const sub = AppState.addEventListener('change', (state) => {
      if (state === 'active' && store.getState().phase === 'ready') void store.getState().sync(false);
    });
    return () => sub.remove();
  }, [store]);

  const frame = (children: React.ReactNode) => (
    <SafeAreaView style={{ flex: 1, backgroundColor: t.background }}>
      <StatusBar style="auto" />
      {children}
    </SafeAreaView>
  );

  if (phase === 'booting') return frame(<ActivityIndicator style={{ marginTop: 80 }} />);
  if (phase === 'fatal') {
    return frame(
      <View style={{ padding: 20, gap: 12 }}>
        <Banner>Приложение не смогло открыть своё хранилище.</Banner>
        <Body muted>{fatalError}</Body>
      </View>,
    );
  }
  if (phase === 'needs-dataset') {
    return frame(showDiagnostics ? <DiagnosticsScreen /> : <OnboardingScreen onShowDiagnostics={() => setShowDiagnostics(true)} />);
  }
  if (phase === 'needs-group') {
    return frame(
      <>
        {groupMissing && (
          <View style={{ padding: 16, paddingBottom: 0 }}>
            <Banner>Выбранной группы нет в новом расписании. Выберите группу заново.</Banner>
          </View>
        )}
        <GroupPicker onDone={() => undefined} />
      </>,
    );
  }
  return <MainNavigation />;
}

function MainNavigation() {
  const t = useTheme();
  const dark = useTheme().background === '#0F1115';
  const navTheme: NavTheme = {
    ...(dark ? DarkTheme : DefaultTheme),
    colors: { ...(dark ? DarkTheme : DefaultTheme).colors, background: t.background, card: t.card, text: t.text, border: t.border, primary: t.primary },
  };
  return (
    <NavigationContainer theme={navTheme}>
      <StatusBar style="auto" />
      <Stack.Navigator>
        <Stack.Screen name="Tabs" component={TabsScreen} options={{ headerShown: false }} />
        <Stack.Screen name="GroupPicker" options={{ title: 'Группа' }} component={GroupPickerRoute} />
        <Stack.Screen name="Diagnostics" options={{ title: 'Диагностика' }} component={DiagnosticsScreen} />
        <Stack.Screen name="FloorPlan" options={{ title: 'План' }} component={FloorPlanRoute} />
      </Stack.Navigator>
    </NavigationContainer>
  );
}

function TabsScreen({ navigation }: { navigation: { navigate: (name: any, params?: any) => void } }) {
  return (
    <PlanContext.Provider value={{ openPlan: (roomRef) => navigation.navigate('FloorPlan', { roomRef }) }}>
    <Tabs.Navigator screenOptions={{ tabBarLabelStyle: { fontSize: 13, fontWeight: '600' }, tabBarIcon: () => null, tabBarIconStyle: { display: 'none' }, tabBarLabelPosition: 'beside-icon' }}>
      <Tabs.Screen name="Today" options={{ title: 'Сегодня' }} component={TodayScreen} />
      <Tabs.Screen name="Schedule" options={{ title: 'Расписание' }} component={ScheduleScreen} />
      <Tabs.Screen name="Settings" options={{ title: 'Настройки' }}>
        {() => <SettingsScreen onChangeGroup={() => navigation.navigate('GroupPicker')} onDiagnostics={() => navigation.navigate('Diagnostics')} />}
      </Tabs.Screen>
    </Tabs.Navigator>
    </PlanContext.Provider>
  );
}

function FloorPlanRoute({ route }: { route: { params: { roomRef: string } } }) {
  return <FloorPlanScreen roomRef={route.params.roomRef} />;
}

function GroupPickerRoute({ navigation }: { navigation: { goBack: () => void } }) {
  const groupId = useAppStore((s) => s.groupId);
  return <GroupPicker initialGroupId={groupId} onDone={() => navigation.goBack()} />;
}

