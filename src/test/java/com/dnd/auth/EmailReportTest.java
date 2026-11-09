package com.dnd.auth;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.Assert.*;

public class EmailReportTest {
    @Test
    public void draftContainsCategoryUtf8DescriptionAndOptionalAttachmentButNoCredentials() throws Exception {
        SmtpConfig config = new SmtpConfig();
        config.fromAddress = "support@example.com";
        config.username = "private-smtp-user";
        config.password = "never-include-this-secret";
        EmailService service = new EmailService(config);
        assertEquals("support@example.com", service.reportRecipient());
        byte[] image = {1, 2, 3, 4};
        byte[] draft = service.reportDraft(service.reportRecipient(), "Almerion - Bug",
            "აღწერა\nSteps to reproduce", image);
        String raw = new String(draft, StandardCharsets.UTF_8);
        assertFalse(raw.contains(config.username));
        assertFalse(raw.contains(config.password));
        MimeMessage message = read(draft);
        assertEquals("1", message.getHeader("X-Unsent", null));
        assertEquals("Almerion - Bug", message.getSubject());
        assertEquals("support@example.com", message.getAllRecipients()[0].toString());
        MimeMultipart parts = (MimeMultipart) message.getContent();
        assertEquals(2, parts.getCount());
        assertTrue(parts.getBodyPart(0).getContent().toString().contains("აღწერა"));
        assertEquals("almerion-screenshot.png", parts.getBodyPart(1).getFileName());
        assertArrayEquals(image, parts.getBodyPart(1).getInputStream().readAllBytes());
    }

    @Test
    public void removedScreenshotIsAbsentAndDraftWorksWithoutSmtp() throws Exception {
        EmailService service = new EmailService(new SmtpConfig());
        assertFalse(service.isConfigured());
        MimeMessage message = read(service.reportDraft("support@example.com",
            "Almerion - Suggestion", "Suggestion text", null));
        assertTrue(message.isMimeType("text/plain"));
        assertEquals("Suggestion text", message.getContent());
    }

    @Test
    public void recipientFallsBackToUsernameOnlyWhenSenderIsEmpty() {
        SmtpConfig config = new SmtpConfig();
        config.username = "sender@example.com";
        assertEquals(config.username, new EmailService(config).reportRecipient());
        assertEquals("", new EmailService(null).reportRecipient());
    }

    private static MimeMessage read(byte[] draft) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(draft));
    }
}
