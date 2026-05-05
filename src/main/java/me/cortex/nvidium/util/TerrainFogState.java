package me.cortex.nvidium.util;

import com.mojang.blaze3d.systems.RenderSystem;

public final class TerrainFogState {
    private static final float[] color = new float[4];
    private static float start;
    private static float end;
    private static int shapeId;
    private static boolean captured;

    private TerrainFogState() {
    }

    public static void captureFromRenderSystem() {
        float[] currentColor = RenderSystem.getShaderFogColor();
        System.arraycopy(currentColor, 0, color, 0, Math.min(currentColor.length, color.length));
        start = RenderSystem.getShaderFogStart();
        end = RenderSystem.getShaderFogEnd();
        shapeId = RenderSystem.getShaderFogShape().getId();
        captured = true;
    }

    public static float[] getColor() {
        return captured ? color : RenderSystem.getShaderFogColor();
    }

    public static float getStart() {
        return captured ? start : RenderSystem.getShaderFogStart();
    }

    public static float getEnd() {
        return captured ? end : RenderSystem.getShaderFogEnd();
    }

    public static int getShapeId() {
        return captured ? shapeId : RenderSystem.getShaderFogShape().getId();
    }
}
