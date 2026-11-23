package com.dnd.auth;

import org.junit.Test;
import static org.junit.Assert.*;

public class SmtpConfigTest {
    @org.junit.Rule public org.junit.rules.TemporaryFolder temp = new org.junit.rules.TemporaryFolder();

    @Test public void defaultsUseTheAlmerionAddressWithoutEnablingUnauthenticatedMail() {
        SmtpConfig config = new SmtpConfig();
        assertEquals("almerion.d20@gmail.com", config.username);
        assertEquals(config.username, new EmailService(config).reportRecipient());
        assertFalse(config.enabled);
        assertTrue(config.password.isEmpty());
    }

    @Test public void oldServiceAddressMigratesWithoutChangingOtherSettings() {
        SmtpConfig config = new SmtpConfig();
        config.username = "dndmanagerd20@gmail.com";
        config.fromAddress = "dndmanagerd20@gmail.com";
        config.password = "test-placeholder";
        config.port = 465;
        config.enabled = true;
        assertTrue(SmtpConfig.migrateServiceAddress(config));
        assertEquals(SmtpConfig.SERVICE_ADDRESS, config.username);
        assertEquals(SmtpConfig.SERVICE_ADDRESS, config.fromAddress);
        assertEquals("test-placeholder", config.password);
        assertEquals(465, config.port);
        assertTrue(config.enabled);
        assertFalse(SmtpConfig.migrateServiceAddress(config));
    }

    @Test public void customMailProviderIsNotChanged() {
        SmtpConfig config = new SmtpConfig();
        config.username = "custom@example.test";
        config.fromAddress = "support@example.test";
        assertFalse(SmtpConfig.migrateServiceAddress(config));
        assertEquals("custom@example.test", config.username);
        assertEquals("support@example.test", config.fromAddress);
    }

    @Test public void reportSetupRequiresSecureSmtpAndAGmailAppPassword() {
        SmtpConfig config = new SmtpConfig();
        config.enabled = true;
        config.username = "sender@example.com";
        config.password = "ordinary-password";
        EmailService service = new EmailService(config);
        assertTrue(service.reportConfigProblem().contains("App Password"));
        config.password = "abcd efgh ijkl mnop";
        config.useTls = false;
        assertTrue(service.reportConfigProblem().contains("secure SMTP"));
        config.port = 465;
        assertNull(service.reportConfigProblem());
    }

    @Test public void configuredPasswordIsNotWrittenInPlaintext() throws Exception {
        SmtpConfig config = new SmtpConfig();
        config.password = "abcd efgh ijkl mnop";
        config.enabled = true;
        var target = temp.getRoot().toPath().resolve("smtp-config.json");
        config.saveWithProtectedPassword(target, new com.dnd.github.TokenStore.Protector() {
            @Override public String name() { return "test"; }
            @Override public String protect(String secret) { return "opaque-encrypted-payload"; }
            @Override public String unprotect(String stored) { throw new UnsupportedOperationException(); }
        });
        String contents = java.nio.file.Files.readString(target);
        assertFalse(contents.contains(config.password));
        var stored = com.dnd.data.JsonMappers.create().readValue(contents, SmtpConfig.class);
        assertTrue(stored.enabled);
        assertTrue(stored.password.isEmpty());
        assertEquals("opaque-encrypted-payload", stored.protectedPassword);
    }
}
