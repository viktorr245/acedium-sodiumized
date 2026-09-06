package me.cortex.nvidium.compat;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.function.Supplier;

/** Render-thread scope for offscreen cameras. Restores the enclosing view even on failure. */
public final class SecondaryRenderContext {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private SecondaryRenderContext() {}

    public static boolean isActive() {
        return CURRENT.get() != null;
    }

    public static int depth() {
        Scope scope = CURRENT.get();
        return scope == null ? 0 : scope.depth;
    }

    /** Captures a component once per view; its returned action restores that component. */
    public static void isolate(Object component, Supplier<Runnable> capture) {
        Scope scope = CURRENT.get();
        if (scope != null && !scope.components.containsKey(component)) {
            Runnable restore = capture.get();
            scope.components.put(component, Boolean.TRUE);
            scope.restorations.add(restore);
        }
    }

    public static void run(Runnable render) {
        Scope parent = CURRENT.get();
        Scope scope = new Scope(parent == null ? 1 : parent.depth + 1);
        CURRENT.set(scope);
        try (scope) {
            render.run();
        } finally {
            if (parent == null) CURRENT.remove();
            else CURRENT.set(parent);
        }
    }

    private static final class Scope implements AutoCloseable {
        final int depth;
        final IdentityHashMap<Object, Boolean> components = new IdentityHashMap<>();
        final ArrayList<Runnable> restorations = new ArrayList<>();

        Scope(int depth) {
            this.depth = depth;
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            for (int i = restorations.size() - 1; i >= 0; i--) {
                try {
                    restorations.get(i).run();
                } catch (RuntimeException e) {
                    if (failure == null) failure = e;
                    else failure.addSuppressed(e);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
