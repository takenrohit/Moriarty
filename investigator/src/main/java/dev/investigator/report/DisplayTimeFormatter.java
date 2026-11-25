package dev.investigator.report;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Formats instants in the Display_Timezone for the Markdown and HTML outputs (Req 17.5, 17.7).
 *
 * <p>Every method converts with {@code instant.atZone(zone)}, so the result does not depend on the JVM
 * default timezone. Zone abbreviations use English names ({@code IST} for {@code Asia/Kolkata});
 * zones without one show an offset such as {@code GMT-05:00}. JSON output does not use this class: it
 * keeps UTC ISO-8601 strings.
 */
public final class DisplayTimeFormatter {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.ENGLISH);
    private static final DateTimeFormatter ZONE = DateTimeFormatter.ofPattern("z", Locale.ENGLISH);

    private final ZoneId zone;

    public DisplayTimeFormatter(ZoneId zone) {
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    public ZoneId zone() {
        return zone;
    }

    /** Hour and minute in the display zone, for example {@code 20:10}. */
    public String time(Instant instant) {
        return TIME.format(instant.atZone(zone));
    }

    /** Date, time and zone abbreviation, for example {@code 2026-11-23 20:10 IST}. */
    public String dateTime(Instant instant) {
        return DATE_TIME.format(instant.atZone(zone));
    }

    /** Zone abbreviation in effect at {@code instant}, for example {@code IST}. */
    public String zoneAbbreviation(Instant instant) {
        return ZONE.format(instant.atZone(zone));
    }

    /** Calendar date of {@code instant} in the display zone. */
    public LocalDate date(Instant instant) {
        return instant.atZone(zone).toLocalDate();
    }
}
