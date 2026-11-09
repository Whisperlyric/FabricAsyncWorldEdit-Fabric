/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.fabric;

import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.implementation.packet.ChunkPacket;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseItem;
import com.sk89q.worldedit.blocks.BaseItemStack;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.fabric.internal.ExtendedMinecraftServer;
import com.sk89q.worldedit.fabric.internal.FabricEntity;
import com.sk89q.worldedit.fabric.internal.FabricServerLevelDelegateProxy;
import com.sk89q.worldedit.fabric.internal.FabricWorldNativeAccess;
import com.sk89q.worldedit.fabric.internal.NBTConverter;
import com.sk89q.worldedit.function.mask.AbstractExtentMask;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.internal.Constants;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.util.TreeGenerator.TreeType;
import com.sk89q.worldedit.util.concurrency.LazyReference;
import com.sk89q.worldedit.util.io.file.SafeFiles;
import com.sk89q.worldedit.world.AbstractWorld;
import com.sk89q.worldedit.world.RegenOptions;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.generation.ConfiguredFeatureType;
import com.sk89q.worldedit.world.generation.StructureType;
import com.sk89q.worldedit.world.item.ItemTypes;
import com.sk89q.worldedit.world.weather.WeatherType;
import com.sk89q.worldedit.world.weather.WeatherTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.EndFeatures;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.Clearable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import org.enginehub.linbus.tree.LinCompoundTag;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * An adapter to Minecraft worlds for WorldEdit.
 */
public class FabricWorld extends AbstractWorld {


    private static Identifier getDimensionRegistryKey(Level world) {
        return Objects.requireNonNull(world.getServer(), "server cannot be null")
            .registryAccess()
            .lookupOrThrow(Registries.DIMENSION_TYPE)
            .getKey(world.dimensionType());
    }

    private final WeakReference<Level> worldRef;
    private final String worldNameRef;
    private final FabricWorldNativeAccess worldNativeAccess;

    /**
     * Construct a new world.
     *
     * @param world the world
     */
    FabricWorld(Level world) {
        checkNotNull(world);
        this.worldRef = new WeakReference<>(world);
        LevelData levelData = world.getLevelData();
        this.worldNameRef = levelData instanceof ServerLevelData serverLevelData
            ? serverLevelData.getLevelName()
            : "world";
        this.worldNativeAccess = new FabricWorldNativeAccess(worldRef);
    }

    /**
     * Get the underlying handle to the world.
     *
     * @return the world
     * @throws RuntimeException thrown if a reference to the world was lost (i.e. world was unloaded)
     */
    public Level getWorld() {
        Level world = worldRef.get();
        if (world != null) {
            return world;
        } else {
            throw new RuntimeException("The reference to the world was lost (i.e. the world may have been unloaded)");
        }
    }

    @Override
    public String getName() {
        LevelData levelProperties = getWorld().getLevelData();
        return ((ServerLevelData) levelProperties).getLevelName();
    }

    @Override
    public String getNameUnsafe() {
        return worldNameRef;
    }

    @Override
    public String id() {
        return getName() + "_" + getDimensionRegistryKey(getWorld());
    }

    @Override
    public Path getStoragePath() {
        final Level world = getWorld();
        MinecraftServer server = world.getServer();
        checkState(server instanceof ExtendedMinecraftServer, "Need a server world");
        return ((ExtendedMinecraftServer) server).fawe_fabric$getStoragePath(world);
    }

    @Override
    public <B extends BlockStateHolder<B>> boolean setBlock(BlockVector3 position, B block, SideEffectSet sideEffects) throws WorldEditException {
        clearContainerBlockContents(position);
        return worldNativeAccess.setBlock(position, block, sideEffects);
    }

