/**
 * Данные планов корпусов. Координаты в условных единицах плана (пикселях исходной схемы), начало в левом верхнем углу.
 * Файл данных описывает план целиком: комнаты, оси коридоров, лестницы. По коридорам строятся маршруты.
 */
export type Point = readonly [number, number];

export interface PlanRoom {
  /** Номер аудитории так, как он написан на плане («129», «016а»). Номера могут повторяться: тогда подсвечиваются все. */
  ref: string;
  /** Подпись для помещений без номера («Библиотека»). */
  title?: string;
  polygon: readonly Point[];
}

export interface PlanFloor {
  id: string;
  /** Номер этажа для переключателя (0 = цоколь, −1 = подвал). */
  level: number;
  title: string;
  width: number;
  height: number;
  rooms: readonly PlanRoom[];
  /** Оси коридоров: ломаные линии. Концы, лежащие рядом, считаются соединёнными. */
  corridors: ReadonlyArray<readonly Point[]>;
  stairs: readonly Point[];
}

export interface PlanBuilding {
  id: string;
  name: string;
  floors: readonly PlanFloor[];
}

export interface PlanData {
  /** Откуда взяты данные и можно ли ими пользоваться в выпуске. Выводится на экране плана. */
  provenance: string;
  buildings: readonly PlanBuilding[];
}

export interface RoomHit {
  building: PlanBuilding;
  floor: PlanFloor;
  room: PlanRoom;
  /** Совпадение не точное (номер найден без ведущих нулей): на экране помечается как вероятное. */
  approximate?: boolean;
}
