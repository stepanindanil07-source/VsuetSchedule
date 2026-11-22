package ru.vsuet.schedule.schedule.domain;

/** Kind of lesson. {@link #OTHER} is for anything the parser cannot classify (the raw text is kept). */
public enum LessonType {
    LECTURE,
    PRACTICE,
    LAB,
    OTHER
}
