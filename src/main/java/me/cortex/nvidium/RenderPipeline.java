package me.cortex.nvidium;

import me.cortex.nvidium.util.RegionKeepDistance;
import net.minecraft.client.MinecraftClient;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.ints.*;
import me.cortex.nvidium.compat.SecondaryRenderContext;
import me.cortex.nvidium.config.StatisticsLoggingLevel;
import me.cortex.nvidium.config.TranslucencySortingLevel;
import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.gl.buffers.IDeviceMappedBuffer;
import me.cortex.nvidium.managers.RegionManager;
import me.cortex.nvidium.managers.RegionVisibilityTracker;
import me.cortex.nvidium.managers.SectionManager;
import me.cortex.nvidium.renderers.*;
import me.cortex.nvidium.util.DownloadTaskStream;
import me.cortex.nvidium.util.TerrainFogState;
import me.cortex.nvidium.util.TickableManager;
import me.cortex.nvidium.util.UploadingBufferStream;
import net.minecraft.util.math.BlockPos;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import org.joml.*;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

import java.lang.Math;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import static me.cortex.nvidium.gl.buffers.PersistentSparseAddressableBuffer.alignUp;
import static org.lwjgl.opengl.ARBDirectStateAccess.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL32C.GL_DEPTH_CLAMP;
import static org.lwjgl.opengl.GL30C.GL_R8UI;
import static org.lwjgl.opengl.GL30C.GL_RED_INTEGER;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.NVRepresentativeFragmentTest.GL_REPRESENTATIVE_FRAGMENT_TEST_NV;
import static org.lwjgl.opengl.NVShaderBufferStore.GL_SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV;
import static org.lwjgl.opengl.NVUniformBufferUnifiedMemory.GL_UNIFORM_BUFFER_ADDRESS_NV;
import static org.lwjgl.opengl.NVUniformBufferUnifiedMemory.GL_UNIFORM_BUFFER_UNIFIED_NV;
import static org.lwjgl.opengl.NVVertexBufferUnifiedMemory.*;


//TODO: extract out sectionManager, uploadStream, downloadStream and other funky things to an auxiliary parent NvidiumWorldRenderer class
public class RenderPipeline {
    public static final int GL_DRAW_INDIRECT_UNIFIED_NV = 0x8F40;
    public static final int GL_DRAW_INDIRECT_ADDRESS_NV = 0x8F41;

    private final RenderDevice device;
    private final UploadingBufferStream uploadStream;
    private final DownloadTaskStream downloadStream;

    private final SectionManager sectionManager;

    public final RegionVisibilityTracker regionVisibilityTracking;

    private PrimaryTerrainRasterizer terrainRasterizer;
    private RegionRasterizer regionRasterizer;
    private SectionRasterizer sectionRasterizer;
    private TemporalTerrainRasterizer temporalRasterizer;
    private TranslucentTerrainRasterizer translucencyTerrainRasterizer;
    private SortRegionSectionPhase regionSectionSorter;

    private static final int SCENE_SIZE = (int) alignUp(4*4*4+4*4+4*4+4+4*4+4*4+8*8+3*4+3+4+8, 2);

    private final ViewState mainView;
    private ViewState view;
    private final ArrayList<ViewState> secondaryViews = new ArrayList<>();
    private TranslucentTerrainRasterizer secondaryTranslucencyRasterizer;

    /** Visibility, commands and uniforms belong to a camera, not to shared terrain geometry. */
    private final class ViewState {
        final IDeviceMappedBuffer sceneUniform, regionVisibility, sectionVisibility;
        final IDeviceMappedBuffer terrainCommandBuffer, translucencyCommandBuffer;
        final IDeviceMappedBuffer regionSortingList, statisticsBuffer;
        final BitSet regionVisibilityTracker = new BitSet();
        final IntSet regionsToSort = new IntOpenHashSet();
        final Statistics stats = new Statistics();
        int prevRegionCount, frameId;

        ViewState() {
            int maxRegions = sectionManager.getRegionManager().maxRegions();
            sceneUniform = device.createDeviceOnlyMappedBuffer(SCENE_SIZE + maxRegions * 2L);
            regionVisibility = device.createDeviceOnlyMappedBuffer(maxRegions);
            sectionVisibility = device.createDeviceOnlyMappedBuffer(maxRegions * 256L);
            terrainCommandBuffer = device.createDeviceOnlyMappedBuffer(maxRegions * 8L);
            translucencyCommandBuffer = device.createDeviceOnlyMappedBuffer(maxRegions * 8L);
            regionSortingList = device.createDeviceOnlyMappedBuffer(maxRegions * 2L);
            statisticsBuffer = device.createDeviceOnlyMappedBuffer(16);
            clear();
        }

