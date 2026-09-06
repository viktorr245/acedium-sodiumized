package me.cortex.nvidium.util;

/** Shared interpretation of the saved region retention setting (in chunks). */
public final class RegionKeepDistance {
    public static final int VANILLA = 32;
    public static final int KEEP_ALL = 256;

    private RegionKeepDistance() {}

    public static boolean retainsUnloadedSections(int configured, int renderDistance) {
        return configured == KEEP_ALL || (configured != VANILLA && configured > renderDistance);
    }

    public static boolean hasFiniteRetention(int configured, int renderDistance) {
        return configured != KEEP_ALL && retainsUnloadedSections(configured, renderDistance);
    }

    public static float fogDistance(int configured, float vanillaDistance) {
        if (configured == KEEP_ALL) return Math.max(vanillaDistance, 9999999f);
        if (configured == VANILLA) return vanillaDistance;
        return Math.max(vanillaDistance, configured * 16f);
    }

    public static float farPlaneDistance(int configured, float vanillaFarPlane) {
        if (configured == VANILLA) return vanillaFarPlane;
        // Minecraft uses four times its view distance for the projection far plane.
        // Keep that margin for retained regions too, including diagonal views.
        return Math.max(vanillaFarPlane, configured * 16f * 4f);
    }
}
