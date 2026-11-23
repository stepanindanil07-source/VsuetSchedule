module.exports = {
  preset: 'jest-expo',
  testMatch: ['<rootDir>/tests/**/*.test.ts?(x)'],
  moduleNameMapper: {
    '^@core$': '<rootDir>/../core-ts/src/index.ts',
  },
  // SheetJS в core-ts/node_modules грузится как обычный CommonJS
  transformIgnorePatterns: ['/node_modules/(?!((jest-)?react-native|@react-native(-community)?|expo(nent)?|@expo(nent)?/.*|@expo-google-fonts/.*|react-navigation|@react-navigation/.*|zustand|@noble/hashes))'],
  // ядро лежит вне каталога приложения: его файлам нужны пакеты из node_modules приложения (вспомогательный код Babel)
  modulePaths: ['<rootDir>/node_modules'],
  testTimeout: 60000,
};
