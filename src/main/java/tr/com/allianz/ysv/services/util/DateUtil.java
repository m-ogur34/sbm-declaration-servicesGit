package tr.com.allianz.ysv.services.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;


public final class DateUtil {

    public static final DateTimeFormatter SBM_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private DateUtil() {
    }


    public static String format(LocalDate date) {
        return date == null ? null : date.format(SBM_DATE_FORMATTER);
    }


    public static LocalDate parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), SBM_DATE_FORMATTER);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
