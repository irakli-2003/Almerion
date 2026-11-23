package com.dnd.auth;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMultipart;
import jakarta.activation.DataHandler;
import jakarta.mail.util.ByteArrayDataSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Properties;

/**
 * Sends plain-text emails over SMTP using {@link SmtpConfig}.
 *
 * <p>Failures are reported with a specific, actionable reason (bad credentials,
 * blocked port, unreachable host, ...) rather than a generic "not configured"
 * message, because the two most common setup problems - a Gmail account without
 * an App Password, and a network that blocks outbound SMTP - look identical
 * otherwise.</p>
 */
public class EmailService {
    /** Fail fast rather than freezing the UI for the OS-default (minutes-long) TCP timeout. */
    private static final String TIMEOUT_MILLIS = "10000";

    private final SmtpConfig config;

    public EmailService() {
        this(SmtpConfig.loadOrCreateTemplate());
    }

    public EmailService(SmtpConfig config) {
        this.config = config;
    }

    public boolean isConfigured() {
        return configProblem() == null;
    }

    /**
     * @return a human-readable description of why this config can't send mail yet,
     *         or {@code null} if it looks usable.
     */
    public String configProblem() {
        if (config == null) return "No SMTP configuration was found.";
        if (!config.enabled) {
            return "SMTP is turned off. Set \"enabled\": true in " + SmtpConfig.configPath() + ".";
        }
        if (isBlank(config.host)) {
            return "\"host\" is empty. For Gmail this must be the mail server smtp.gmail.com, not your email address.";
        }
        if (config.host.contains("@")) {
            return "\"host\" looks like an email address (" + config.host + "). It must be the mail server name,"
                + " e.g. smtp.gmail.com.";
        }
        if (config.port <= 0) return "\"port\" must be a positive number (587 for STARTTLS, 465 for SSL).";
        if (isBlank(config.username)) return "\"username\" is empty. Use the full email address you send from.";
        if (isBlank(config.password) && isBlank(config.protectedPassword)) {
            return "\"password\" is empty. For Gmail this must be a 16-character App Password, not your normal"
                + " account password.";
        }
        return null;
    }

    /** Reports must never use a plaintext SMTP connection, including older configurations. */
    public String reportConfigProblem() {
        String problem = configProblem();
        if (problem != null) return problem;
        if (!config.useTls && config.port != 465) return "Enable a secure SMTP connection before sending reports.";
        if (!AuthService.isValidEmail(config.username) || !AuthService.isValidEmail(reportRecipient())) {
            return "Enter a valid sender email address.";
        }
        if (config.port > 65535) return "Enter an SMTP port between 1 and 65535.";
        if (!config.host.matches("[A-Za-z0-9.-]+") || config.host.startsWith(".") || config.host.endsWith(".")) {
            return "Enter the SMTP server hostname without a URL or port.";
        }
        if ("smtp.gmail.com".equalsIgnoreCase(config.host) && isBlank(config.protectedPassword)
                && config.password.replaceAll("\\s+", "").length() != 16) {
            return "For Gmail, use a 16-character Google App Password, not your normal password.";
        }
        return null;
    }

    public void send(String to, String subject, String body) {
        send(to, subject, body, null);
    }

    /** Uses the configured sender or Almerion's support address; credentials are never included. */
    public String reportRecipient() {
        if (config == null) return SmtpConfig.SERVICE_ADDRESS;
        return isBlank(config.fromAddress) ? (isBlank(config.username) ? SmtpConfig.SERVICE_ADDRESS : config.username)
            : config.fromAddress;
    }