        void clear() {
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
            prevRegionCount = 0;
            frameId = 0;
            regionVisibilityTracker.clear();
            regionsToSort.clear();
            for (var buffer : new IDeviceMappedBuffer[]{regionVisibility, sectionVisibility, statisticsBuffer}) {
                nglClearNamedBufferData(buffer.getId(), GL_R8UI, GL_RED_INTEGER, GL_UNSIGNED_BYTE, 0);
            }
        }

        void delete() {
            for (var buffer : new IDeviceMappedBuffer[]{sceneUniform, regionVisibility, sectionVisibility,
                    terrainCommandBuffer, translucencyCommandBuffer, regionSortingList, statisticsBuffer}) {
                buffer.delete();
            }
        }
    }

    private void selectView() {
        SecondaryRenderContext.isolate(this, () -> {
            ViewState previous = view;
            int index = SecondaryRenderContext.depth() - 1;
            while (secondaryViews.size() <= index) secondaryViews.add(new ViewState());
            ViewState next = secondaryViews.get(index);
            // Sequential mirrors at the same depth may have entirely different cameras.
            next.clear();
            view = next;
            return () -> view = previous;
        });
    }

    private final IDeviceMappedBuffer transformationArray;
    private final IDeviceMappedBuffer originOffsetArray;



    private static final class Statistics {
        public int frustumCount;
        public int regionCount;
        public int sectionCount;
        public int quadCount;
    }


    public RenderPipeline(RenderDevice device, UploadingBufferStream uploadStream, DownloadTaskStream downloadStream, SectionManager sectionManager) {
        this.device = device;
        this.uploadStream = uploadStream;
        this.downloadStream = downloadStream;
        this.sectionManager = sectionManager;

        terrainRasterizer = new PrimaryTerrainRasterizer();
        regionRasterizer = new RegionRasterizer();
        sectionRasterizer = new SectionRasterizer();
        temporalRasterizer = new TemporalTerrainRasterizer();
        translucencyTerrainRasterizer = new TranslucentTerrainRasterizer();
        regionSectionSorter = new SortRegionSectionPhase();

        mainView = new ViewState();
        view = mainView;

        this.transformationArray = device.createDeviceOnlyMappedBuffer(RegionManager.MAX_TRANSFORMATION_COUNT * (4*4*4));
        this.originOffsetArray = device.createDeviceOnlyMappedBuffer(RegionManager.MAX_TRANSFORMATION_COUNT * 8);

        regionVisibilityTracking = new RegionVisibilityTracker(downloadStream, sectionManager.getRegionManager().maxRegions());



        //Initialize the transformationArray buffer to the identity affine transform
        {
            long ptr = this.uploadStream.upload(this.transformationArray, 0, RegionManager.MAX_TRANSFORMATION_COUNT * (4*4*4));
            var transform = new Matrix4f().identity();
            for (int i = 0; i < RegionManager.MAX_TRANSFORMATION_COUNT; i++) {
                transform.getToAddress(ptr);
                ptr += 4*4*4;
            }
        }
        //Clear the origin offset
        nglClearNamedBufferData(this.originOffsetArray.getId(), GL_R8UI, GL_RED_INTEGER, GL_UNSIGNED_BYTE, 0);


    }

    //TODO: FIXME: optimize this so that multiple uploads just upload a single time per frame!!!
    // THIS IS CRITICAL
    public void setTransformation(int id, Matrix4fc transform) {
        if (id < 0 || id >= RegionManager.MAX_TRANSFORMATION_COUNT) {
            throw new IllegalArgumentException("Id out of bounds: " + id);
        }
        long ptr = this.uploadStream.upload(this.transformationArray, id * (4*4*4), 4*4*4);
        transform.getToAddress(ptr);
    }

    //TODO: FIXME: optimize this so that multiple uploads just upload a single time per frame!!!
    // THIS IS CRITICAL
    public void setOrigin(int id, int x, int y, int z) {
        if (id < 0 || id >= RegionManager.MAX_TRANSFORMATION_COUNT) {
            throw new IllegalArgumentException("Id out of bounds: " + id);
        }
        long ptr = this.uploadStream.upload(this.originOffsetArray, id * 8, 8);
        long pos = 0;
        pos |= x&0x1ffffff;
        pos |= ((long)(z&0x1ffffff))<<25;
        pos |= ((long)(y&0x3fff))<<50;

        MemoryUtil.memPutLong(ptr, pos);
    }


