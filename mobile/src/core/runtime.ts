import { sha256 } from '@noble/hashes/sha2.js';
import { bytesToHex, utf8ToBytes } from '@noble/hashes/utils.js';
import { FuitsExcelParser, ParserRegistry, SpoExcelParser, ZaoExcelParser } from '@core';

/** SHA-256 текста (для контрольной суммы набора). Без node:crypto и TextEncoder: в Hermes их может не быть. */
export const sha256Text = (text: string): string => bytesToHex(sha256(utf8ToBytes(text)));

/** SHA-256 файла: по нему видно, что файл изменился даже при том, что сайт не прислал ETag. */
export const sha256Bytes = (bytes: Uint8Array): string => bytesToHex(sha256(bytes));

/** Парсеры приложения. Новый формат подключается регистрацией здесь; остальной код не меняется. */
export function createParserRegistry(): ParserRegistry {
  return new ParserRegistry([new FuitsExcelParser(), new SpoExcelParser(), new ZaoExcelParser()]);
}
