package com.churchgeniuspro.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * App Store readiness D8: evaluation tenants (DEMO- sample data, TRIAL- self-service
 * trials) can never send WhatsApp, even if Twilio credentials are stored for them.
 */
class WhatsAppDemoGuardTest {

    private static final Path SRC =
            Path.of("src/main/java/com/churchgeniuspro/service/WhatsAppSenderService.java");

    @Test
    @DisplayName("DEMO- and TRIAL- tenants are blocked; a regular church is not")
    void evaluationTenantsBlocked() {
        assertTrue(WhatsAppSenderService.isWhatsAppBlockedTenant("DEMO-1793981746588"));
        assertTrue(WhatsAppSenderService.isWhatsAppBlockedTenant("TRIAL-1793000000000"));
        assertFalse(WhatsAppSenderService.isWhatsAppBlockedTenant("USR839e1eac-4b01-4a37-872f-14883dc2eafa"));
        assertFalse(WhatsAppSenderService.isWhatsAppBlockedTenant("CHURCH-123"));
    }

    @Test
    @DisplayName("an unknown tenant fails closed")
    void unknownTenantFailsClosed() {
        assertTrue(WhatsAppSenderService.isWhatsAppBlockedTenant(null));
        assertTrue(WhatsAppSenderService.isWhatsAppBlockedTenant("  "));
    }

    @Test
    @DisplayName("the guard sits above every Twilio WhatsApp address in both send paths")
    void guardPrecedesEveryWhatsAppCall() throws Exception {
        String src = Files.readString(SRC);
        for (String method : new String[] {"private void doSend(", "private void doSendTemplate("}) {
            int start = src.indexOf(method);
            assertTrue(start >= 0, method + " not found");
            int next = src.indexOf("\n    private ", start + method.length());
            String body = src.substring(start, next < 0 ? src.length() : next);
            int guard = body.indexOf("isWhatsAppBlockedTenant(");
            int twilio = body.indexOf("\"whatsapp:\"");
            assertTrue(guard >= 0, method + " has no evaluation-tenant guard");
            assertTrue(twilio < 0 || guard < twilio,
                    method + ": the guard must come before the Twilio WhatsApp call");
        }
    }
}
