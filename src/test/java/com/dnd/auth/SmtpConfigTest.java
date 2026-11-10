package com.dnd.auth;

import org.junit.Test;
import static org.junit.Assert.*;

public class SmtpConfigTest {
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
}
