package com.agentflow.tool;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DateTimeToolTest {
    private final DateTimeTool dateTime = new DateTimeTool();

    @Test void returnsRequestedIanaTimezone() {
        assertTrue(dateTime.currentDateTime("Asia/Kolkata").endsWith("+05:30[Asia/Kolkata]"));
    }

    @Test void rejectsUnknownTimezone() {
        assertThrows(IllegalArgumentException.class, () -> dateTime.currentDateTime("Not/A_Zone"));
    }
}
