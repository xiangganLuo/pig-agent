package io.pigagent.plugin.collection.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic conversions + canonical {"error"} on bad input for {@link TimeTool}. */
class TimeToolTest {

    private final TimeTool tool = new TimeTool();

    @Test
    void epochToIso_secondsAtEpochZeroUtc() {
        // Act
        String result = tool.epochToIso(0L, "seconds", "UTC");

        // Assert
        assertThat(result).isEqualTo("1970-01-01T00:00:00Z");
    }

    @Test
    void epochToIso_millisConvertsToSameInstant() {
        // Act
        String result = tool.epochToIso(1_000L, "millis", "UTC");

        // Assert
        assertThat(result).isEqualTo("1970-01-01T00:00:01Z");
    }

    @Test
    void epochToIso_invalidUnitReturnsError() {
        assertThat(tool.epochToIso(0L, "fortnights", "UTC")).startsWith("{\"error\":");
    }

    @Test
    void epochToIso_invalidTimezoneReturnsError() {
        assertThat(tool.epochToIso(0L, "seconds", "Not/AZone")).startsWith("{\"error\":");
    }

    @Test
    void isoToEpoch_utcInstant() {
        // Act
        String result = tool.isoToEpoch("2021-01-01T00:00:00Z");

        // Assert
        assertThat(result).isEqualTo("{\"epochSeconds\":1609459200,\"epochMillis\":1609459200000}");
    }

    @Test
    void isoToEpoch_naiveDatetimeAssumedUtc() {
        assertThat(tool.isoToEpoch("1970-01-01T00:00:00"))
                .isEqualTo("{\"epochSeconds\":0,\"epochMillis\":0}");
    }

    @Test
    void isoToEpoch_invalidReturnsError() {
        assertThat(tool.isoToEpoch("not-a-date")).startsWith("{\"error\":");
    }

    @Test
    void convertTimezone_utcToShanghaiAddsEightHours() {
        // Act
        String result = tool.convertTimezone("2026-07-15T12:00:00", "UTC", "Asia/Shanghai");

        // Assert
        assertThat(result).isEqualTo("2026-07-15T20:00:00+08:00");
    }

    @Test
    void convertTimezone_invalidZoneReturnsError() {
        assertThat(tool.convertTimezone("2026-07-15T12:00:00", "UTC", "Bogus/Zone"))
                .startsWith("{\"error\":");
    }

    @Test
    void convertTimezone_invalidDatetimeReturnsError() {
        assertThat(tool.convertTimezone("nope", "UTC", "UTC")).startsWith("{\"error\":");
    }

    @Test
    void currentDateTime_defaultsToUtcAndIsNotAnError() {
        // Act
        String result = tool.currentDateTime(null);

        // Assert — UTC-offset formatting ends with Z, and it is not an error envelope
        assertThat(result).doesNotStartWith("{\"error\":").endsWith("Z");
    }

    @Test
    void currentDateTime_invalidTimezoneReturnsError() {
        assertThat(tool.currentDateTime("Mars/Olympus")).startsWith("{\"error\":");
    }
}
