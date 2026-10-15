package com.keel.server.metering;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface UsageDailyStore {
    void replaceOpen(LocalDate day, String env, UsageMath.Slice slice);

    void closeBefore(LocalDate today);

    UsageMath.Slice sumClosed(String env);

    UsageMath.Slice open(String env, LocalDate day);

    Long distinctUsers(String env);

    LocalDate earliest(String env);
}
