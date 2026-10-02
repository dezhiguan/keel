package com.keel.server.insight.archunit.violations;

import java.sql.SQLException;
import java.sql.Statement;

public class InsightWriteViolation {
    public void write(Statement statement) throws SQLException {
        statement.executeUpdate("update business_table set value = 1");
    }
}