    @Override
    public Set<SideEffect> applySideEffects(BlockVector3 position, BlockState previousType, SideEffectSet sideEffectSet) {
        worldNativeAccess.applySideEffects(position, previousType, sideEffectSet);
        return Sets.intersection(FabricWorldEdit.inst.getPlatform().getSupportedSideEffects(), sideEffectSet.getSideEffectsToApply());
    }

    @Override
    public int getBlockLightLevel(BlockVector3 position) {
        checkNotNull(position);
        return getWorld().getMaxLocalRawBrightness(FabricAdapter.toBlockPos(position));
    }

    @Override
    public boolean clearContainerBlockContents(BlockVector3 position) {
        checkNotNull(position);

        BlockEntity tile = getWorld().getBlockEntity(FabricAdapter.toBlockPos(position));
        if ((tile instanceof Clearable)) {
            ((Clearable) tile).clearContent();
            ChunkPackets.resendBlockEntity(tile);
            return true;
        }
        return false;
    }

    @Override
    public BiomeType getBiome(BlockVector3 position) {
        checkNotNull(position);
        ChunkAccess chunk = getWorld().getChunk(position.x() >> 4, position.z() >> 4);
        return getBiomeInChunk(position, chunk);
    }

    private BiomeType getBiomeInChunk(BlockVector3 position, ChunkAccess chunk) {
        return FabricAdapter.adapt(
            chunk.getNoiseBiome(position.x() >> 2, position.y() >> 2, position.z() >> 2).value()
        );
    }

