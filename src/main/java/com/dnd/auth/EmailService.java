package com.dnd.auth;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

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
        if (isBlank(normalizedPassword())) {
            return "\"password\" is empty. For Gmail this must be a 16-character App Password, not your normal"
                + " account password.";
        }
        return null;
    }

    public void send(String to, String subject, String body) {
        String problem = configProblem();
        if (problem != null) {
            throw new EmailNotConfiguredException(problem);
        }

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.host", config.host);
        props.put("mail.smtp.port", String.valueOf(config.port));
        props.put("mail.smtp.connectiontimeout", TIMEOUT_MILLIS);
        props.put("mail.smtp.timeout", TIMEOUT_MILLIS);
        props.put("mail.smtp.writetimeout", TIMEOUT_MILLIS);
        if (usesImplicitSsl()) {
            // Port 465 speaks TLS from the first byte; STARTTLS (587) negotiates it mid-session.
            // Using the wrong one for the port hangs until timeout, so pick based on the port.
            props.put("mail.smtp.ssl.enable", "true");
        } else {
            props.put("mail.smtp.starttls.enable", String.valueOf(config.useTls));
        }

        Session session = Session.getInstance(props, new jakarta.mail.Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.username, normalizedPassword());
            }
        });

        try {
            MimeMessage message = new MimeMessage(session);
            String from = isBlank(config.fromAddress) ? config.username : config.fromAddress;
            message.setFrom(new InternetAddress(from));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject);
            message.setText(body);
            Transport.send(message);
        } catch (MessagingException e) {
            throw new EmailSendException(explain(e), e);
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
            "If you're reading this, your SMTP settings work. Verification and password-reset"
                + " emails will be delivered normally.");
    }

    private boolean usesImplicitSsl() {
        return config.port == 465;
    }

    /**
     * Gmail displays App Passwords in four space-separated groups ("abcd efgh ijkl mnop").
     * Pasting them verbatim is the expected user behaviour, but the spaces are display-only
     * and make SMTP AUTH fail, so they're stripped here.
     */
    private String normalizedPassword() {
        return config.password == null ? "" : config.password.replaceAll("\\s+", "");
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