    //ISSUE TODO: regions that where in frustum but are now out of frustum must have the visibility data cleared
    // this is due to funny issue of pain where the section was "visible" last frame cause it didnt get ticked
    public void renderFrame(Viewport frustum, ChunkRenderMatrices crm, double px, double py, double pz) {//NOTE: can use any of the command list rendering commands to basicly draw X indirects using the same shader, thus allowing for terrain to be rendered very efficently

        selectView();
        boolean secondary = SecondaryRenderContext.isActive();
        if (sectionManager.getRegionManager().regionCount() == 0) {
            view.prevRegionCount = 0;
            return;
        }//Dont render anything if there is nothing to render

        final int DEBUG_RENDER_LEVEL = 0;//0: no debug, 1: region debug, 2: section debug
        final boolean WRITE_DEPTH = false;

        /*
        for (int i = 0; i <3*3*3;i++) {
            new NvidiumAPI("nvidium").setRegionTransformId(1, i%3, (i/3)%3, ((i/3)/3)%3);
        }
        new NvidiumAPI("nvidium").setTransformation(1, new Matrix4f().identity().scale(1,1  ,1));
        new NvidiumAPI("nvidium").setOrigin(1, 0,0,0);
           */

        Vector3i blockPos = new Vector3i(((int)Math.floor(px)), ((int)Math.floor(py)), ((int)Math.floor(pz)));
        Vector3i chunkPos = new Vector3i(blockPos.x>>4,blockPos.y>>4,blockPos.z>>4);
        //  /tp @p 0.0 -1.62 0.0 0 0
        //Clear the first gl error, not our fault
        //glGetError();

        int visibleRegions = 0;

        long queryAddr = 0;
        var rm = sectionManager.getRegionManager();
        short[] regionMap;
        //Enqueue all the visible regions
        {

            //The region data indicies is located at the end of the view.sceneUniform
            IntSortedSet regions = new IntAVLTreeSet();
            boolean finiteRetention = RegionKeepDistance.hasFiniteRetention(
                    Nvidium.config.region_keep_distance, MinecraftClient.getInstance().options.getClampedViewDistance());
            for (int i = 0; i < rm.maxRegionIndex(); i++) {
                if (!rm.regionExists(i)) continue;
                if (!secondary && finiteRetention && !rm.withinSquare(Nvidium.config.region_keep_distance+4, i, chunkPos.x, chunkPos.y, chunkPos.z)) {
                    removeRegion(i);
                    continue;
                }
                //TODO: fog culling/region removal cause with bobby the removal distance is huge and people run out of vram very fast


                if (rm.isRegionVisible(frustum, i)) {
                    //Note, its sorted like this because of overdraw, also the translucency command buffer is written to
                    // in a reverse order to this in the section_raster/task.glsl shader
                    regions.add(((rm.distance(i, chunkPos.x, chunkPos.y, chunkPos.z))<<16)|i);
                    visibleRegions++;
                    view.regionVisibilityTracker.set(i);

                    if (!secondary && rm.isRegionInACameraAxis(i, px, py, pz)) {
                        view.regionsToSort.add(i);
                    }

                } else {
                    if (view.regionVisibilityTracker.get(i)) {//Going from visible to non visible
                        //Clear the visibility bits
                        if (Nvidium.config.enable_temporal_coherence) {
                            nglClearNamedBufferSubData(view.sectionVisibility.getId(), GL_R8UI, (long) i << 8, 255, GL_RED_INTEGER, GL_UNSIGNED_BYTE, 0);
                        }
                    }
                    view.regionVisibilityTracker.clear(i);
                }

            }

            regionMap = new short[regions.size()];
            if (visibleRegions == 0) {
                view.prevRegionCount = 0;
                return;
            }
            long addr = uploadStream.upload(view.sceneUniform, SCENE_SIZE, visibleRegions*2);
            queryAddr = addr;//This is ungodly hacky
            int j = 0;
            for (int i : regions) {
                regionMap[j] = (short) i;
                MemoryUtil.memPutShort(addr+((long) j <<1), (short) i);
                j++;
            }

            if (Nvidium.config.statistics_level != StatisticsLoggingLevel.NONE) {
                view.stats.frustumCount = regions.size();
            }
        }

        {
            //TODO: maybe segment the uniform buffer into 2 parts, always updating and static where static holds pointers
            Vector3f delta = new Vector3f((float) (px-(chunkPos.x<<4)), (float) (py-(chunkPos.y<<4)), (float) (pz-(chunkPos.z<<4)));
            delta.negate();
            long addr = uploadStream.upload(view.sceneUniform, 0, SCENE_SIZE);
            var mvp =new Matrix4f(crm.projection())
                    .mul(crm.modelView())
                    .translate(delta)//Translate the subchunk position
                    .getToAddress(addr);
            addr += 4*4*4;
            new Vector4i(chunkPos.x, chunkPos.y, chunkPos.z, 0).getToAddress(addr);//Chunk the camera is in//TODO: THIS
            addr += 16;
            new Vector4f(delta,0).getToAddress(addr);//Subchunk offset (note, delta is already negated)
            addr += 16;
            new Vector4f(TerrainFogState.getColor()).getToAddress(addr);
            addr += 16;
            MemoryUtil.memPutLong(addr, view.sceneUniform.getDeviceAddress() + SCENE_SIZE);//Put in the location of the region indexs
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionManager.getRegionManager().getRegionBufferAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionManager.getRegionManager().getSectionBufferAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, view.regionVisibility.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, view.sectionVisibility.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, view.terrainCommandBuffer.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, view.translucencyCommandBuffer.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, view.regionSortingList.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, sectionManager.terrainAreana.buffer.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, this.transformationArray.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, this.originOffsetArray.getDeviceAddress());
            addr += 8;
            MemoryUtil.memPutLong(addr, view.statisticsBuffer == null?0:view.statisticsBuffer.getDeviceAddress());//Logging buffer
            addr += 8;
            MemoryUtil.memPutFloat(addr, TerrainFogState.getStart());//FogStart
            addr += 4;
            MemoryUtil.memPutFloat(addr, TerrainFogState.getEnd());//FogEnd
            addr += 4;
            MemoryUtil.memPutInt(addr, TerrainFogState.getShapeId());//IsSphericalFog
            addr += 4;
            MemoryUtil.memPutShort(addr, (short) visibleRegions);
            addr += 2;
            MemoryUtil.memPutByte(addr, (byte) (view.frameId++));
        }

