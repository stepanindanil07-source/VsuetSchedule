import { useEffect, useState } from 'react';
import { ActivityIndicator, Text, View } from 'react-native';
import { AppRoot } from './src/app/AppRoot';
import { createServices } from './src/app/createServices';
import { createAppStore, type AppStore, type Services } from './src/state/store';

export default function App() {
  const [ready, setReady] = useState<{ store: AppStore; services: Services } | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    createServices().then(
      (services) => setReady({ services, store: createAppStore(services) }),
      (e: Error) => setError(e.message),
    );
  }, []);

  if (error !== null) {
    return (
      <View style={{ flex: 1, justifyContent: 'center', padding: 24 }}>
        <Text>Не удалось открыть хранилище приложения: {error}</Text>
      </View>
    );
  }
  if (ready === null) return <ActivityIndicator style={{ flex: 1 }} />;
  return <AppRoot store={ready.store} services={ready.services} />;
}
