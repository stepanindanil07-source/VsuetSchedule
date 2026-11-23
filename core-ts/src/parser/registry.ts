import type { ScheduleParser } from './types';

/**
 * Реестр парсеров. Парсер выбирается ТОЛЬКО по идентификатору, указанному у источника: файл никогда не угадывается по содержимому,
 * поэтому неподходящий парсер не может быть выбран молча. Неизвестный идентификатор это ошибка.
 * Новый парсер подключается регистрацией; существующие, база данных и экраны не меняются.
 */
export class ParserRegistry {
  private readonly parsers = new Map<string, ScheduleParser>();

  constructor(parsers: readonly ScheduleParser[] = []) {
    parsers.forEach((p) => this.register(p));
  }

  register(parser: ScheduleParser): void {
    if (this.parsers.has(parser.parserId)) throw new Error(`Duplicate parser id '${parser.parserId}'`);
    this.parsers.set(parser.parserId, parser);
  }

  get(parserId: string): ScheduleParser {
    const parser = this.parsers.get(parserId);
    if (!parser) throw new Error(`No parser registered for id '${parserId}'. Known ids: ${[...this.parsers.keys()].join(', ')}`);
    return parser;
  }

  has(parserId: string): boolean {
    return this.parsers.has(parserId);
  }
}