        if (Nvidium.config.translucency_sorting_level == TranslucencySortingLevel.NONE) {
            view.regionsToSort.clear();
        }

        int regionSortSize = view.regionsToSort.size();

        if (regionSortSize != 0){
            long regionSortUpload = uploadStream.upload(view.regionSortingList, 0, regionSortSize * 2);
            for (int region : view.regionsToSort) {
                MemoryUtil.memPutShort(regionSortUpload, (short) region);
                regionSortUpload += 2;
            }
            view.regionsToSort.clear();
        }

        sectionManager.commitChanges();//Commit all uploads done to the terrain and meta data
        uploadStream.commit();

        if (!secondary) TickableManager.TickAll();

        //if ((err = glGetError()) != 0) {
        //    throw new IllegalStateException("GLERROR: "+err);
        //}


        glEnableClientState(GL_UNIFORM_BUFFER_UNIFIED_NV);
        glEnableClientState(GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        glEnableClientState(GL_ELEMENT_ARRAY_UNIFIED_NV);
        glEnableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);
        //Bind the uniform, it doesnt get wiped between shader changes
        glBufferAddressRangeNV(GL_UNIFORM_BUFFER_ADDRESS_NV, 0, view.sceneUniform.getDeviceAddress(), SCENE_SIZE);

