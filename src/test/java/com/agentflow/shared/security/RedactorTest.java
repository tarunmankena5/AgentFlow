package com.agentflow.shared.security;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedactorTest {
    @Test void redactsSensitiveKeysAndCredentialPatterns() {
        Object safe = Redactor.sanitize(Map.of("api_key", "key-123", "note", "Bearer abc.def and user@example.com"));
        assertEquals("[REDACTED]", ((Map<?, ?>) safe).get("api_key"));
        assertEquals("Bearer [REDACTED] and [REDACTED_EMAIL]", ((Map<?, ?>) safe).get("note"));
        assertEquals("token=[REDACTED]", Redactor.sanitizeText("token=secret-value"));
        assertEquals("Incorrect API key provided: [REDACTED]. Contact support.",
                Redactor.sanitizeText("Incorrect API key provided: sk-test-secret. Contact support."));
    }
}
