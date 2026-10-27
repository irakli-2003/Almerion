package com.dnd.github;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Encrypts the GitHub token with Windows DPAPI ("CurrentUser" scope), so the stored file is
 * useless to other Windows accounts and on other computers. Java has no DPAPI binding, so this
 * runs the built-in Windows PowerShell; the secret travels over stdin/stdout, never the
 * command line. Any failure makes {@link TokenStore} fall back to its plain-text file.
 */
final class WindowsDpapi implements TokenStore.Protector {
    private static final String SCRIPT = "$ErrorActionPreference='Stop';"
        + "Add-Type -AssemblyName System.Security;"
        + "$in=[Console]::In.ReadToEnd().Trim();"
        + "$s=[Security.Cryptography.DataProtectionScope]::CurrentUser;"
        + "if('%s' -eq 'protect'){"
        + "$b=[Security.Cryptography.ProtectedData]::Protect([Text.Encoding]::UTF8.GetBytes($in),$null,$s);"
        + "[Console]::Out.Write([Convert]::ToBase64String($b))}"
        + "else{"
        + "$b=[Security.Cryptography.ProtectedData]::Unprotect([Convert]::FromBase64String($in),$null,$s);"
        + "[Console]::Out.Write([Convert]::ToBase64String($b))}";

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    @Override
    public String name() {
        return "dpapi";
    }

    @Override
    public String protect(String secret) throws IOException {
        return run("protect", secret);
    }

    @Override
    public String unprotect(String stored) throws IOException {
        return new String(Base64.getDecoder().decode(run("unprotect", stored)), StandardCharsets.UTF_8);
    }

    private static String run(String mode, String input) throws IOException {
        String encoded = Base64.getEncoder().encodeToString(
            String.format(SCRIPT, mode).getBytes(StandardCharsets.UTF_16LE));
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
            "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(input.getBytes(StandardCharsets.UTF_8));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream stdout = process.getInputStream()) {
            stdout.transferTo(out);
        }
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("DPAPI helper timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        String result = out.toString(StandardCharsets.US_ASCII).trim();
        if (process.exitValue() != 0 || result.isEmpty()) throw new IOException("DPAPI helper failed");
        return result;
    }
}