        if (!secondary && view.prevRegionCount != 0) {
            glEnable(GL_DEPTH_TEST);
            terrainRasterizer.raster(view.prevRegionCount, view.terrainCommandBuffer.getDeviceAddress());
            glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT);
        }

        //NOTE: For GL_REPRESENTATIVE_FRAGMENT_TEST_NV to work, depth testing must be disabled, or depthMask = false
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(false);
        if (DEBUG_RENDER_LEVEL == 1 && WRITE_DEPTH) {
            glDepthMask(true);
        }
        if (DEBUG_RENDER_LEVEL != 1) {
            glColorMask(false, false, false, false);
        }
        if (DEBUG_RENDER_LEVEL == 0)
        {
            glEnable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
        }

        // Vista's off-axis near plane can cut away the visible faces of a bounding box
        // without cutting away all terrain inside it. Clamp only the visibility proxies;
        // actual terrain must still clip at the mirror plane.
        boolean restoreDepthClamp = secondary && !glIsEnabled(GL_DEPTH_CLAMP);
        if (restoreDepthClamp) glEnable(GL_DEPTH_CLAMP);

        try {
            regionRasterizer.raster(visibleRegions);

            if (DEBUG_RENDER_LEVEL == 1) {
                glColorMask(false, false, false, false);
            }

            //glMemoryBarrier(GL_SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

            //glColorMask(true, true, true, true);

            if (DEBUG_RENDER_LEVEL == 2) {
                glColorMask(true, true, true, true);
            }
            if (DEBUG_RENDER_LEVEL == 2 && WRITE_DEPTH) {
                glDepthMask(true);
            }

            sectionRasterizer.raster(visibleRegions);
        } finally {
            if (restoreDepthClamp) glDisable(GL_DEPTH_CLAMP);
        }
        glDisable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
        glDepthMask(true);
        glColorMask(true, true, true, true);

        //glMemoryBarrier(GL_SHADER_GLOBAL_ACCESS_BARRIER_BIT_NV);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);


        view.prevRegionCount = visibleRegions;

        //Do temporal rasterization
        if (secondary) {
            // A reflection has no valid previous frame. Draw its current visibility immediately,
            // including when temporal coherence is disabled in the user's settings.
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT);
            terrainRasterizer.raster(visibleRegions, view.terrainCommandBuffer.getDeviceAddress());
        } else if (Nvidium.config.enable_temporal_coherence) {
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT);
            temporalRasterizer.raster(visibleRegions, view.terrainCommandBuffer.getDeviceAddress());
        }


        if (!secondary) {//Only the main camera influences residency scores
            glDepthMask(false);
            glColorMask(false, false, false, false);
            glEnable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);

            regionVisibilityTracking.computeVisibility(visibleRegions, view.regionVisibility, regionMap);

            glDisable(GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
            glDepthMask(true);
            glColorMask(true, true, true, true);
        }



        if (regionSortSize != 0) {
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            regionSectionSorter.dispatch(regionSortSize);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        }

        glDisableClientState(GL_UNIFORM_BUFFER_UNIFIED_NV);
        glDisableClientState(GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        glDisableClientState(GL_ELEMENT_ARRAY_UNIFIED_NV);
        glDisableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);
        glDepthFunc(GL11C.GL_LEQUAL);
        glDisable(GL_DEPTH_TEST);


        //if ((err = glGetError()) != 0) {
        //    throw new IllegalStateException("GLERROR: "+err);
        //}
    }

    void enqueueRegionSort(int regionId) {
        mainView.regionsToSort.add(regionId);
    }

    //TODO: refactor to different location
    private boolean removeRegion(int id) {
        if (id < 0 || !sectionManager.getRegionManager().regionExists(id)) {
            return false;
        }
        sectionManager.removeRegionById(id);
        regionVisibilityTracking.resetRegion(id);
        return true;
    }

    //TODO: refactor out of the render pipeline along with regionVisibilityTracking and removeRegion and statistics
    public boolean removeARegion() {
        var regionManager = sectionManager.getRegionManager();
        int regionId = regionVisibilityTracking.findMostLikelyLeastSeenRegion(regionManager.maxRegionIndex());
        if (removeRegion(regionId)) {
            return true;
        }

        for (int i = 0; i < regionManager.maxRegionIndex(); i++) {
            if (removeRegion(i)) {
                return true;
            }
        }

        return false;
    }

    /*
    private void setRegionVisible(long rid) {
        glClearNamedBufferSubData(view.regionVisibility.getId(), GL_R8UI, rid, 1, GL_RED_INTEGER, GL_UNSIGNED_BYTE, new int[]{(byte)(1)});
    }*/

    //Translucency is rendered in a very cursed and incorrect way
    // it hijacks the unassigned indirect command dispatch and uses that to dispatch the translucent chunks as well
    public void renderTranslucent() {
        selectView();
        if (view.prevRegionCount == 0) return;
        glEnableClientState(GL_UNIFORM_BUFFER_UNIFIED_NV);
        glEnableClientState(GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        glEnableClientState(GL_ELEMENT_ARRAY_UNIFIED_NV);
        glEnableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);
        //Need to rebind the uniform since it might have been wiped
        glBufferAddressRangeNV(GL_UNIFORM_BUFFER_ADDRESS_NV, 0, view.sceneUniform.getDeviceAddress(), SCENE_SIZE);

        //Translucency sorting
        {
            glEnable(GL_DEPTH_TEST);
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA);
            if (SecondaryRenderContext.isActive()) {
                if (secondaryTranslucencyRasterizer == null) {
                    secondaryTranslucencyRasterizer = new TranslucentTerrainRasterizer(true);
                }
                secondaryTranslucencyRasterizer.raster(view.prevRegionCount, view.translucencyCommandBuffer.getDeviceAddress());
            } else {
                translucencyTerrainRasterizer.raster(view.prevRegionCount, view.translucencyCommandBuffer.getDeviceAddress());
            }
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            glDisable(GL_DEPTH_TEST);
        }

        glDisableClientState(GL_UNIFORM_BUFFER_UNIFIED_NV);
        glDisableClientState(GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        glDisableClientState(GL_ELEMENT_ARRAY_UNIFIED_NV);
        glDisableClientState(GL_DRAW_INDIRECT_UNIFIED_NV);




        if (SecondaryRenderContext.isActive()) return;

        //Download statistics
        if (Nvidium.config.statistics_level.ordinal() > StatisticsLoggingLevel.FRUSTUM.ordinal()){
            downloadStream.download(view.statisticsBuffer, 0, 4*4, (addr)-> {
                mainView.stats.regionCount = MemoryUtil.memGetInt(addr);
                mainView.stats.sectionCount = MemoryUtil.memGetInt(addr+4);
                mainView.stats.quadCount = MemoryUtil.memGetInt(addr+8);
            });
        }


        if (Nvidium.config.statistics_level.ordinal() > StatisticsLoggingLevel.FRUSTUM.ordinal()) {
            //glMemoryBarrier(GL_ALL_BARRIER_BITS);
            //Stupid bloody nvidia not following spec forcing me to use a upload stream
            long upload = this.uploadStream.upload(view.statisticsBuffer, 0, 4*4);
            MemoryUtil.memSet(upload, 0, 4*4);
            //glClearNamedBufferSubData(view.statisticsBuffer.getId(), GL_R32UI, 0, 4 * 4, GL_RED_INTEGER, GL_UNSIGNED_INT, new int[]{0});
        }
    }

    public void delete() {
        regionVisibilityTracking.delete();

        mainView.delete();
        secondaryViews.forEach(ViewState::delete);
        secondaryViews.clear();
        if (secondaryTranslucencyRasterizer != null) secondaryTranslucencyRasterizer.delete();

        terrainRasterizer.delete();
        regionRasterizer.delete();
        sectionRasterizer.delete();
        temporalRasterizer.delete();
        translucencyTerrainRasterizer.delete();
        regionSectionSorter.delete();
        this.transformationArray.delete();
        this.originOffsetArray.delete();


    }

    public void addDebugInfo(List<String> info) {
        if (Nvidium.config.statistics_level != StatisticsLoggingLevel.NONE) {
            StringBuilder builder = new StringBuilder();
            builder.append("Statistics: ");
            if (Nvidium.config.statistics_level.ordinal() >=  StatisticsLoggingLevel.FRUSTUM.ordinal()) {
                builder.append("F: ").append(view.stats.frustumCount);
            }
            if (Nvidium.config.statistics_level.ordinal() >=  StatisticsLoggingLevel.REGIONS.ordinal()) {
                builder.append(", R: ").append(view.stats.regionCount);
            }
            if (Nvidium.config.statistics_level.ordinal() >=  StatisticsLoggingLevel.SECTIONS.ordinal()) {
                builder.append(", S: ").append(view.stats.sectionCount);
            }
            if (Nvidium.config.statistics_level.ordinal() >=  StatisticsLoggingLevel.QUADS.ordinal()) {
                builder.append(", Q: ").append(view.stats.quadCount);
            }
            info.addAll(List.of(builder.toString().split("\n")));
        }
    }

    public void reloadShaders() {
        if (secondaryTranslucencyRasterizer != null) {
            secondaryTranslucencyRasterizer.delete();
            secondaryTranslucencyRasterizer = null;
        }
        terrainRasterizer.delete();
        regionRasterizer.delete();
        sectionRasterizer.delete();
        temporalRasterizer.delete();
        translucencyTerrainRasterizer.delete();
        regionSectionSorter.delete();

        terrainRasterizer = new PrimaryTerrainRasterizer();
        regionRasterizer = new RegionRasterizer();
        sectionRasterizer = new SectionRasterizer();
        temporalRasterizer = new TemporalTerrainRasterizer();
        translucencyTerrainRasterizer = new TranslucentTerrainRasterizer();
        regionSectionSorter = new SortRegionSectionPhase();
    }
}
