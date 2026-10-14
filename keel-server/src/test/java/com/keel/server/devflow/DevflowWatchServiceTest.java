package com.keel.server.devflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DevflowWatchServiceTest {
    @Test
    void sevenDistinctHealthyDaysAreRequired() {
        var observations = new ObjectMapper().createArrayNode();
        for (int day = 1; day <= 6; day++) {
            observations.addObject().put("day", "2026-10-%02d".formatted(day)).put("healthy", true);
        }
        assertThat(DevflowWatchService.sevenHealthyDays(observations)).isFalse();
        observations.addObject().put("day", "2026-10-06").put("healthy", true);
        assertThat(DevflowWatchService.sevenHealthyDays(observations)).isFalse();
        observations.remove(6);
        observations.addObject().put("day", "2026-10-07").put("healthy", false);
        assertThat(DevflowWatchService.sevenHealthyDays(observations)).isFalse();
        observations.addObject().put("day", "2026-10-08").put("healthy", true);
        assertThat(DevflowWatchService.sevenHealthyDays(observations)).isFalse();
        for (int day = 9; day <= 14; day++) {
            observations.addObject().put("day", "2026-10-%02d".formatted(day)).put("healthy", true);
        }
        assertThat(DevflowWatchService.sevenHealthyDays(observations)).isTrue();
        observations.addObject().put("day", "2026-10-16").put("healthy", true);
        assertThat(DevflowWatchService.sevenHealthyDays(observations)).isFalse();
    }
}
