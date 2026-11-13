package com.dnd.chat;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class SessionChatTest {
    private SessionChat chat() {
        return new SessionChat(() -> List.of(new SessionChat.Peer("a", "Alice"), new SessionChat.Peer("b", "Bob"),
            new SessionChat.Peer("c", "Cara")));
    }
    private SessionChat.Command create(String... peers) {
        return new SessionChat.Command(UUID.randomUUID().toString(), "create", null, List.of(peers), "Private group", "");
    }
    private SessionChat.Command send(String thread, String text) {
        return new SessionChat.Command(UUID.randomUUID().toString(), "send", thread, List.of(), "", text);
    }

    @Test public void privateAndGroupConversationsAreFilteredButCurrentHostCanMonitorWithoutImpersonation() {
        try (var chat = chat()) {
            String direct = chat.execute("a", create("b")).selected();
            chat.execute("a", send(direct, "Secret to Bob"));
            assertTrue(chat.execute("c", SessionChat.Command.poll(null)).conversations().isEmpty());
            assertThrows(SecurityException.class, () -> chat.execute("c", SessionChat.Command.poll(direct)));
            assertThrows(SecurityException.class, () -> chat.execute("c", send(direct, "intrusion")));
            var monitor = chat.execute(SessionChat.HOST, SessionChat.Command.poll(direct));
            assertEquals("Secret to Bob", monitor.messages().getFirst().text());
            assertThrows(SecurityException.class, () -> chat.execute(SessionChat.HOST, send(direct, "hidden GM")));
            String group = chat.execute("a", create("b", "c")).selected();
            chat.execute("c", send(group, "Group reply"));
            assertEquals(1, chat.execute("c", SessionChat.Command.poll(null)).conversations().size());
            assertEquals("Group reply", chat.execute("b", SessionChat.Command.poll(group)).messages().getFirst().text());
            assertEquals(2, chat.execute(SessionChat.HOST, SessionChat.Command.poll(null)).conversations().size());
        }
    }

    @Test public void unreadCountsOnlyClearOnOpeningAndRetriesDoNotDuplicateMessagesOrConversations() {
        try (var chat = chat()) {
            var create = create("b");
            String thread = chat.execute("a", create).selected();
            assertEquals(thread, chat.execute("a", create).selected());
            assertEquals(thread, chat.execute("b", create("a")).selected());
            var message = send(thread, "Once");
            chat.execute("a", message);
            chat.execute("a", message);
            assertEquals(1, chat.execute("b", SessionChat.Command.poll(null)).conversations().getFirst().unread());
            assertEquals(1, chat.execute("b", SessionChat.Command.poll(thread)).messages().size());
            assertEquals(0, chat.execute("b", SessionChat.Command.poll(null)).conversations().getFirst().unread());
            assertThrows(IllegalArgumentException.class, () -> chat.execute("a",
                new SessionChat.Command(message.id(), "send", thread, List.of(), "", "Changed retry")));
        }
    }

    @Test public void retainsExactlyLatestFiveHundredAndErasesEverythingOnSessionEnd() {
        var chat = chat();
        String thread = chat.execute("a", create("b")).selected();
        for (int i = 0; i < 501; i++) chat.execute("a", send(thread, "Message " + i));
        var view = chat.execute("b", SessionChat.Command.poll(thread));
        assertEquals(500, view.messages().size());
        assertEquals("Message 1", view.messages().getFirst().text());
        assertEquals("Message 500", view.messages().getLast().text());
        chat.close();
        assertThrows(IllegalStateException.class, () -> chat.execute("a", SessionChat.Command.poll(null)));
        try (var fresh = chat()) { assertTrue(fresh.execute("a", SessionChat.Command.poll(null)).conversations().isEmpty()); }
    }
}
