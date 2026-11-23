/**
 * Копии скачанных файлов. Нужны, чтобы при сбое сети (и при смене версии парсера) работать с последней хорошей копией, а не
 * заново качать файл. Файл заменяется только после успешного разбора нового.
 */
export interface FileCache {
  read(sourceId: string): Promise<Uint8Array | null>;
  write(sourceId: string, bytes: Uint8Array): Promise<void>;
}

export class MemoryFileCache implements FileCache {
  readonly files = new Map<string, Uint8Array>();
  async read(sourceId: string): Promise<Uint8Array | null> {
    return this.files.get(sourceId) ?? null;
  }
  async write(sourceId: string, bytes: Uint8Array): Promise<void> {
    this.files.set(sourceId, bytes);
  }
}
