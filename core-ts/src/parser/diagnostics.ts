import type { ParseDiagnostics, ParseIssue } from '../model/types';

/** Место в исходном файле: лист и ячейка или диапазон. */
export interface SourceLocation {
  sheet: string;
  range: string;
}

/**
 * Накопитель диагностики на время разбора. Ничего не теряется молча: пропущенная запись это ERROR,
 * сомнительная, но сохранённая, это WARNING.
 */
export class DiagnosticsCollector {
  private readonly issues: ParseIssue[] = [];
  private recognized = 0;
  private skipped = 0;

  /** Занятие превращено в запись. */
  entryRecognized(): void {
    this.recognized++;
  }

  /** Занятие найдено, но не разобрано: пропущено, записано как ERROR. */
  entrySkipped(code: string, message: string, location: SourceLocation | null, rawText: string | null): void {
    this.skipped++;
    this.issues.push(this.issue('ERROR', code, message, location, rawText));
  }

  /** Требует внимания, но запись сохранена. */
  warn(code: string, message: string, location: SourceLocation | null, rawText: string | null): void {
    this.issues.push(this.issue('WARNING', code, message, location, rawText));
  }

  build(parserVersion: string): ParseDiagnostics {
    return {
      parserVersion,
      totalFound: this.recognized + this.skipped,
      recognized: this.recognized,
      skipped: this.skipped,
      issues: [...this.issues],
    };
  }

  private issue(severity: 'WARNING' | 'ERROR', code: string, message: string, location: SourceLocation | null, rawText: string | null): ParseIssue {
    return { severity, code, message, sheet: location?.sheet ?? null, range: location?.range ?? null, rawText };
  }
}
