/**
 * Загрузка файла расписания с условными запросами. Сайт университета отдаёт ETag и Last-Modified и отвечает 304 на повторный
 * запрос, поэтому проверка обновления не скачивает файл заново.
 */

export interface ConditionalRequest {
  url: string;
  /** Передаётся только когда у нас есть сохранённая копия файла: без копии 304 нам ничем не поможет. */
  etag: string | null;
  lastModified: string | null;
  timeoutMs: number;
}

export type FetchResult =
  | { status: 'NOT_MODIFIED' }
  | { status: 'OK'; bytes: Uint8Array; etag: string | null; lastModified: string | null };

/** Сетевая ошибка, после которой имеет смысл повторить (обрыв, тайм-аут, 5xx). */
export class RetryableFetchError extends Error {}
/** Ошибка, после которой повтор бесполезен (404, 403, пустой ответ). */
export class PermanentFetchError extends Error {}

export type Fetcher = (request: ConditionalRequest) => Promise<FetchResult>;

/** Верхняя граница размера файла: расписания весят сотни килобайт, всё больше 20 МБ это не то, что мы ждём. */
export const MAX_FILE_BYTES = 20 * 1024 * 1024;

export const fetchFile: Fetcher = async (request) => {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), request.timeoutMs);
  try {
    const headers: Record<string, string> = { Accept: '*/*' };
    if (request.etag !== null) headers['If-None-Match'] = request.etag;
    if (request.lastModified !== null) headers['If-Modified-Since'] = request.lastModified;
    let response: Response;
    try {
      response = await fetch(request.url, { headers, signal: controller.signal });
    } catch (error) {
      throw new RetryableFetchError(controller.signal.aborted ? 'Сайт не ответил вовремя' : `Нет соединения: ${(error as Error).message}`);
    }
    if (response.status === 304) return { status: 'NOT_MODIFIED' };
    if (response.status >= 500 || response.status === 429 || response.status === 408) throw new RetryableFetchError(`Сайт ответил ${response.status}`);
    if (response.status !== 200) throw new PermanentFetchError(`Сайт ответил ${response.status}`);
    const declared = Number(response.headers.get('content-length'));
    if (Number.isFinite(declared) && declared > MAX_FILE_BYTES) throw new PermanentFetchError('Файл слишком большой');
    let bytes: Uint8Array;
    try {
      bytes = new Uint8Array(await response.arrayBuffer());
    } catch (error) {
      throw new RetryableFetchError(controller.signal.aborted ? 'Сайт не ответил вовремя' : `Загрузка прервана: ${(error as Error).message}`);
    }
    if (bytes.length === 0) throw new PermanentFetchError('Пустой ответ сайта');
    if (bytes.length > MAX_FILE_BYTES) throw new PermanentFetchError('Файл слишком большой');
    return { status: 'OK', bytes, etag: response.headers.get('etag'), lastModified: response.headers.get('last-modified') };
  } finally {
    clearTimeout(timer);
  }
};

/** Начало файла: Excel 2007+ это ZIP («PK»), Excel 97–2003 это контейнер OLE (D0 CF 11 E0). Страница ошибки HTML с кодом 200 не пройдёт. */
export function looksLikeExcel(bytes: Uint8Array, format: 'XLS' | 'XLSX'): boolean {
  if (format === 'XLSX') return bytes.length > 4 && bytes[0] === 0x50 && bytes[1] === 0x4b && bytes[2] === 0x03 && bytes[3] === 0x04;
  return bytes.length > 8 && bytes[0] === 0xd0 && bytes[1] === 0xcf && bytes[2] === 0x11 && bytes[3] === 0xe0;
}

/** Выполняет запрос с повторами: пауза растёт, к ней добавляется случайная доля, чтобы устройства не стучались в сайт одновременно. */
export async function withRetries<T>(
  action: () => Promise<T>,
  options: { attempts: number; baseDelayMs: number; sleep?: (ms: number) => Promise<void>; random?: () => number },
): Promise<T> {
  const sleep = options.sleep ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)));
  const random = options.random ?? Math.random;
  let lastError: unknown;
  for (let attempt = 1; attempt <= options.attempts; attempt++) {
    try {
      return await action();
    } catch (error) {
      lastError = error;
      if (!(error instanceof RetryableFetchError) || attempt === options.attempts) throw error;
      await sleep(options.baseDelayMs * 2 ** (attempt - 1) * (0.75 + random() * 0.5));
    }
  }
  throw lastError;
}
