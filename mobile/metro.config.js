// Метро видит ядро расписания (../core-ts): оно лежит рядом в репозитории и не является пакетом npm.
const path = require('path');
const { getDefaultConfig } = require('expo/metro-config');

const config = getDefaultConfig(__dirname);
const core = path.resolve(__dirname, '../core-ts');

config.watchFolders = [...(config.watchFolders ?? []), core];
// Зависимости самого ядра (SheetJS) лежат в core-ts/node_modules, зависимости приложения в mobile/node_modules.
config.resolver.nodeModulesPaths = [path.resolve(__dirname, 'node_modules'), path.resolve(core, 'node_modules')];

module.exports = config;
