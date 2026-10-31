package com.agentflow.tool;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class DateTimeTool {
    @Tool(description = "Get the current date and time in an IANA timezone such as UTC or Asia/Kolkata.")
    public String currentDateTime(@ToolParam(description = "IANA timezone; use UTC when omitted") String timezone) {
        if (timezone != null && timezone.length() > 100) throw new IllegalArgumentException("Timezone identifier is too long");
        try { return ZonedDateTime.ofInstant(Instant.now(), ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone)).toString(); }
        catch (Exception e) { throw new IllegalArgumentException("Unknown timezone"); }
    }
}
