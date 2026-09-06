package me.cortex.nvidium.sodiumCompat;

import net.caffeinemc.mods.sodium.api.util.ColorABGR;
import net.caffeinemc.mods.sodium.api.util.ColorU8;
import net.caffeinemc.mods.sodium.client.gl.attribute.GlVertexFormat;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.system.MemoryUtil;

public class NvidiumCompactChunkVertex implements ChunkVertexType {
    public static final GlVertexFormat VERTEX_FORMAT = new GlVertexFormat(null, null, 20);

    public static final int STRIDE = 20;
    public static final NvidiumCompactChunkVertex INSTANCE = new NvidiumCompactChunkVertex();

    private static final int POSITION_MAX_VALUE = 1 << 20;
    public static final int TEXTURE_MAX_VALUE = 32768;

    private static final float MODEL_ORIGIN = 8.0f;
    private static final float MODEL_RANGE = 32.0f;

    @Override
    public GlVertexFormat getVertexFormat() {
        return VERTEX_FORMAT;
    }

    @Override
    public ChunkVertexEncoder getEncoder() {
        return (ptr, material, vertices, sectionIndex) -> {
            float centerU = 0.0f;
            float centerV = 0.0f;
            for (var vertex : vertices) {
                centerU += vertex.u;
                centerV += vertex.v;
            }
            centerU *= 0.25f;
            centerV *= 0.25f;

            for (var vertex : vertices) {
                int light = compactLight(vertex.light);
                int u = encodeTexture(centerU, vertex.u);
                int v = encodeTexture(centerV, vertex.v);

                int x = encodePosition(vertex.x);
                int y = encodePosition(vertex.y);
                int z = encodePosition(vertex.z);

                // Sodium's 20-byte layout: two interleaved 20-bit position words,
                // color, UVs, then light/material/section. Preserve tiny model offsets.
                MemoryUtil.memPutInt(ptr + 0, packPosition(x >>> 10, y >>> 10, z >>> 10));
                MemoryUtil.memPutInt(ptr + 4, packPosition(x, y, z));
                MemoryUtil.memPutInt(ptr + 8, encodeColor(vertex.color, vertex.ao));
                MemoryUtil.memPutInt(ptr + 12, packTexture(u, v));
                MemoryUtil.memPutInt(ptr + 16, light | (encodeDrawParameters(material) << 16) | ((sectionIndex & 0xFF) << 24));

                ptr += STRIDE;
            }

            return ptr;
        };
    }

    private static int compactLight(int light) {
        int sky = MathHelper.clamp((light >>> 16) & 0xFF, 8, 248);
        int block = MathHelper.clamp((light >>> 0) & 0xFF, 8, 248);

        return (block << 0) | (sky << 8);
    }

    private static int encodePosition(float v) {
        return ((int) (((MODEL_ORIGIN + v) / MODEL_RANGE) * POSITION_MAX_VALUE)) & 0xFFFFF;
    }

    private static int packPosition(int x, int y, int z) {
        return (x & 0x3FF) | ((y & 0x3FF) << 10) | ((z & 0x3FF) << 20);
    }

    // Must match decodeVertexPosition in terrain/vertex_format.glsl.
    static float decodePosition(long vertex, int axis) {
        int shift = axis * 10;
        int high = (MemoryUtil.memGetInt(vertex) >>> shift) & 0x3FF;
        int low = (MemoryUtil.memGetInt(vertex + 4) >>> shift) & 0x3FF;
        return ((high << 10) | low) * (MODEL_RANGE / POSITION_MAX_VALUE) - MODEL_ORIGIN;
    }

    private static int encodeDrawParameters(int material) {
        return (material & 0xFF) << 0;
    }

    private static int encodeColor(int color, float brightness) {
        int r = ColorU8.normalizedFloatToByte(ColorU8.byteToNormalizedFloat(ColorABGR.unpackRed(color)) * brightness);
        int g = ColorU8.normalizedFloatToByte(ColorU8.byteToNormalizedFloat(ColorABGR.unpackGreen(color)) * brightness);
        int b = ColorU8.normalizedFloatToByte(ColorU8.byteToNormalizedFloat(ColorABGR.unpackBlue(color)) * brightness);

        return ColorABGR.pack(r, g, b, 0x00);
    }

    private static int encodeTexture(float center, float value) {
        int bias = value < center ? 1 : -1;
        int quantized = Math.round(value * TEXTURE_MAX_VALUE) + bias;
        return (quantized & 0x7FFF) | (sign(bias) << 15);
    }

    private static int packTexture(int u, int v) {
        return ((u & 0xFFFF) << 0) | ((v & 0xFFFF) << 16);
    }

    private static int sign(int value) {
        return value >>> 31;
    }
}
