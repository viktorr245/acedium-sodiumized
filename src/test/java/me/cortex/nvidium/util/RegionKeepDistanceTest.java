package me.cortex.nvidium.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class RegionKeepDistanceTest {
    @Test
    public void lowerRetentionCannotShortenNormalTerrainRendering() {
        // Regression: RD 96 / keep 64 used to evict chunks 80-87 and shorten the far plane.
        assertFalse(RegionKeepDistance.hasFiniteRetention(64, 96));
        assertFalse(RegionKeepDistance.retainsUnloadedSections(64, 96));
        assertEquals(1536f, RegionKeepDistance.fogDistance(64, 1536f), 0f);
        assertEquals(6144f, RegionKeepDistance.farPlaneDistance(64, 6144f), 0f);
    }

    @Test
    public void equalDistancesUseNormalSectionLifetime() {
        assertFalse(RegionKeepDistance.hasFiniteRetention(64, 64));
        assertFalse(RegionKeepDistance.retainsUnloadedSections(64, 64));
    }

    @Test
    public void extraRetentionExtendsFogAndProjectionWithVanillaMargin() {
        assertTrue(RegionKeepDistance.hasFiniteRetention(64, 8));
        assertTrue(RegionKeepDistance.retainsUnloadedSections(64, 8));
        assertEquals(1024f, RegionKeepDistance.fogDistance(64, 128f), 0f);
        assertEquals(4096f, RegionKeepDistance.farPlaneDistance(64, 512f), 0f);
    }

    @Test
    public void vanillaSentinelDoesNotMeanThirtyTwoChunkRetention() {
        for (int rd : new int[]{2, 8, 32, 96}) {
            assertFalse(RegionKeepDistance.retainsUnloadedSections(32, rd));
            assertFalse(RegionKeepDistance.hasFiniteRetention(32, rd));
            assertEquals(rd * 16f, RegionKeepDistance.fogDistance(32, rd * 16f), 0f);
            assertEquals(rd * 64f, RegionKeepDistance.farPlaneDistance(32, rd * 64f), 0f);
        }
    }

    @Test
    public void keepAllRetainsItsSavedMeaningEvenAtLargerRenderDistances() {
        for (int rd : new int[]{8, 96, 256, 512}) {
            assertTrue(RegionKeepDistance.retainsUnloadedSections(256, rd));
            assertFalse(RegionKeepDistance.hasFiniteRetention(256, rd));
        }
    }

    @Test
    public void noSettingReducesExistingFogOrProjectionRange() {
        for (int keep = 32; keep <= 256; keep++) {
            for (int rd : new int[]{2, 8, 32, 64, 96, 256, 512}) {
                assertTrue(RegionKeepDistance.fogDistance(keep, rd * 16f) >= rd * 16f);
                assertTrue(RegionKeepDistance.farPlaneDistance(keep, rd * 64f) >= rd * 64f);
            }
        }
    }
}
