package com.herasgarden.gardenevents;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class EventTime {
    private static final ZoneId EASTERN = ZoneId.of("America/New_York");
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("M/d/yyyy 'at' h:mm a", Locale.US).withZone(EASTERN);

    private EventTime() {
    }

    public static String format(long epochMillis) {
        return FORMATTER.format(Instant.ofEpochMilli(epochMillis));
    }

    public static ZoneId zone() {
        return EASTERN;
    }
}
