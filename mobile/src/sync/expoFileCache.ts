import { Directory, File, Paths } from 'expo-file-system';
import type { FileCache } from './fileCache';

/** Копии файлов в каталоге документов приложения (expo-file-system). */
export class ExpoFileCache implements FileCache {
  private readonly dir = new Directory(Paths.document, 'sources');

  private file(sourceId: string, suffix = 'bin'): File {
    return new File(this.dir, `${sourceId}.${suffix}`);
  }

  async read(sourceId: string): Promise<Uint8Array | null> {
    const file = this.file(sourceId);
    if (!file.exists) return null;
    try {
      const bytes = await file.bytes();
      return bytes.length > 0 ? bytes : null;
    } catch {
      return null;
    }
  }

  async write(sourceId: string, bytes: Uint8Array): Promise<void> {
    if (!this.dir.exists) this.dir.create({ intermediates: true, idempotent: true });
    // сначала во временный файл: оборванная запись не испортит последнюю хорошую копию
    const temp = this.file(sourceId, 'tmp');
    if (temp.exists) temp.delete();
    temp.create();
    await temp.write(bytes);
    const target = this.file(sourceId);
    if (target.exists) target.delete();
    temp.move(target);
  }
}
