package com.dnd.cli.core;

import java.util.Scanner;

/**
 * Default {@link ConsoleIO} implementation backed by real {@code System.out}
 * and a {@link Scanner} over {@code System.in}.
 */
public class SystemConsoleIO implements ConsoleIO {
    private final Scanner scanner;

    public SystemConsoleIO(Scanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public void print(String text) {
        System.out.print(text);
    }

    @Override
    public void println(String text) {
        System.out.println(text);
    }

    @Override
    public void println() {
        System.out.println();
    }

    @Override
    public String readLine() {
        return scanner.nextLine();
    }

    @Override
    public String readSecret() {
        java.io.Console console = System.console();
        if (console == null) throw new IllegalStateException("Open Almerion in a real terminal to enter your password without echo.");
        char[] password = console.readPassword();
        if (password == null) throw new IllegalStateException("Password input was cancelled.");
        try {
            return new String(password);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }
}

