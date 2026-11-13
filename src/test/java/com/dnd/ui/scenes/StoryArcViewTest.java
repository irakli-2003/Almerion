package com.dnd.ui.scenes;

import com.dnd.data.StorylineService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertTrue;

/**
 * Regression test for a session file with a long name spilling its label outside its
 * timeline rectangle: the bar height must always grow to fit the (possibly wrapped) label.
 */
public class StoryArcViewTest {

    @BeforeClass
    public static void startToolkit() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyRunning) {
            ready.countDown();
        }
        assertTrue("JavaFX toolkit did not start", ready.await(20, TimeUnit.SECONDS));
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void onFxThread(ThrowingRunnable work) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                work.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue("FX task timed out", done.await(20, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw failure.get() instanceof Exception e ? e : new RuntimeException(failure.get());
        }
    }

    @Test
    public void barsGrowTallEnoughToContainAWrappedLongFileName() throws Exception {
        Path root = Files.createTempDirectory("dnd-story-arc-test");
        StorylineService service = new StorylineService(root);
        service.ensureRoot();
        service.createFile(service.getRoot(), "A Very Long Session Name That Should Wrap Across Several Lines.md");

        onFxThread(() -> {
            Pane pane = StoryArcView.build(service, service.getRoot());

            Rectangle bar = firstRectangle(pane);
            Text label = firstText(pane);
            assertTrue("must have rendered a bar and a label", bar != null && label != null);

            double labelHeight = label.getLayoutBounds().getHeight();
            assertTrue("the rectangle must be tall enough to contain its (possibly wrapped) label",
                bar.getHeight() >= labelHeight);
        });
    }

    private static Rectangle firstRectangle(Node root) {
        if (root instanceof Rectangle r) return r;
        if (root instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                Rectangle found = firstRectangle(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Text firstText(Node root) {
        if (root instanceof Text t) return t;
        if (root instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                Text found = firstText(child);
                if (found != null) return found;
            }
        }
        return null;
    }
}
