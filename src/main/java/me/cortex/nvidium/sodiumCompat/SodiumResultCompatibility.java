package me.cortex.nvidium.sodiumCompat;

import it.unimi.dsi.fastutil.longs.LongArrays;
import net.minecraft.client.MinecraftClient;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryUtil;

public class SodiumResultCompatibility {
    private static final int FACING_COUNT = 7;

    public static RepackagedSectionOutput repackage(ChunkBuildOutput result) {
        int formatSize = NvidiumCompactChunkVertex.STRIDE;
        int geometryBytes = result.meshes.values().stream().mapToInt(a->a.getVertexData().getLength()).sum();
        var output = new NativeBuffer(geometryBytes);
        var offsets = new short[8];
        var min = new Vector3i(2000);
        var max = new Vector3i(-2000);
        packageSectionGeometry(formatSize, output, offsets, result, min, max);

        Vector3i size;
        {
            min.x = Math.max(min.x, 0);
            min.y = Math.max(min.y, 0);
            min.z = Math.max(min.z, 0);
            min.x = Math.min(min.x, 15);
            min.y = Math.min(min.y, 15);
            min.z = Math.min(min.z, 15);

            max.x = Math.min(max.x, 16);
            max.y = Math.min(max.y, 16);
            max.z = Math.min(max.z, 16);
            max.x = Math.max(max.x, 0);
            max.y = Math.max(max.y, 0);
            max.z = Math.max(max.z, 0);

            size =  new Vector3i(max.x - min.x - 1, max.y - min.y - 1, max.z - min.z - 1);

            size.x = Math.min(15, Math.max(size.x, 0));
            size.y = Math.min(15, Math.max(size.y, 0));
            size.z = Math.min(15, Math.max(size.z, 0));
        }
        var repackagedGeometry = new RepackagedSectionOutput((geometryBytes/formatSize)/4, output, offsets, min, size);
        //NvidiumGeometryReencoder.transpileGeometry(repackagedGeometry);
        return repackagedGeometry;
    }


    private static void copyQuad(long from, long too) {
        //Quads are 64 bytes big
        for (long i = 0; i < 64; i += 8) {
            MemoryUtil.memPutLong(too + i, MemoryUtil.memGetLong(from + i));
        }
    }

