import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { ParseIssue, ParsedEntry, ParsedGroup, ParsedSchedule } from '../src/model/types';

export const FIXTURES = join(__dirname, '..', 'fixtures');

export function fileBytes(name: string): Uint8Array {
  return new Uint8Array(readFileSync(join(FIXTURES, 'files', name)));
}

/** Эталон: то, что выдали Java-парсеры backend для того же файла. */
export interface Golden {
  file: string;
  parserKey: string;
  parserVersion: string;
  period: { yearFrom: number; yearTo: number; term: string } | null;
  groups: ParsedGroup[];
  entries: ParsedEntry[];
  diagnostics: { totalFound: number; recognized: number; skipped: number; issues: Array<Omit<ParseIssue, 'rawText'>> };
}

export function golden(name: string): Golden {
  return JSON.parse(readFileSync(join(FIXTURES, 'golden', name + '.json'), 'utf-8')) as Golden;
}

/** Первые расхождения двух списков записей, читаемо: чтобы при сбое сразу видеть, что именно разошлось. */
export function firstDifferences<T>(actual: T[], expected: T[], key: (x: T) => string, limit = 5): string[] {
  const out: string[] = [];
  const n = Math.max(actual.length, expected.length);
  for (let i = 0; i < n && out.length < limit; i++) {
    const a = actual[i] === undefined ? '<нет>' : key(actual[i]);
    const e = expected[i] === undefined ? '<нет>' : key(expected[i]);
    if (a !== e) out.push(`#${i}\n  TS  : ${a}\n  Java: ${e}`);
  }
  return out;
}

export function entryKey(e: ParsedEntry): string {
  return JSON.stringify(e);
}

export function summary(s: ParsedSchedule): string {
  return `${s.groups.length} групп, ${s.entries.length} занятий, ${s.diagnostics.issues.length} замечаний`;
}
