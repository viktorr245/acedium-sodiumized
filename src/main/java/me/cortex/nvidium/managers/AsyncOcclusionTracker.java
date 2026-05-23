package me.cortex.nvidium.managers;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import me.cortex.nvidium.sodiumCompat.IRenderSectionExtension;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateType;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionFlags;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.Thread.MAX_PRIORITY;

public class AsyncOcclusionTracker {
    private static final int MAX_REBUILD_PRIORITY_BUCKETS = 128;

    private final OcclusionCuller occlusionCuller;
    private final Thread cullThread;
    private final World world;

    private volatile boolean running = true;
    private volatile int frame = 0;
    private volatile Viewport viewport = null;

    private final Semaphore framesAhead = new Semaphore(0);

    private final AtomicReference<List<RenderSection>> atomicBfsResult = new AtomicReference<>();
    private final AtomicReference<List<RenderSection>> blockEntitySectionsRef = new AtomicReference<>(new ArrayList<>());
    private final AtomicReference<Sprite[]> visibleAnimatedSpritesRef = new AtomicReference<>();

    private final Map<ChunkUpdateType, ArrayDeque<RenderSection>> outputRebuildQueue;
    private final List<RenderSection>[] rebuildPriorityBuckets;

    private final float renderDistance;
    private volatile long iterationTimeMillis;
    private volatile boolean shouldUseOcclusionCulling = true;

    private volatile int chunkVisibilityCount = 0;

    public AsyncOcclusionTracker(int renderDistance, Long2ReferenceMap<RenderSection> sections, World world, Map<ChunkUpdateType, ArrayDeque<RenderSection>> outputRebuildQueue) {
        this.occlusionCuller = new OcclusionCuller(sections, world);
        this.renderDistance = renderDistance * 16f;
        this.outputRebuildQueue = outputRebuildQueue;
        this.world = world;
        this.rebuildPriorityBuckets = createRebuildPriorityBuckets(renderDistance);

        this.cullThread = new Thread(this::run);
        this.cullThread.setName("Cull thread");
        this.cullThread.setPriority(MAX_PRIORITY);
        this.cullThread.start();
    }