    @Override
    public boolean setBiome(BlockVector3 position, BiomeType biome) {
        checkNotNull(position);
        checkNotNull(biome);

        ChunkAccess chunk = getWorld().getChunk(position.x() >> 4, position.z() >> 4);
        // Screw it, we know it's really mutable...
        var biomeArray = (PalettedContainer<Holder<Biome>>) chunk.getSection(chunk.getSectionIndex(position.y())).getBiomes();
        biomeArray.getAndSetUnchecked(
            position.x() & 3, position.y() & 3, position.z() & 3,
            getWorld().registryAccess().lookup(Registries.BIOME)
                .orElseThrow()
                .getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.parse(biome.id())))
        );
        chunk.markUnsaved();
        if (chunk instanceof LevelChunk levelChunk) {
            ChunkPackets.queueBiomeResend(levelChunk);
        }
        return true;
    }

    private static final LoadingCache<ServerLevel, FabricFakePlayer> fakePlayers
            = CacheBuilder.newBuilder().weakKeys().softValues().build(CacheLoader.from(FabricFakePlayer::new));

    @Override
    public boolean useItem(BlockVector3 position, BaseItem item, Direction face) {
        ItemStack stack = FabricAdapter.adapt(new BaseItemStack(item.getType(), item.getNbtReference(), 1));
        ServerLevel world = (ServerLevel) getWorld();
        final FabricFakePlayer fakePlayer;
        try {
            fakePlayer = fakePlayers.get(world);
        } catch (ExecutionException ignored) {
            return false;
        }
        fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, stack);
        fakePlayer.absSnapTo(position.x(), position.y(), position.z(),
                (float) face.toVector().toYaw(), (float) face.toVector().toPitch());
        final BlockPos blockPos = FabricAdapter.toBlockPos(position);
        final BlockHitResult rayTraceResult = new BlockHitResult(FabricAdapter.toVec3(position),
                FabricAdapter.adapt(face), blockPos, false);
        UseOnContext itemUseContext = new UseOnContext(fakePlayer, InteractionHand.MAIN_HAND, rayTraceResult);
        InteractionResult used = stack.useOn(itemUseContext);
        if (used != InteractionResult.SUCCESS) {
            // try activating the block
            used = getWorld().getBlockState(blockPos).useItemOn(stack, world, fakePlayer, InteractionHand.MAIN_HAND, rayTraceResult);
        }
        if (used != InteractionResult.SUCCESS) {
            used = stack.use(world, fakePlayer, InteractionHand.MAIN_HAND);
        }
        return used == InteractionResult.SUCCESS;
    }

    @Override
    public void dropItem(Vector3 position, BaseItemStack item) {
        checkNotNull(position);
        checkNotNull(item);

        if (item.getType() == ItemTypes.AIR) {
            return;
        }

        ItemEntity entity = new ItemEntity(getWorld(), position.x(), position.y(), position.z(), FabricAdapter.adapt(item));
        entity.setPickUpDelay(10);
        getWorld().addFreshEntity(entity);
    }

    @Override
    public void simulateBlockMine(BlockVector3 position) {
        BlockPos pos = FabricAdapter.toBlockPos(position);
        getWorld().destroyBlock(pos, true);
    }

    @Override
    public boolean canPlaceAt(BlockVector3 position, BlockState blockState) {
        return FabricAdapter.adapt(blockState).canSurvive(getWorld(), FabricAdapter.toBlockPos(position));
    }

    @Override
    public boolean regenerate(Region region, Extent extent, RegenOptions options) {
        // Don't even try to regen if it's going to fail.
        ChunkSource provider = getWorld().getChunkSource();
        if (!(provider instanceof ServerChunkCache)) {
            return false;
        }

        try {
            doRegen(region, extent, options);
        } catch (Exception e) {
            throw new IllegalStateException("Regen failed", e);
        }

        return true;
    }

    /**
     * Generates the selection in a temporary {@link ServerLevel} and copies the
     * resulting block states and biomes back into {@code extent}.
     *
     * <p>Chunks are read at {@link ChunkStatus#FEATURES}; seed overrides and
     * {@code //regen -b} are not supported.</p>
     */
    private static final long REGEN_TIMEOUT_MS = Long.getLong("fawe.regen.timeoutMs", 120_000L);

    private void doRegen(Region region, Extent extent, RegenOptions options) throws Exception {
        ServerLevel originalWorld = (ServerLevel) getWorld();
        Path tempDir = Files.createTempDirectory("WorldEditWorldGen");
        try {
            LevelStorageSource storage = LevelStorageSource.createDefault(tempDir);
            try (LevelStorageSource.LevelStorageAccess session = storage.createAccess("WorldEditTempGen")) {
                ServerLevelData levelData = (ServerLevelData) originalWorld.getLevelData();
                LevelStem stem = new LevelStem(
                        originalWorld.dimensionTypeRegistration(),
                        originalWorld.getChunkSource().getGenerator()
                );
                try (ServerLevel generatedWorld = new ServerLevel(
                        originalWorld.getServer(),
                        Util.backgroundExecutor(),
                        session,
                        levelData,
                        originalWorld.dimension(),
                        stem,
                        originalWorld.isDebug(),
                        originalWorld.getSeed(),
                        ImmutableList.of(),
                        false
                )) {
                    ServerChunkCache chunkSource = generatedWorld.getChunkSource();
                    // One chunk of margin: features of the border chunks reach into the
                    // selection and would be truncated without the neighbour.
                    CuboidRegion preGen = new CuboidRegion(
                            region.getMinimumPoint().subtract(16, 0, 16),
                            region.getMaximumPoint().add(16, 0, 16)
                    );
                    long deadline = System.currentTimeMillis() + REGEN_TIMEOUT_MS;
                    Map<Long, ChunkAccess> generatedChunks = new java.util.HashMap<>();
                    for (BlockVector2 chunk : preGen.getChunks()) {
                        CompletableFuture<ChunkResult<ChunkAccess>> load =
                                chunkSource.getChunkFuture(chunk.x(), chunk.z(), ChunkStatus.FEATURES, true);
                        while (true) {
                            ChunkResult<ChunkAccess> result = load.getNow(null);
                            if (result != null) {
                                if (!result.isSuccess()) {
                                    throw new IllegalStateException(
                                            "Chunk generation failed for " + chunk + ": " + result.getError());
                                }
                                generatedChunks.put(chunkKey(chunk.x(), chunk.z()), result.orElse(null));
                                break;
                            }
                            if (System.currentTimeMillis() > deadline) {
                                throw new IllegalStateException(
                                        "Timed out waiting for chunk generation at " + chunk
                                        + " (" + REGEN_TIMEOUT_MS + "ms)");
                            }
                            if (!chunkSource.pollTask()) {
                                // No task ready: release the CPU instead of hot-spinning.
                                java.util.concurrent.locks.LockSupport.parkNanos(200_000L);
                            }
                        }
                    }

                    net.minecraft.world.level.block.state.BlockState air =
                            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                    int minSectionY = generatedWorld.getMinY() >> 4;
                    for (BlockVector3 position : region) {
                        ChunkAccess access = generatedChunks.get(
                                chunkKey(position.x() >> 4, position.z() >> 4));
                        if (access == null) {
                            continue;
                        }
                        LevelChunkSection[] sections = access.getSections();
                        int offset = (position.y() >> 4) - minSectionY;
                        net.minecraft.world.level.block.state.BlockState nms = air;
                        if (offset >= 0 && offset < sections.length && sections[offset] != null) {
                            nms = sections[offset].getBlockState(
                                    position.x() & 15, position.y() & 15, position.z() & 15);
                        }
                        // Features at FEATURES status place no block entities, so a plain
                        // state (no NBT) is the same data vanilla //regen copies.
                        extent.setBlock(position, FabricAdapter.adapt(nms));
                        if (options.shouldRegenBiomes()) {
                            extent.setBiome(position, FabricAdapter.adapt(
                                    access.getNoiseBiome(
                                            position.x() >> 2, position.y() >> 2, position.z() >> 2).value()));
                        }
                    }
                }
            }
        } finally {
            SafeFiles.tryHardToDeleteDir(tempDir);
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    @Override
    public boolean generateTree(com.sk89q.worldedit.world.generation.TreeType type,
                                EditSession editSession,
                                BlockVector3 position) throws MaxChangedBlocksException {
        // 26.x addresses trees by configured-feature id (minecraft:oak, ...), which is
        // exactly what the keyed tree type carries. This is the overload the forest
        // brush and //tree use.
        return placeConfiguredFeature(type.id(), editSession, position, RandomSource.create());
    }

    @Override
    public boolean generateTree(TreeType type, EditSession editSession, BlockVector3 position) throws MaxChangedBlocksException {
        // One RandomSource per call: LegacyRandomSource throws a ThreadingDetector
        // exception on concurrent use, and FAWE runs these from several queue threads.
        RandomSource random = RandomSource.create();
        String feature = treeFeature(type, random);
        if (feature == null) {
            return false;
        }
        // Chorus plants have to grow from the block above the chosen position.
        BlockVector3 origin = type == TreeType.CHORUS_PLANT ? position.add(0, 1, 0) : position;
        return placeConfiguredFeature(feature, editSession, origin, random);
    }

    /**
     * Places a configured feature through the {@link FabricServerLevelDelegateProxy},
     * so every {@code setBlock} the feature performs lands in the {@link EditSession}
     * (and its history) instead of the live world.
     *
     * <p>26.3 folded the configured-feature registry into {@code Registries.FEATURE},
     * but both {@code ConfiguredFeature} and {@code Feature} expose the same
     * {@code place(WorldGenLevel, ChunkGenerator, RandomSource, BlockPos)} shape, so
     * only the registry lookup differs.</p>
     */
    private boolean placeConfiguredFeature(String id, EditSession editSession, BlockVector3 position,
                                           RandomSource random)
            throws MaxChangedBlocksException {
        Identifier key = Identifier.tryParse(id);
        if (key == null || !(getWorld() instanceof ServerLevel level)) {
            return false;
        }
        var feature = level.registryAccess()
                //#if MC >= 260300
                //$$ .lookupOrThrow(Registries.FEATURE)
                //#else
                .lookupOrThrow(Registries.CONFIGURED_FEATURE)
                //#endif
                .getValue(key);
        if (feature == null) {
            return false;
        }
        try (FabricServerLevelDelegateProxy.LevelAndProxy proxy =
                     FabricServerLevelDelegateProxy.newInstance(editSession, level)) {
            return feature.place(
                    proxy.level(),
                    level.getChunkSource().getGenerator(),
                    random,
                    FabricAdapter.toBlockPos(position)
            );
        }
    }

    /**
     * Maps WorldEdit's legacy tree types onto the vanilla tree features. The feature
     * generator is deterministic, needs no sapling or bonemeal, and honours the tree's
     * own space requirements.
     */
    @Nullable
    private static String treeFeature(TreeType type, RandomSource random) {
        return switch (type) {
            case TREE -> featureId(TreeFeatures.OAK);
            case BIG_TREE -> featureId(TreeFeatures.FANCY_OAK);
            case REDWOOD -> featureId(TreeFeatures.SPRUCE);
            case TALL_REDWOOD -> featureId(TreeFeatures.PINE);
            case MEGA_REDWOOD -> featureId(TreeFeatures.MEGA_SPRUCE);
            case RANDOM_REDWOOD -> pick(random, featureId(TreeFeatures.SPRUCE), featureId(TreeFeatures.PINE), featureId(TreeFeatures.MEGA_SPRUCE));
            case BIRCH -> featureId(TreeFeatures.BIRCH);
            case TALL_BIRCH -> featureId(TreeFeatures.SUPER_BIRCH_BEES_0002);
            case RANDOM_BIRCH -> pick(random, featureId(TreeFeatures.BIRCH), featureId(TreeFeatures.SUPER_BIRCH_BEES_0002));
            case JUNGLE -> featureId(TreeFeatures.MEGA_JUNGLE_TREE);
            case SMALL_JUNGLE -> featureId(TreeFeatures.JUNGLE_TREE);
            case SHORT_JUNGLE -> featureId(TreeFeatures.JUNGLE_TREE_NO_VINE);
            case RANDOM_JUNGLE -> pick(
                    random,
                    featureId(TreeFeatures.JUNGLE_TREE),
                    featureId(TreeFeatures.MEGA_JUNGLE_TREE),
                    featureId(TreeFeatures.JUNGLE_TREE_NO_VINE)
            );
            case JUNGLE_BUSH -> featureId(TreeFeatures.JUNGLE_BUSH);
            case SWAMP -> featureId(TreeFeatures.SWAMP_OAK);
            case ACACIA -> featureId(TreeFeatures.ACACIA);
            case AZALEA -> featureId(TreeFeatures.AZALEA_TREE);
            case DARK_OAK -> featureId(TreeFeatures.DARK_OAK);
            case PINE -> featureId(TreeFeatures.PINE);
            case CHORUS_PLANT -> featureId(EndFeatures.CHORUS_PLANT);
            case MANGROVE -> featureId(TreeFeatures.MANGROVE);
            case TALL_MANGROVE -> featureId(TreeFeatures.TALL_MANGROVE);
            case CHERRY -> featureId(TreeFeatures.CHERRY);
            case PALE_OAK -> featureId(TreeFeatures.PALE_OAK);
            case PALE_OAK_CREAKING -> featureId(TreeFeatures.PALE_OAK_CREAKING);
            case RED_MUSHROOM -> featureId(TreeFeatures.HUGE_RED_MUSHROOM);
            case BROWN_MUSHROOM -> featureId(TreeFeatures.HUGE_BROWN_MUSHROOM);
            case RANDOM_MUSHROOM -> pick(
                    random,
                    featureId(TreeFeatures.HUGE_RED_MUSHROOM),
                    featureId(TreeFeatures.HUGE_BROWN_MUSHROOM)
            );
            case CRIMSON_FUNGUS -> featureId(TreeFeatures.CRIMSON_FUNGUS);
            case WARPED_FUNGUS -> featureId(TreeFeatures.WARPED_FUNGUS);
            case RANDOM -> pick(
                    random,
                    featureId(TreeFeatures.OAK), featureId(TreeFeatures.FANCY_OAK), featureId(TreeFeatures.BIRCH),
                    featureId(TreeFeatures.SPRUCE), featureId(TreeFeatures.JUNGLE_TREE), featureId(TreeFeatures.ACACIA),
                    featureId(TreeFeatures.DARK_OAK), featureId(TreeFeatures.MANGROVE), featureId(TreeFeatures.CHERRY),
                    featureId(TreeFeatures.PALE_OAK)
            );
        };
    }

    private static String featureId(ResourceKey<?> feature) {
        return feature.identifier().toString();
    }

    private static String pick(RandomSource random, String... features) {
        return features[random.nextInt(features.length)];
    }

    @Override
    public boolean generateFeature(ConfiguredFeatureType type, EditSession editSession, BlockVector3 position) {
        try {
            return placeConfiguredFeature(type.id(), editSession, position, RandomSource.create());
        } catch (MaxChangedBlocksException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean generateStructure(StructureType type, EditSession editSession, BlockVector3 position) {
        return false;
    }

    @Override
    public void checkLoadedChunk(BlockVector3 pt) {
        getWorld().getChunk(FabricAdapter.toBlockPos(pt));
    }

    @Override
    public void fixAfterFastMode(Iterable<BlockVector2> chunks) {
        fixLighting(chunks);
    }

    public void sendBiomeUpdates(Iterable<BlockVector2> chunks) {
        List<ChunkAccess> nativeChunks = chunks instanceof Collection<BlockVector2> chunkCollection ? Lists.newArrayListWithCapacity(chunkCollection.size()) : Lists.newArrayList();
        for (BlockVector2 chunk : chunks) {
            nativeChunks.add(getWorld().getChunk(chunk.x(), chunk.z(), ChunkStatus.BIOMES, false));
        }
        ((ServerLevel) getWorld()).getChunkSource().chunkMap.resendBiomesForChunks(nativeChunks);
    }

    @Override
    public void fixLighting(Iterable<BlockVector2> chunks) {
        Level world = getWorld();
        for (BlockVector2 chunk : chunks) {
            world.getChunkSource().getLightEngine().setLightEnabled(
                new ChunkPos(chunk.x(), chunk.z()), true
            );
        }
    }

    @Override
    public boolean playEffect(Vector3 position, int type, int data) {
        // TODO update sound API
        // getWorld().playSound(type, FabricAdapter.toBlockPos(position.toBlockPoint()), data);
        return true;
    }

    @Override
    public WeatherType getWeather() {
        WeatherData info = ((ServerLevel) getWorld()).getWeatherData();
        if (info.isThundering()) {
            return WeatherTypes.THUNDER_STORM;
        }
        if (info.isRaining()) {
            return WeatherTypes.RAIN;
        }
        return WeatherTypes.CLEAR;
    }

    @Override
    public long getRemainingWeatherDuration() {
        WeatherData info = ((ServerLevel) getWorld()).getWeatherData();
        if (info.isThundering()) {
            return info.getThunderTime();
        }
        if (info.isRaining()) {
            return info.getRainTime();
        }
        return info.getClearWeatherTime();
    }

    @Override
    public void setWeather(WeatherType weatherType) {
        setWeather(weatherType, 0);
    }

    @Override
    public void setWeather(WeatherType weatherType, long duration) {
        WeatherData info = ((ServerLevel) getWorld()).getWeatherData();
        if (weatherType == WeatherTypes.THUNDER_STORM) {
            info.setClearWeatherTime(0);
            info.setThundering(true);
            info.setThunderTime((int) duration);
        } else if (weatherType == WeatherTypes.RAIN) {
            info.setClearWeatherTime(0);
            info.setRaining(true);
            info.setRainTime((int) duration);
        } else if (weatherType == WeatherTypes.CLEAR) {
            info.setRaining(false);
            info.setThundering(false);
            info.setClearWeatherTime((int) duration);
        }
    }

    @Override
    public int getMinY() {
        return getWorld().getMinY();
    }

    @Override
    public int getMaxY() {
        return getWorld().getMaxY();
    }

    @Override
    public BlockVector3 getSpawnPosition() {
        return FabricAdapter.adapt(getWorld().getLevelData().getRespawnData().pos());
    }

    @Override
    public BlockState getBlock(BlockVector3 position) {
        net.minecraft.world.level.block.state.BlockState mcState = getWorld()
                .getChunk(position.x() >> 4, position.z() >> 4)
                .getBlockState(FabricAdapter.toBlockPos(position));

        return FabricAdapter.adapt(mcState);
    }

    @Override
    public BaseBlock getFullBlock(BlockVector3 position) {
        BlockPos pos = new BlockPos(position.x(), position.y(), position.z());
        BlockEntity tile = ((LevelChunk) getWorld().getChunk(pos)).getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
        if (tile == null) {
            tile = getWorld().getChunkAt(pos).getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
        }

        if (tile != null) {
            var tagValueOutput = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, getWorld().registryAccess());
            tile.saveWithId(tagValueOutput);
            net.minecraft.nbt.CompoundTag tag = tagValueOutput.buildResult();
            return getBlock(position).toBaseBlock(LazyReference.from(() -> NBTConverter.fromNative(tag)));
        } else {
            return getBlock(position).toBaseBlock();
        }
    }

    @Override
    public int hashCode() {
        return getWorld().hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if ((o instanceof FabricWorld other)) {
            Level otherWorld = other.worldRef.get();
            Level thisWorld = worldRef.get();
            return otherWorld != null && otherWorld.equals(thisWorld);
        } else if (o instanceof com.sk89q.worldedit.world.World) {
            return ((com.sk89q.worldedit.world.World) o).getName().equals(getName());
        } else {
            return false;
        }
    }

    @Override
    public List<? extends Entity> getEntities(Region region) {
        final Level world = getWorld();
        AABB box = new AABB(
            FabricAdapter.toVec3(region.getMinimumPoint()),
            FabricAdapter.toVec3(region.getMaximumPoint().add(BlockVector3.ONE))
        );
        List<net.minecraft.world.entity.Entity> nmsEntities = world.getEntities(
            (net.minecraft.world.entity.Entity) null,
            box,
            e -> region.contains(FabricAdapter.adapt(e.blockPosition()))
        );
        return nmsEntities.stream()
            .map(FabricEntity::new)
            .collect(ImmutableList.toImmutableList());
    }

    @Override
    public List<? extends Entity> getEntities() {
        final Level world = getWorld();
        if (!(world instanceof ServerLevel)) {
            return Collections.emptyList();
        }
        return Streams.stream(((ServerLevel) world).getAllEntities())
            .map(FabricEntity::new)
            .collect(ImmutableList.toImmutableList());
    }

    @Nullable
    @Override
    public Entity createEntity(Location location, BaseEntity entity) {
        return createEntity(location, entity, uuidFromNbt(entity));
    }

    @Nullable
    private static UUID uuidFromNbt(BaseEntity entity) {
        if (entity == null || entity.getNbt() == null) {
            return null;
        }
        net.minecraft.nbt.CompoundTag tag = NBTConverter.toNative(entity.getNbt());
        return tag.getIntArray("UUID").map(UUIDUtil::uuidFromIntArray).orElse(null);
    }
    @Nullable
    @Override
    public Entity createEntity(Location location, BaseEntity entity, @Nullable UUID uuid) {
        if (entity == null || !(getWorld() instanceof ServerLevel level)) {
            return null;
        }
        EntityType<?> type = level.registryAccess()
                .lookupOrThrow(Registries.ENTITY_TYPE)
                .getValue(Identifier.parse(entity.getType().id()));
        if (type == null) {
            return null;
        }
        LinCompoundTag linTag = entity.getNbt();
        net.minecraft.nbt.CompoundTag nativeTag = linTag == null
                ? new net.minecraft.nbt.CompoundTag()
                : NBTConverter.toNative(linTag);
        nativeTag.putString("id", entity.getType().id());
        // Copy/paste/API creates a fresh identity; history restore passes the exact UUID.
        removeUnwantedEntityTagsRecursively(nativeTag);
        if (uuid != null) {
            var existing = level.getEntity(uuid);
            FabricConfiguration config = FabricWorldEdit.inst == null ? null : FabricWorldEdit.inst.getConfig();
            if (existing != null && (config == null || config.historyEntityUuidSkip)) {
                return new FabricEntity(existing);
            }
            nativeTag.putIntArray("UUID", UUIDUtil.uuidToIntArray(uuid));
        }
        var created = EntityType.loadEntityRecursive(
                type, nativeTag, level, EntitySpawnReason.COMMAND, EntityProcessor.NOP);
        if (created == null) {
            return null;
        }
        if (uuid != null) {
            created.setUUID(uuid);
        }
        created.setPos(location.x(), location.y(), location.z());
        if (!level.addFreshEntity(created)) {
            return null;
        }
        return new FabricEntity(created);
    }

    @Override
    public void removeEntity(int x, int y, int z, UUID uuid) {
        if (uuid == null) {
            return;
        }
        var entity = getWorld().getEntity(uuid);
        if (entity != null) {
            entity.remove(net.minecraft.world.entity.Entity.RemovalReason.KILLED);
        }
    }

    private void removeUnwantedEntityTagsRecursively(net.minecraft.nbt.CompoundTag tag) {
        for (String name : Constants.NO_COPY_ENTITY_NBT_FIELDS) {
            tag.remove(name);
        }

        // Adapted from net.minecraft.world.entity.EntityType#loadEntityRecursive
        tag.getList("Passengers").ifPresent(nbttaglist -> {
            for (int i = 0; i < nbttaglist.size(); ++i) {
                removeUnwantedEntityTagsRecursively(nbttaglist.getCompoundOrEmpty(i));
            }
        });
    }

    @Override
    public Mask createLiquidMask() {
        return new LiquidMask(this);
    }

    public boolean isValid() {
        return worldRef.get() != null;
    }

    @Override
    public IChunkGet get(int chunkX, int chunkZ) {
        return new FabricChunkGet(getWorld().getChunk(chunkX, chunkZ));
    }

    @Override
    public void refreshChunk(int chunkX, int chunkZ) {
        ChunkPackets.resend(getWorld().getChunk(chunkX, chunkZ));
    }

    @Override
    public void sendFakeChunk(@Nullable Player player, ChunkPacket packet) {
        // FAWE's fake-chunk packet type is not constructible without the legacy
        // NMS mapping layer; authoritative re-sends go through refreshChunk().
    }

    @Override
    public void flush() {
        worldNativeAccess.flush();
    }

    @Override
    public boolean tile(int x, int y, int z, FaweCompoundTag tile) throws WorldEditException {
        return false;
    }

    private static final class LiquidMask extends AbstractExtentMask {
        private LiquidMask(Extent extent) {
            super(extent);
        }

        @Override
        public boolean test(Extent extent, BlockVector3 position) {
            return FabricAdapter.adapt(extent.getBlock(position)).getBlock() instanceof LiquidBlock;
        }

        @Override
        public boolean test(BlockVector3 vector) {
            return test(getExtent(), vector);
        }

        @Override
        public Mask copy() {
            return new LiquidMask(getExtent());
        }
    }

}
