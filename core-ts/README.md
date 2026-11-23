# Ядро расписания ВГУИТ (TypeScript)

Платформенно независимая часть автономного мобильного приложения: **разбор Excel, каноническая модель, сборка полного набора данных,
календарь недель, схема локальной SQLite**. Не зависит от React Native: байты файла на входе, данные на выходе. Тестируется в Node/Jest
на настоящих файлах университета и сверяется с Java-парсерами backend (эталон).

Приложению остаётся то, что требует устройства: загрузка файлов (HTTP), запись в SQLite, фоновые задачи, интерфейс.

## Состав

| Часть | Где | Что делает |
|---|---|---|
| Чтение Excel | `src/excel` | узкий интерфейс `SheetView` (текст, объединения, скрытые столбцы и строки), реализация на SheetJS. Парсеры не знают о SheetJS |
| Парсеры | `src/parsers/{fuits,spo,zao}`, общий движок `grid` | `vsuet-grid-xlsx` (ФУИТС, ИТФ, ЭХТ, ТФ, ЭУ), `spo-xls` (СПО), `zao-xlsx` (заочная форма, занятия по датам). Независимы по форматам |
| Реестр парсеров | `src/parser/registry.ts` | выбор строго по идентификатору источника, файл никогда не угадывается по содержимому |
| Реестр источников | `src/sources` | 8 подключённых файлов, подразделения, период, пробелы полноты (7 файлов не подключены) |
| Календарь | `src/calendar` | чередование от введённого якоря; нет календаря → явное «не предоставлен» |
| Сборка набора | `src/dataset` | `buildDataset`: проверки, объединение, контрольная сумма, версия, manifest, **отказ при ошибке обязательного источника** |
| Публикация | `src/sync/publish.ts` | `publishDataset`: прежний набор заменяется только полным и корректным; любой отказ его не трогает |
| SQLite | `src/storage/schema.ts` | схема базы набора (заменяется целиком) и базы приложения (настройки, история), вставки набора |

## Как подключить в приложение

```ts
import { ParserRegistry, FuitsExcelParser, SpoExcelParser, ZaoExcelParser, SOURCES, ORGANIZATION_UNITS, KNOWN_GAPS,
         AUTUMN_2026, contextFromPeriodStart, UNKNOWN_CONTEXT, publishDataset } from 'vsuet-schedule-core';

const parsers = new ParserRegistry([new FuitsExcelParser(), new SpoExcelParser(), new ZaoExcelParser()]);

// 1. для каждого включённого источника: скачать байты (условный запрос по ETag / Last-Modified), посчитать SHA-256, разобрать
const bytes: Uint8Array = await download(source.url);
const context = source.periodId ? contextFromPeriodStart(period.startDate) : UNKNOWN_CONTEXT;
const parsed = parsers.get(source.parserId).parse({ bytes, context });          // бросает ScheduleParseError, если файл не разобрать
imports.push({ source, outcome: { status: 'OK', parsed, fileSha256, fetchedAt } });
// ... при ошибке скачивания или разбора: { source, outcome: { status: 'FAILED', error } }

// 2. собрать и опубликовать набор. store.replace обязан подменять базу атомарно (собрать рядом, затем переименовать файл)
const outcome = publishDataset({ sources: SOURCES, imports, units: ORGANIZATION_UNITS, period: AUTUMN_2026, periods: [AUTUMN_2026],
                                 knownGaps: KNOWN_GAPS, now: new Date().toISOString(), sha256 }, store);
// outcome.status: PUBLISHED | UNCHANGED | REJECTED (прежняя база осталась, outcome.reasons объясняет почему)
```

Нужно от приложения: функция `sha256(text): string` (синхронная; подойдёт чисто-JS `@noble/hashes`), `DatasetStore` поверх SQLite, загрузка файлов.
Тяжёлый разбор (по замеру на компьютере порядка полусекунды, на телефоне заметно дольше) выполнять не на потоке интерфейса.

## Запуск

```bash
cd core-ts && npm ci
npm test            # 124 теста: сверка с Java на 8 реальных файлах, набор, календарь, SQLite
npm run typecheck
```

`fixtures/files` это настоящие файлы vsuet.ru (8 штук, 1,2 МБ), `fixtures/golden` это результат Java-парсеров для них. Обновить эталон:
`./mvnw compile` в корне и `java -cp "target/classes:<classpath>" core-ts/tools/GoldenExport.java`.

## Измерено (настольный компьютер, Node 24; на телефоне будет медленнее)

8 файлов 1,16 МБ → разбор 0,4–0,5 с, сборка набора 31 мс, 7285 занятий и 300 групп, JSON 3,7 МБ (gzip 231 КБ), загрузка в SQLite 68 мс,
файл базы 3,3 МБ. SheetJS читает `.xls` и `.xlsx` целиком, объединённые ячейки, скрытые столбцы и строки совпадают с Apache POI.

## Ограничения (честно)

- **Не проверено на устройстве и в Hermes.** Тесты идут в Node. Регулярные выражения написаны без `\p{L}` и lookbehind именно из-за этого,
  но работу SheetJS и всего ядра в Hermes нужно проверить на Android; скорость разбора на слабых телефонах не измерена.
- **Доверие TLS к vsuet.ru на Android** (корень GlobalSign R46) не проверено: см. `docs/STANDALONE_APP_PLAN.md`.
- **Подключено 8 файлов из 15, опубликованных университетом.** `coverage.complete` в наборе честно `false`, пока 7 файлов не исследованы.
- **Конфигурация семестра** (даты, якорь числителя/знаменателя, привязки файлов) задана в коде `src/sources/registry.ts`. Новый семестр
  требует обновления приложения или удалённой конфигурации (решение не принято).
- Если университет изменит формат Excel или адреса файлов, парсер откажется (ошибка вместо неверных данных), а приложение оставит прежнюю базу.
  Автоматического восстановления поддержки неизвестного формата нет и обещать его нельзя.
