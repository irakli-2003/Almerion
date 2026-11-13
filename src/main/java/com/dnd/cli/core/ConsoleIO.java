package com.dnd.cli.core;

/**
 * Abstraction over interactive console input/output. Pages and services
 * depend on this interface instead of calling {@code System.out}/{@code System.in}
 * or {@link java.util.Scanner} directly.
 *
 * This is what makes CLI interaction logic unit-testable: tests can supply a
 * fake implementation that records output and replays scripted input, instead
 * of having to capture real stdout/stdin. It would also let a future
 * alternative front-end (e.g. a GUI or web API) reuse the same page/service
 * logic behind a different {@code ConsoleIO} implementation.
 */
public interface ConsoleIO {
    void print(String text);

    void println(String text);

    void println();

    /**
     * Reads a single line of input, blocking until one is available.
     */
    String readLine();

    default String readSecret() {
        throw new IllegalStateException("Password input requires a terminal with echo disabled.");
    }
}