    public void send(String to, String subject, String body, byte[] screenshot) {
        String problem = configProblem();
        if (problem != null) {
            throw new EmailNotConfiguredException(problem);
        }
        final String password;
        try {
            String stored = config.smtpPassword();
            password = "smtp.gmail.com".equalsIgnoreCase(config.host) ? stored.replaceAll("\\s+", "") : stored;
        } catch (IOException | IllegalArgumentException e) {
            throw new EmailNotConfiguredException("The saved email password could not be unlocked. Configure email again.");
        }
        if (isBlank(password)) throw new EmailNotConfiguredException("Enter an SMTP password or app password.");

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.host", config.host);
        props.put("mail.smtp.port", String.valueOf(config.port));
        props.put("mail.smtp.connectiontimeout", TIMEOUT_MILLIS);
        props.put("mail.smtp.timeout", TIMEOUT_MILLIS);
        props.put("mail.smtp.writetimeout", TIMEOUT_MILLIS);
        if (usesImplicitSsl() || config.useTls) props.put("mail.smtp.ssl.checkserveridentity", "true");
        if (usesImplicitSsl()) {
            // Port 465 speaks TLS from the first byte; STARTTLS (587) negotiates it mid-session.
            // Using the wrong one for the port hangs until timeout, so pick based on the port.
            props.put("mail.smtp.ssl.enable", "true");
        } else {
            props.put("mail.smtp.starttls.enable", String.valueOf(config.useTls));
            props.put("mail.smtp.starttls.required", String.valueOf(config.useTls));
        }

        Session session = Session.getInstance(props, new jakarta.mail.Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.username, password);
            }
        });

        try {
            MimeMessage message = new MimeMessage(session);
            String from = isBlank(config.fromAddress) ? config.username : config.fromAddress;
            message.setFrom(new InternetAddress(from));
            populate(message, to, subject, body, screenshot);
            Transport.send(message);
        } catch (MessagingException e) {
            throw new EmailSendException(explain(e), e);
        }
    }

    /** A complete email draft, including the optional screenshot, usable without SMTP. */
    public byte[] reportDraft(String to, String subject, String body, byte[] screenshot) {
        try {
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
            populate(message, to, subject, body, screenshot);
            message.setHeader("X-Unsent", "1");
            if (!isBlank(reportRecipient())) message.setFrom(new InternetAddress(reportRecipient(), true));
            message.saveChanges();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            message.writeTo(out);
            return out.toByteArray();
        } catch (MessagingException | IOException e) {
            throw new EmailSendException("Could not create the email draft: " + e.getMessage(), e);
        }
    }

    private static void populate(MimeMessage message, String to, String subject, String body,
                                 byte[] screenshot) throws MessagingException {
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, true));
        message.setSubject(subject, "UTF-8");
        if (screenshot == null) {
            message.setText(body, "UTF-8");
        } else {
            MimeBodyPart text = new MimeBodyPart();
            text.setText(body, "UTF-8");
            MimeBodyPart image = new MimeBodyPart();
            image.setDataHandler(new DataHandler(new ByteArrayDataSource(screenshot, "image/png")));
            image.setFileName("almerion-screenshot.png");
            image.setDisposition(jakarta.mail.Part.ATTACHMENT);
            MimeMultipart parts = new MimeMultipart();
            parts.addBodyPart(text);
            parts.addBodyPart(image);
            message.setContent(parts);
        }
    }

    /** Sends a test message to the configured from-address, to verify host/port/credentials. */
    public void testConnection() {
        String problem = configProblem();
        if (problem != null) {
            throw new EmailNotConfiguredException(problem);
        }
        send(isBlank(config.fromAddress) ? config.username : config.fromAddress,
            "Almerion test email",
            "Almerion successfully sent this test message using your outgoing email settings.");
    }

    private boolean usesImplicitSsl() {
        return config.port == 465;
    }

    private String explain(MessagingException e) {
        String detail = e.getMessage() == null ? e.toString() : e.getMessage();
        String lower = detail.toLowerCase(Locale.ROOT);
        if (e instanceof jakarta.mail.AuthenticationFailedException || lower.contains("authentication")
            || lower.contains("username and password not accepted")) {
            return "The mail server rejected the username/password. For Gmail you must enable 2-Step"
                + " Verification and use a 16-character App Password (not your normal password)."
                + "\n\nServer said: " + detail;
        }
        if (lower.contains("timed out") || lower.contains("timeout") || lower.contains("unreachable")
            || lower.contains("refused") || lower.contains("couldn't connect")) {
            return "Could not reach " + config.host + " on port " + config.port + "."
                + " Many office and VPN networks block outbound SMTP - try another network, or port 465"
                + " (SSL) instead of 587.\n\nDetails: " + detail;
        }
        if (lower.contains("unknownhost") || lower.contains("unknown host")) {
            return "The mail server name \"" + config.host + "\" could not be resolved. Check it for typos"
                + " (Gmail uses smtp.gmail.com).\n\nDetails: " + detail;
        }
        return detail;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Thrown when {@link #send} is called but the SMTP settings are incomplete. */
    public static class EmailNotConfiguredException extends RuntimeException {
        public EmailNotConfiguredException(String message) {
            super(message);
        }
    }

    /** Thrown when the SMTP settings look complete but the send itself failed. */
    public static class EmailSendException extends RuntimeException {
        public EmailSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
