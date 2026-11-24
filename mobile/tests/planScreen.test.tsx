import { render, screen } from '@testing-library/react-native';
import { DEMO_PLAN } from '../src/plan/demoMainFloor1';
import { FloorPlanScreen } from '../src/features/plan/FloorPlanScreen';
import { LessonCard } from '../src/features/common/LessonCard';
import { PlanContext } from '../src/features/common/PlanContext';
import type { DayLesson } from '../src/domain/schedule';

const lesson = (classroom: string | null): DayLesson => ({
  weekUncertain: false,
  entry: {
    id: 'x', groupId: 'uits/У-251', subject: 'Теоретическая механика', lessonType: 'LECTURE', rawLessonType: 'л.', teacher: 'Давыдов О.Ю.', classroom,
    dayOfWeek: 2, specificDate: null, startTime: '11:50', endTime: '13:25', weekType: 'EVERY_WEEK', subgroupNumber: null, sourceId: 'uits', originalText: null,
  },
});

describe('экран плана', () => {
  it('найденная аудитория: название и корпус, подпись о демо-данных', async () => {
    await render(<FloorPlanScreen plan={DEMO_PLAN} roomRef="129" />);
    expect(screen.getByText('Аудитория 129')).toBeTruthy();
    expect(screen.getByText(/Главный корпус, 1 этаж/)).toBeTruthy();
    expect(screen.getByText(/Демо: схематичный первый этаж/)).toBeTruthy();
  });

  it('номер из двух помещений: сообщает, что подсвечены все', async () => {
    await render(<FloorPlanScreen plan={DEMO_PLAN} roomRef="125" />);
    expect(screen.getByText(/2 помещения с таким номером/)).toBeTruthy();
  });

  it('ненайденная аудитория: понятное сообщение, а не пустой экран', async () => {
    await render(<FloorPlanScreen plan={DEMO_PLAN} roomRef="999" />);
    expect(screen.getByText(/пока не размечена на плане/)).toBeTruthy();
  });
});

describe('кнопка «На плане» в карточке занятия', () => {
  const opened: string[] = [];
  const wrap = (classroom: string | null) => (
    <PlanContext.Provider value={{ openPlan: (r) => void opened.push(r) }}>
      <LessonCard lesson={lesson(classroom)} showSubgroup={false} />
    </PlanContext.Provider>
  );

  it('есть для аудитории, найденной на плане, и открывает план по этому номеру', async () => {
    await render(wrap('129'));
    const { fireEvent } = require('@testing-library/react-native');
    await fireEvent.press(screen.getByText('На плане ›'));
    expect(opened).toEqual(['129']);
  });

  it('нет для аудитории не с плана, для пустой и без провайдера', async () => {
    await render(wrap('999'));
    expect(screen.queryByText('На плане ›')).toBeNull();
    await render(wrap(null));
    expect(screen.queryByText('На плане ›')).toBeNull();
    await render(<LessonCard lesson={lesson('129')} showSubgroup={false} />);
    expect(screen.queryByText('На плане ›')).toBeNull();
  });
});
