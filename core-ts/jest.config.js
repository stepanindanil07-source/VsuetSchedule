/** @type {import('jest').Config} */
module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'node',
  testMatch: ['<rootDir>/tests/**/*.test.ts'],
  // разбор реальных файлов занимает доли секунды, но на медленных машинах CI оставим запас
  testTimeout: 30000,
};
