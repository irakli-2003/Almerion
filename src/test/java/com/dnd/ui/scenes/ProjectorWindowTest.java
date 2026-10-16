package com.dnd.ui.scenes;

import com.dnd.model.world.map.MonsterToken;
import com.dnd.model.world.map.PlayerToken;
import org.junit.Test;

import static org.junit.Assert.*;

public class ProjectorWindowTest {

    @Test
    public void onlyPlayerAbilityRangesReachTheProjector() {
        assertTrue(ProjectorWindow.isPlayerRange(new PlayerToken(), 6));
        assertFalse(ProjectorWindow.isPlayerRange(new MonsterToken(), 6));
        assertFalse(ProjectorWindow.isPlayerRange(new PlayerToken(), 0));
        assertFalse(ProjectorWindow.isPlayerRange(null, 6));
    }

    @Test
    public void mapFitsTheScreen() {
        assertEquals(40.0, ProjectorWindow.fitCellSize(1920, 1080, 30, 27), 0.001);
    }
}
