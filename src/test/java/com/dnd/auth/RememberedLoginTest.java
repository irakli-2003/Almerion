package com.dnd.auth;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class RememberedLoginTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void remembersOnlyEncryptedCredentialsAndCanForgetThem() throws IOException {
        var file = temp.getRoot().toPath().resolve("remembered-login.dpapi");
        var login = new RememberedLogin(file);
        assumeTrue(login.isSupported());
        assertTrue(login.load().isEmpty());
        String identifier = "test@example.invalid";
        String password = " test-\u10d0-password ";
        login.save(identifier, password);
        assertFalse(Files.readString(file).contains(identifier));
        assertFalse(Files.readString(file).contains("password"));
        assertEquals(new RememberedLogin.Credentials(identifier, password),
            new RememberedLogin(file).load().orElseThrow());
        login.clear();
        assertTrue(new RememberedLogin(file).load().isEmpty());
    }

    @Test public void unreadableCredentialsCanBeRemoved() throws IOException {
        var file = temp.newFile().toPath();
        var login = new RememberedLogin(file);
        assumeTrue(login.isSupported());
        Files.writeString(file, "not encrypted credentials");
        assertThrows(IOException.class, login::load);
        login.clear();
        assertTrue(login.load().isEmpty());
    }
}
