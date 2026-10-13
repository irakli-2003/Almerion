package com.dnd.data;

import com.dnd.model.magic.Spell;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class EntityDescriptionsTest {
    private final Map<String, String> names = Map.of("fire bolt", "FB", "fire", "F", "rope", "R");

    @Test
    public void mentionAtPrefersTheLongestWholeWordName() {
        String text = "He casts Fire Bolt at the rope.";
        assertEquals("FB", EntityDescriptions.mentionAt(text, text.indexOf("Fire") + 1, names));
        assertEquals("FB", EntityDescriptions.mentionAt(text, text.indexOf("Bolt") + 1, names));
        assertEquals("R", EntityDescriptions.mentionAt(text, text.indexOf("rope") + 2, names));
        assertNull(EntityDescriptions.mentionAt(text, text.indexOf("casts"), names));
    }

    @Test
    public void mentionAtIgnoresPartsOfLongerWords() {
        String text = "The fireplace and the ropes.";
        assertNull(EntityDescriptions.mentionAt(text, 5, names));
        assertNull(EntityDescriptions.mentionAt(text, text.indexOf("ropes") + 1, names));
    }

    @Test
    public void spellBodyListsComponents() {
        Spell s = new Spell();
        s.setName("Fire Bolt");
        s.setLevel(0);
        s.setDescription("A mote of fire.");
        s.setComponents(List.of("V", "S", "M"));
        s.setMaterialComponent("a pinch of sulfur");
        String body = new EntityDescriptions(id -> null, id -> null, id -> null).spellBody(s);
        assertTrue(body, body.contains("sulfur"));
        assertTrue(body, body.contains("A mote of fire."));
    }
}
