package ru.vsuet.schedule.schedule.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.DayOfWeek;

/** Stores the ISO day number (1 = Monday ... 7 = Sunday) in a SMALLINT column. */
@Converter
class DayOfWeekConverter implements AttributeConverter<DayOfWeek, Short> {

    @Override
    public Short convertToDatabaseColumn(DayOfWeek day) {
        return day == null ? null : (short) day.getValue();
    }

    @Override
    public DayOfWeek convertToEntityAttribute(Short value) {
        return value == null ? null : DayOfWeek.of(value);
    }
}
