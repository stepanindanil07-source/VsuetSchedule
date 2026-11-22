package ru.vsuet.schedule.schedule.domain;

/** Kind of lesson. {@link #OTHER} is for anything the parser cannot classify (the raw text is kept). */
public enum LessonType {
    LECTURE,
    PRACTICE,
    LAB,
    /** Written exam ("экз.") of the correspondence schedule. */
    EXAM,
    /** Credit ("зач.") of the correspondence schedule. */
    CREDIT,
    OTHER
}
