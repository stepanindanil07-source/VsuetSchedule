import { useColorScheme } from 'react-native';

export interface Theme {
  background: string;
  card: string;
  text: string;
  muted: string;
  border: string;
  primary: string;
  onPrimary: string;
  accent: string;
  warningBg: string;
  warningText: string;
  errorText: string;
}

const light: Theme = {
  background: '#F3F4F8',
  card: '#FFFFFF',
  text: '#16181D',
  muted: '#6B7280',
  border: '#E1E4EA',
  primary: '#2F5BEA',
  onPrimary: '#FFFFFF',
  accent: '#E8EEFF',
  warningBg: '#FFF4D6',
  warningText: '#6B4E00',
  errorText: '#B42318',
};

const dark: Theme = {
  background: '#0F1115',
  card: '#1A1D24',
  text: '#ECEEF3',
  muted: '#9AA1AE',
  border: '#2A2E37',
  primary: '#7C9BFF',
  onPrimary: '#0F1115',
  accent: '#222A44',
  warningBg: '#3A3010',
  warningText: '#F3D675',
  errorText: '#FF8A80',
};

export const useTheme = (): Theme => (useColorScheme() === 'dark' ? dark : light);