    @SuppressWarnings("unchecked")
    private static List<RenderSection>[] createRebuildPriorityBuckets(int renderDistance) {
        var buckets = new List[Math.min(renderDistance + 1, MAX_REBUILD_PRIORITY_BUCKETS)];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new ArrayList<RenderSection>();
        }
        return buckets;
    }

    private void run() {

        while (running) {
            framesAhead.acquireUninterruptibly();
            if (!running) break;
            Viewport viewport = this.viewport;
            if (viewport == null) {
                continue;
            }

            long startTime = System.currentTimeMillis();

            final boolean animateVisibleSpritesOnly = SodiumClientMod.options().performance.animateOnlyVisibleTextures;
            //The reason for batching is so that ordering is strongly defined
            List<RenderSection> chunkUpdates = new ArrayList<>();
            List<RenderSection> blockEntitySections = new ArrayList<>();
            Set<Sprite> animatedSpriteSet = animateVisibleSpritesOnly?new HashSet<>():null;
            int[] visibleGeometryCounter = new int[1];
            final OcclusionCuller.Visitor visitor = (OcclusionCuller.Visitor) Proxy.newProxyInstance(
                    OcclusionCuller.Visitor.class.getClassLoader(),
                    new Class[]{OcclusionCuller.Visitor.class},
                    (proxy, method, args) -> {
                        if ("visit".equals(method.getName()) && args != null && args.length >= 1) {
                            this.visitSection(
                                    (RenderSection) args[0],
                                    viewport,
                                    chunkUpdates,
                                    blockEntitySections,
                                    animatedSpriteSet,
                                    visibleGeometryCounter,
                                    animateVisibleSpritesOnly
                            );
                            return null;
                        }

                        if ("toString".equals(method.getName())) {
                            return "AsyncOcclusionTrackerVisitor";
                        }

                        if ("hashCode".equals(method.getName())) {
                            return System.identityHashCode(proxy);
                        }

                        if ("equals".equals(method.getName())) {
                            return args != null && args.length > 0 && proxy == args[0];
                        }

                        return null;
                    }
            );

            frame++;
            float searchDistance = this.getSearchDistance();
            boolean useOcclusionCulling = this.shouldUseOcclusionCulling;
            try {
                this.occlusionCuller.findVisible(visitor, viewport, searchDistance, useOcclusionCulling, frame);
            } catch (Throwable e) {
                System.err.println("Error doing traversal");
                e.printStackTrace();
            }

            if (!chunkUpdates.isEmpty()) {
                this.prioritizeChunkUpdates(chunkUpdates, viewport);
                var previous = atomicBfsResult.getAndSet(chunkUpdates);
                if (previous != null) {
                    //We need to cleanup our state from a previous iteration
                    for (var section : previous) {
                        if (section.isDisposed())
                            continue;
                        //Reset that it hasnt been seen
                        ((IRenderSectionExtension) section).isSeen(false);
                    }
                }
            }
            this.chunkVisibilityCount = visibleGeometryCounter[0];
            blockEntitySectionsRef.set(blockEntitySections);
            visibleAnimatedSpritesRef.set(animatedSpriteSet==null?null:animatedSpriteSet.toArray(new Sprite[0]));
            iterationTimeMillis = System.currentTimeMillis() - startTime;
        }
    }

    private void prioritizeChunkUpdates(List<RenderSection> chunkUpdates, Viewport viewport) {
        if (chunkUpdates.size() < 2) {
            return;
        }

        BlockPos cameraBlock = viewport.getBlockCoord();
        int cameraChunkX = cameraBlock.getX() >> 4;
        int cameraChunkY = cameraBlock.getY() >> 4;
        int cameraChunkZ = cameraBlock.getZ() >> 4;
        int maxBucket = this.rebuildPriorityBuckets.length - 1;

        for (RenderSection section : chunkUpdates) {
            int distance = Math.max(
                    Math.max(Math.abs(section.getChunkX() - cameraChunkX), Math.abs(section.getChunkZ() - cameraChunkZ)),
                    Math.abs(section.getChunkY() - cameraChunkY)
            );
            this.rebuildPriorityBuckets[Math.min(distance, maxBucket)].add(section);
        }

        int index = 0;
        for (List<RenderSection> bucket : this.rebuildPriorityBuckets) {
            for (RenderSection section : bucket) {
                chunkUpdates.set(index++, section);
            }
            bucket.clear();
        }
    }

    private void visitSection(RenderSection section, Viewport viewport, List<RenderSection> chunkUpdates, List<RenderSection> blockEntitySections, @Nullable Set<Sprite> animatedSpriteSet, int[] visibleGeometryCounter, boolean animateVisibleSpritesOnly) {
        var extension = (IRenderSectionExtension)section;
        var cancellationToken = section.getTaskCancellationToken();
        var cancelledTask = cancellationToken != null && cancellationToken.isCancelled();

        if (cancelledTask || (section.getPendingUpdate() != null && cancellationToken == null)) {
            if (!extension.isSeen()) {
                //Set that the section has been seen
                extension.isSeen(true);
                chunkUpdates.add(section);
            }
        }

        if ((section.getFlags()&(1<<RenderSectionFlags.HAS_BLOCK_GEOMETRY))!=0) {
            visibleGeometryCounter[0]++;
        }

        if ((section.getFlags()&(1<<RenderSectionFlags.HAS_BLOCK_ENTITIES))!=0 &&
                section.getPosition().isWithinDistance(viewport.getChunkCoord(),33)) {//32 rd max chunk distance
            blockEntitySections.add(section);
        }
        if (animateVisibleSpritesOnly && (section.getFlags()&(1<<RenderSectionFlags.HAS_ANIMATED_SPRITES)) != 0 &&
                section.getPosition().isWithinDistance(viewport.getChunkCoord(),33)) {//32 rd max chunk distance (i.e. only animate sprites up to 32 chunks away)
            var animatedSprites = section.getAnimatedSprites();
            if (animatedSprites != null) {
                animatedSpriteSet.addAll(List.of(animatedSprites));
            }
        }
    }

    public final void update(Viewport viewport, Camera camera, boolean spectator) {
        this.shouldUseOcclusionCulling = this.shouldUseOcclusionCulling(camera, spectator);

        this.viewport = viewport;

        if (framesAhead.availablePermits() < 5) {//This stops a runaway when the traversal time is greater than frametime
            framesAhead.release();
        }

        var bfsResult = atomicBfsResult.getAndSet(null);
        if (bfsResult != null) {
            for (var section : bfsResult) {
                if (section.isDisposed())
                    continue;
                var cancelledTask = this.clearCancelledTask(section);
                var type = section.getPendingUpdate();
                if (cancelledTask && type == null) {
                    // Sodium drops cancelled jobs without producing a result, so retry the lost build.
                    type = ChunkUpdateType.REBUILD;
                    section.setPendingUpdate(type);
                }
                if (type != null && section.getTaskCancellationToken() == null) {
                    var queue = outputRebuildQueue.get(type);
                    if (queue != null && !queue.contains(section) && queue.size() < type.getMaximumQueueSize()) {
                        ((IRenderSectionExtension) section).isSubmittedRebuild(true);
                        queue.add(section);
                    }
                }
                //Reset that the section has not been seen (whether its been submitted to the queue or not)
                ((IRenderSectionExtension) section).isSeen(false);
            }
        }
    }

    private boolean clearCancelledTask(RenderSection section) {
        var cancellationToken = section.getTaskCancellationToken();
        if (cancellationToken == null || !cancellationToken.isCancelled()) {
            return false;
        }

        section.setTaskCancellationToken(null);
        ((IRenderSectionExtension) section).isSubmittedRebuild(false);
        return true;
    }

    public void delete() {
        running = false;
        framesAhead.release(1000);
        try {
            cullThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


    private float getSearchDistance() {
        return renderDistance;
    }

    private float getSearchDistance2() {
        float distance;
        if (SodiumClientMod.options().performance.useFogOcclusion) {
            distance = this.getEffectiveRenderDistance();
        } else {
            distance = this.getRenderDistance();
        }

        return distance;
    }

    private boolean shouldUseOcclusionCulling(Camera camera, boolean spectator) {
        BlockPos origin = camera.getBlockPos();
        boolean useOcclusionCulling;
        if (spectator && this.world.getBlockState(origin).isOpaqueFullCube(this.world, origin)) {
            useOcclusionCulling = false;
        } else {
            useOcclusionCulling = MinecraftClient.getInstance().chunkCullingEnabled;
        }

        return useOcclusionCulling;
    }

    private float getEffectiveRenderDistance() {
        float[] color = RenderSystem.getShaderFogColor();
        float distance = RenderSystem.getShaderFogEnd();
        float renderDistance = this.getRenderDistance();
        return !MathHelper.approximatelyEquals(color[3], 1.0F) ? renderDistance : Math.min(renderDistance, distance + 0.5F);
    }

    private float getRenderDistance() {
        return (float)this.renderDistance;
    }

    public int getFrame() {
        return frame;
    }

    public List<RenderSection> getLatestSectionsWithEntities() {
        return blockEntitySectionsRef.get();
    }

    @Nullable
    public Sprite[] getVisibleAnimatedSprites() {
        return visibleAnimatedSpritesRef.get();
    }

    public long getIterationTime() {
        return this.iterationTimeMillis;
    }

    public int[] getBuildQueueSizes() {
        var ret = new int[this.outputRebuildQueue.size()];
        for (var type : ChunkUpdateType.values()) {
            ret[type.ordinal()] = this.outputRebuildQueue.get(type).size();
        }
        return ret;
    }

    public int getLastVisibilityCount() {
        return this.chunkVisibilityCount;
    }
}
