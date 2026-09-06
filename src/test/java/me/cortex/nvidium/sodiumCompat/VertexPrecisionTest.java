package me.cortex.nvidium.sodiumCompat;

import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.junit.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import static org.junit.Assert.*;

public class VertexPrecisionTest {
    private static final float STEP = 1.0f / 32768;

    private static ChunkVertexEncoder.Vertex[] quad(float x, float y, float z) {
        var vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();
        for (int i = 0; i < 4; i++) {
            var vertex = vertices[i];
            vertex.x = x;
            vertex.y = y;
            vertex.z = z;
            vertex.color = 0xFF563412;
            vertex.ao = 1;
            vertex.u = (i & 1) == 0 ? 0.25f : 0.5f;
            vertex.v = i < 2 ? 0.25f : 0.5f;
            vertex.light = 0x00F00020;
        }
        return vertices;
    }

    @Test
    public void preservesIronBarOffsetsAtEverySectionHeightAndAxis() {
        var buffer = ByteBuffer.allocateDirect(80);
        long ptr = MemoryUtil.memAddress(buffer);
        for (int local = 0; local < 16; local++) {
            float bottom = local + 0.001f / 16;
            float top = local + 15.999f / 16;
            for (float position : new float[]{bottom, top}) {
                NvidiumCompactChunkVertex.INSTANCE.getEncoder().write(ptr, 2, quad(position, position, position), 0);
                for (int axis = 0; axis < 3; axis++) {
                    float decoded = NvidiumCompactChunkVertex.decodePosition(ptr, axis);
                    assertTrue("Face must stay above the lower block plane", decoded > local);
                    assertTrue("Face must stay below the upper block plane", decoded < local + 1);
                    assertEquals(position, decoded, STEP);
                }
            }
        }
    }

    @Test
    public void roundTripsPositionsAcrossFormatRange() {
        var buffer = ByteBuffer.allocateDirect(80);
        long ptr = MemoryUtil.memAddress(buffer);
        var random = new Random(14);
        for (int i = 0; i < 1000; i++) {
            float x = -8 + random.nextFloat() * 32;
            float y = -8 + random.nextFloat() * 32;
            float z = -8 + random.nextFloat() * 32;
            NvidiumCompactChunkVertex.INSTANCE.getEncoder().write(ptr, 0, quad(x, y, z), 0);
            assertEquals(x, NvidiumCompactChunkVertex.decodePosition(ptr, 0), STEP);
            assertEquals(y, NvidiumCompactChunkVertex.decodePosition(ptr, 1), STEP);
            assertEquals(z, NvidiumCompactChunkVertex.decodePosition(ptr, 2), STEP);
        }
        for (float position : new float[]{-8, -1, 0, 1, 15, 16, Math.nextDown(24.0f)}) {
            NvidiumCompactChunkVertex.INSTANCE.getEncoder().write(ptr, 0, quad(position, position, position), 0);
            assertEquals(position, NvidiumCompactChunkVertex.decodePosition(ptr, 0), STEP);
        }
    }

    @Test
    public void writesCompleteVerticesWithoutOverwritingNeighbors() {
        var buffer = ByteBuffer.allocateDirect(96).order(ByteOrder.nativeOrder());
        long ptr = MemoryUtil.memAddress(buffer) + 8;
        buffer.putLong(0, 0x123456789ABCDEFL);
        buffer.putLong(88, 0xFEDCBA987654321L);
        for (int material = 0; material < 8; material++) {
            long end = NvidiumCompactChunkVertex.INSTANCE.getEncoder().write(ptr, material, quad(1, 2, 3), 255);
            assertEquals(ptr + 80, end);
            for (int i = 0; i < 4; i++) {
                long vertex = ptr + 20L * i;
                assertEquals(0x00563412, MemoryUtil.memGetInt(vertex + 8));
                assertEquals(0xFF000000 | (material << 16) | 0xF020, MemoryUtil.memGetInt(vertex + 16));
                int u = (i & 1) == 0 ? 8193 : 0xBFFF;
                int v = i < 2 ? 8193 : 0xBFFF;
                assertEquals(u | (v << 16), MemoryUtil.memGetInt(vertex + 12));
            }
        }
        assertEquals(0x123456789ABCDEFL, buffer.getLong(0));
        assertEquals(0xFEDCBA987654321L, buffer.getLong(88));
    }

    @Test
    public void copiesEveryByteOfTranslucentQuad() throws Exception {
        var source = ByteBuffer.allocateDirect(80);
        var target = ByteBuffer.allocateDirect(88);
        for (int i = 0; i < 80; i++) source.put(i, (byte) (i + 1));
        target.put(80, (byte) 99);
        var copy = SodiumResultCompatibility.class.getDeclaredMethod("copyQuad", long.class, long.class);
        copy.setAccessible(true);
        copy.invoke(null, MemoryUtil.memAddress(source), MemoryUtil.memAddress(target));
        for (int i = 0; i < 80; i++) assertEquals("byte " + i, source.get(i), target.get(i));
        assertEquals(99, target.get(80));
    }
}
