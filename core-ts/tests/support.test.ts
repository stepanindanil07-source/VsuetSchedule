import { firstDifferences } from './support';

/** Сам сравниватель не должен быть слепым: иначе сверка с эталоном ничего не доказывает. */
describe('firstDifferences', () => {
  const key = (x: { a: number }) => JSON.stringify(x);

  it('пусто для одинаковых списков', () => {
    expect(firstDifferences([{ a: 1 }, { a: 2 }], [{ a: 1 }, { a: 2 }], key)).toEqual([]);
  });
  it('видит отличие значения', () => {
    expect(firstDifferences([{ a: 1 }, { a: 3 }], [{ a: 1 }, { a: 2 }], key)).toHaveLength(1);
  });
  it('видит лишние и недостающие записи', () => {
    expect(firstDifferences([{ a: 1 }], [{ a: 1 }, { a: 2 }], key)).toHaveLength(1);
    expect(firstDifferences([{ a: 1 }, { a: 2 }], [{ a: 1 }], key)).toHaveLength(1);
  });
});