    //Everything is /6*4 cause its in indices and we want verticies
    private static void packageSectionGeometry(int formatSize, NativeBuffer output, short[] outOffsets, ChunkBuildOutput result, Vector3i min, Vector3i max) {
        int offset = 0;

        long outPtr = MemoryUtil.memAddress(output.getDirectBuffer());
        //NOTE: mutates the input translucent geometry

        var cameraPos = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();

        float cpx = (float) (cameraPos.x - (result.render.getChunkX()<<4));
        float cpy = (float) (cameraPos.y - (result.render.getChunkY()<<4));
        float cpz = (float) (cameraPos.z - (result.render.getChunkZ()<<4));

        {//Project the camera pos onto the bounding outline of the chunk (-8 -> 24 for each axis)
            float len = (float) Math.sqrt(cpx*cpx + cpy*cpy + cpz*cpz);
            cpx *= 1/len;
            cpy *= 1/len;
            cpz *= 1/len;

            //The max range of the camera can be is like 32 blocks away so just use that
            len = Math.min(len, 32);

            cpx *= len;
            cpy *= len;
            cpz *= len;
        }

        //Do translucent first
        var translucentData = result.meshes.get(DefaultTerrainRenderPasses.TRANSLUCENT);
        if (translucentData != null) {
            var translucentSegments = getSegmentLayout(translucentData);
            int quadCount = 0;
            for (int i = 0; i < FACING_COUNT; i++) {
                quadCount += translucentSegments.vertexCounts[i] / 4;
            }
            int quadId = 0;
            long[] sortingData = new long[quadCount];
            long[] srcs = new long[FACING_COUNT];
            long translucentBase = MemoryUtil.memAddress(translucentData.getVertexData().getDirectBuffer());
            for (int i = 0; i < FACING_COUNT; i++) {
                int part = translucentSegments.vertexCounts[i];
                long src = translucentBase + (long) translucentSegments.sourceOffsets[i] * formatSize;
                srcs[i] = src;

                float cx = 0;
                float cy = 0;
                float cz = 0;

                for (int j = 0; j < part; j++) {
                    long base = src + (long) j * formatSize;

                    float x = decodePosition(MemoryUtil.memGetShort(base));
                    float y = decodePosition(MemoryUtil.memGetShort(base + 2));
                    float z = decodePosition(MemoryUtil.memGetShort(base + 4));
                    updateSectionBounds(min, max, x, y, z);

                    cx += x;
                    cy += y;
                    cz += z;

                    if ((j & 3) == 3) {
                        cx *= 0.25f;
                        cy *= 0.25f;
                        cz *= 0.25f;

                        float dx = cx - cpx;
                        float dy = cy - cpy;
                        float dz = cz - cpz;

                        float dist = dx * dx + dy * dy + dz * dz;
                        int sortDistance = (int) (dist * (1 << 12));

                        long packedSortingData = (((long) sortDistance) << 32) | ((((long) j >> 2) << 3) | i);
                        sortingData[quadId++] = packedSortingData;

                        cx = 0;
                        cy = 0;
                        cz = 0;
                    }
                }

            }

            if (quadId != sortingData.length) {
                throw new IllegalStateException();
            }

            LongArrays.radixSort(sortingData);

            for (int i = 0; i < sortingData.length; i++) {
                long data = sortingData[i];
                copyQuad(srcs[(int) (data&7)] + ((data>>3)&((1L<<29)-1))*4*formatSize, outPtr + ((sortingData.length-1)-i) * 4L * formatSize);
            }


            offset += quadCount;
        }

        outOffsets[7] = (short) offset;


        var solid = result.meshes.get(DefaultTerrainRenderPasses.SOLID);
        var cutout = result.meshes.get(DefaultTerrainRenderPasses.CUTOUT);
        SegmentLayout solidSegments = solid != null ? getSegmentLayout(solid) : null;
        SegmentLayout cutoutSegments = cutout != null ? getSegmentLayout(cutout) : null;

        //Do all but translucent
        long solidBase = solid != null ? MemoryUtil.memAddress(solid.getVertexData().getDirectBuffer()) : 0;
        long cutoutBase = cutout != null ? MemoryUtil.memAddress(cutout.getVertexData().getDirectBuffer()) : 0;
        for (int i = 0; i < FACING_COUNT; i++) {
            int poff = offset;
            if (solid != null) {
                int part = solidSegments.vertexCounts[i];
                if (part > 0) {
                    long src = solidBase + (long) solidSegments.sourceOffsets[i] * formatSize;
                    long dst = outPtr + offset * 4L * formatSize;
                    MemoryUtil.memCopy(src, dst, (long) part * formatSize);

                    for (int j = 0; j < part; j++) {
                        long base = dst + (long) j * formatSize;
                        updateSectionBounds(min, max, base);
                    }

                    offset += part / 4;
                }
            }
            if (cutout != null) {
                int part = cutoutSegments.vertexCounts[i];
                if (part > 0) {
                    long src = cutoutBase + (long) cutoutSegments.sourceOffsets[i] * formatSize;
                    long dst = outPtr + offset * 4L * formatSize;
                    MemoryUtil.memCopy(src, dst, (long) part * formatSize);

                    for (int j = 0; j < part; j++) {
                        long base = dst + (long) j * formatSize;
                        updateSectionBounds(min, max, base);
                    }

                    offset += part / 4;
                }
            }
            outOffsets[i] = (short) (offset - poff);
        }

        if (offset*4*formatSize != output.getLength()) {
            throw new IllegalStateException();
        }
    }

    private static SegmentLayout getSegmentLayout(BuiltSectionMeshParts mesh) {
        int[] vertexCounts = new int[FACING_COUNT];
        int[] sourceOffsets = new int[FACING_COUNT];

        int sourceOffset = 0;
        int[] vertexSegments = mesh.getVertexSegments();
        for (int i = 0; i < vertexSegments.length; i += 2) {
            int vertexCount = vertexSegments[i];
            if (vertexCount == 0) {
                continue;
            }

            int facing = vertexSegments[i + 1];
            if (facing < 0 || facing >= FACING_COUNT) {
                throw new IllegalStateException("Unexpected mesh facing index: " + facing);
            }

            vertexCounts[facing] = vertexCount;
            sourceOffsets[facing] = sourceOffset;
            sourceOffset += vertexCount;
        }

        return new SegmentLayout(vertexCounts, sourceOffsets);
    }

    private record SegmentLayout(int[] vertexCounts, int[] sourceOffsets) {
    }


    private static float decodePosition(short v) {
        return Short.toUnsignedInt(v)*(1f/2048.0f)-8.0f;
    }

    private static void updateSectionBounds(Vector3i min, Vector3i max, long vertex) {
        float x = decodePosition(MemoryUtil.memGetShort(vertex));
        float y = decodePosition(MemoryUtil.memGetShort(vertex + 2));
        float z = decodePosition(MemoryUtil.memGetShort(vertex + 4));
        updateSectionBounds(min, max, x, y, z);
    }

    private static void updateSectionBounds(Vector3i min, Vector3i max, float x, float y, float z) {
        min.x = (int) Math.min(min.x, Math.floor(x));
        min.y = (int) Math.min(min.y, Math.floor(y));
        min.z = (int) Math.min(min.z, Math.floor(z));

        max.x = (int) Math.max(max.x, Math.ceil(x));
        max.y = (int) Math.max(max.y, Math.ceil(y));
        max.z = (int) Math.max(max.z, Math.ceil(z));
    }
}
