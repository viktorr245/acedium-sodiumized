package me.cortex.nvidium.compat;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class SecondaryRenderContextTest {
    @Test
    public void nestedViewsRestoreTheirParentAndCaptureOnlyOnce() {
        Object component = new Object();
        int[] camera = {0};
        Runnable selectCamera = () -> SecondaryRenderContext.isolate(component, () -> {
            int previous = camera[0];
            camera[0] = SecondaryRenderContext.depth();
            return () -> camera[0] = previous;
        });
        SecondaryRenderContext.run(() -> {
            selectCamera.run();
            selectCamera.run();
            assertEquals(1, camera[0]);
            SecondaryRenderContext.run(() -> {
                selectCamera.run();
                assertEquals(2, camera[0]);
            });
            assertEquals(1, camera[0]);
            assertEquals(1, SecondaryRenderContext.depth());
        });
        assertEquals(0, camera[0]);
        assertFalse(SecondaryRenderContext.isActive());
    }

    @Test
    public void renderingExceptionRestoresAllComponentsInReverseOrder() {
        List<Integer> restored = new ArrayList<>();
        RuntimeException renderFailure = new RuntimeException("render");
        RuntimeException cleanupFailure = new RuntimeException("cleanup");
        try {
            SecondaryRenderContext.run(() -> {
                SecondaryRenderContext.isolate(new Object(), () -> () -> restored.add(1));
                SecondaryRenderContext.isolate(new Object(), () -> () -> {
                    restored.add(2);
                    throw cleanupFailure;
                });
                throw renderFailure;
            });
            fail("Expected render failure");
        } catch (RuntimeException e) {
            assertSame(renderFailure, e);
            assertArrayEquals(new Throwable[]{cleanupFailure}, e.getSuppressed());
        }
        assertEquals(List.of(2, 1), restored);
        assertFalse(SecondaryRenderContext.isActive());
    }

    @Test
    public void sequentialViewsDoNotShareCaptureRegistrations() {
        Object component = new Object();
        int[] captures = {0}, restores = {0};
        Runnable render = () -> SecondaryRenderContext.isolate(component, () -> {
            captures[0]++;
            return () -> restores[0]++;
        });
        SecondaryRenderContext.run(render);
        SecondaryRenderContext.run(render);
        assertEquals(2, captures[0]);
        assertEquals(2, restores[0]);
    }

    @Test
    public void mainViewDoesNotCaptureOrAllocateSecondaryState() {
        SecondaryRenderContext.isolate(this, () -> {
            fail("Main camera must not enter secondary state");
            return () -> {};
        });
        assertEquals(0, SecondaryRenderContext.depth());
    }
}
