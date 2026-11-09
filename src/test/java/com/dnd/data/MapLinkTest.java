package com.dnd.data;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class MapLinkTest {

    @Test
    public void currentLocationNamesReplaceStaleTemplateLabelsWithoutChangingLinksOrProse() {
        String text = "Enter [map:castle|Temple]. Return to [map:castle]. Missing: [map:gone|Lost cave].";
        assertEquals("Enter [map:castle|Queen's Castle $5]. Return to [map:castle|Queen's Castle $5]."
                + " Missing: [map:gone|Lost cave].",
            MapLink.refreshLabels(text, java.util.Map.of("castle", "Queen's Castle $5")));
        assertEquals("", MapLink.refreshLabels(null, java.util.Map.of()));
        assertEquals(text, MapLink.refreshLabels(text, java.util.Map.of()));
    }

    @Test
    public void markerIncludesLabelWhenGiven() {
        assertEquals("[map:map-1|Old Mill]", MapLink.marker("map-1", "Old Mill"));
        assertEquals("[map:map-1]", MapLink.marker("map-1", null));
        assertEquals("[map:map-1]", MapLink.marker("map-1", "   "));
        assertEquals("", MapLink.marker(null, "x"));
    }

    @Test
    public void markerStripsCharactersThatWouldBreakParsing() {
        String marker = MapLink.marker("map-1", "Cave ] of | Doom");
        assertEquals("[map:map-1|Cave  of  Doom]", marker);
        assertEquals(1, MapLink.findAll(marker).size());
    }

    @Test
    public void findsEveryLinkInOrder() {
        String text = "See [map:a|Alpha] then fight at [map:b|Beta].";
        List<MapLink> links = MapLink.findAll(text);
        assertEquals(2, links.size());
        assertEquals("a", links.get(0).mapId());
        assertEquals("Alpha", links.get(0).label());
        assertEquals("b", links.get(1).mapId());
    }

    @Test
    public void labelDefaultsToTheIdWhenOmitted() {
        MapLink link = MapLink.findAll("go to [map:crypt]").get(0);
        assertEquals("crypt", link.mapId());
        assertEquals("crypt", link.label());
    }

    @Test
    public void findsNothingInPlainText() {
        assertTrue(MapLink.findAll("no links here [not:a-map]").isEmpty());
        assertTrue(MapLink.findAll(null).isEmpty());
        assertTrue(MapLink.findAll("").isEmpty());
    }

    @Test
    public void caretInsideOrAtEitherEdgeResolvesToTheLink() {
        String text = "abc [map:x|X] def";
        assertNull(MapLink.at(text, 0));
        assertNotNull(MapLink.at(text, 4));
        assertNotNull(MapLink.at(text, 8));
        assertNotNull(MapLink.at(text, 13));
        assertNull(MapLink.at(text, 15));
    }

    @Test
    public void stripMarkersLeavesReadableLabels() {
        assertEquals("Go to Old Mill now.", MapLink.stripMarkers("Go to [map:m1|Old Mill] now."));
        assertEquals("Go to crypt.", MapLink.stripMarkers("Go to [map:crypt]."));
        assertEquals("", MapLink.stripMarkers(null));
    }
}
