/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.sk89q.worldedit.fabric;

import com.fastasyncworldedit.core.extent.processor.heightmap.HeightMapType;
import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.fastasyncworldedit.core.queue.IQueueExtent;
import com.fastasyncworldedit.core.queue.implementation.blocks.CharGetBlocks;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.fabric.internal.FabricEntity;
import com.sk89q.worldedit.fabric.internal.NBTConverter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.concurrency.LazyReference;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypesCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import org.enginehub.linbus.tree.LinCompoundTag;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * FAWE {@link IChunkGet} backed by a Fabric {@link LevelChunk}.
 *
 * <p>Blocks and biomes are read directly. Block entities and entities are captured
 * on the server thread. Writes are applied on the server thread through
 * {@code FabricPendingApplies}.</p>
 */
public final class FabricChunkGet extends CharGetBlocks {
    private final LevelChunk chunk;
    /**
     * Immutable copy of the section data captured by {@link #setCreateCopy(boolean)}.
     * A null section means the entire section was air at capture time.
     */
    private char[][] snapshotBlocks;
    /** 4x4x4 biome entries per section; null section means the section was not captured. */
    private BiomeType[][] snapshotBiomes;
    /** Block entities at capture time. Keys are absolute world positions. */
    private Map<BlockVector3, FaweCompoundTag> snapshotTiles;
    /** Entities at capture time, keyed by UUID. */
    private Map<UUID, FaweCompoundTag> snapshotEntities;
    private boolean createCopy;
    /**
     * Block entities and entities for a get without a pre-edit snapshot (a plain
     * //copy, for instance): captured once on the server thread on first use and
     * reused for this get's lifetime, which is one queue, i.e. one command.
     */
    private volatile Map<BlockVector3, FaweCompoundTag> liveTileCapture;
    private volatile Map<UUID, FaweCompoundTag> liveEntityCapture;

    private record ServerCapture(@Nullable Map<BlockVector3, FaweCompoundTag> tiles,
                                 @Nullable Map<UUID, FaweCompoundTag> entities) {}

    /**
     * {@code nmsState} is converted during collection on the FJ worker threads, so the
     * main-thread apply loop never transmogs; {@code tile} spares it a per-block
     * {@code tilePositions} lookup.
     */
    private record PendingBlock(BlockPos pos, BlockState state,
            net.minecraft.world.level.block.state.BlockState nmsState, boolean tile) {}
    private record PendingTile(BlockPos pos, FaweCompoundTag tag) {}
    private record PendingBiome(int x, int y, int z, BiomeType biome) {}

    public FabricChunkGet(LevelChunk chunk) {
        this(chunk, null, null, null, null);
    }

    private FabricChunkGet(
            LevelChunk chunk,
            @Nullable char[][] snapshotBlocks,
            @Nullable BiomeType[][] snapshotBiomes,
            @Nullable Map<BlockVector3, FaweCompoundTag> snapshotTiles,
            @Nullable Map<UUID, FaweCompoundTag> snapshotEntities
    ) {
        super(chunk.getMinY() >> 4, chunk.getMaxY() >> 4);
        this.chunk = chunk;
        this.snapshotBlocks = snapshotBlocks;
        this.snapshotBiomes = snapshotBiomes;
        this.snapshotTiles = snapshotTiles;
        this.snapshotEntities = snapshotEntities;
    }

    // FAWE chunk-API convention: x/z are CHUNK-LOCAL (0..15) and y is WORLD.
    // The NMS LevelChunk accessors want world coordinates, hence the base offsets.

    @Override
    public char[] load(int layer) {
        char[] data = super.load(layer);
        if (snapshotBlocks != null) {
            // The snapshot is authoritative: a null section means "air at capture time".
            // Falling through to the live chunk here would read the post-edit state,
            // which made large edits record "before == after" for formerly-air sections.
            char[] snapshot = copySection(layer);
            if (snapshot != null) {
                System.arraycopy(snapshot, 0, data, 0, 4096);
            }
            return data;
        }
        int offset = layer - getMinSectionPosition();
        LevelChunkSection[] sections = chunk.getSections();
        if (offset < 0 || offset >= sections.length) {
            return data;
        }
        LevelChunkSection section = sections[offset];
        if (section == null || section.hasOnlyAir()) {
            return data;
        }
        for (int i = 0; i < 4096; i++) {
            BlockState state = FabricAdapter.adapt(
                    section.getBlockState(i & 15, (i >> 8) & 15, (i >> 4) & 15));
            data[i] = state.getOrdinalChar();
        }
        return data;
    }

    @Override
    public char[] loadIfPresent(int layer) {
        if (snapshotBlocks != null) {
            // In snapshot mode a null section means "air at capture time", so it must
            // not fall through to the live chunk (that would read post-edit state).
            return copySection(layer);
        }
        return super.loadIfPresent(layer);
    }

    @Nullable
    private char[] copySection(int layer) {
        if (snapshotBlocks == null) {
            return null;
        }
        int offset = layer - getMinSectionPosition();
        if (offset < 0 || offset >= snapshotBlocks.length || snapshotBlocks[offset] == null) {
            return null;
        }
        return Arrays.copyOf(snapshotBlocks[offset], 4096);
    }

    @Override
    public BlockState getBlock(int x, int y, int z) {
        if (snapshotBlocks != null) {
            int offset = (y >> 4) - getMinSectionPosition();
            if (offset < 0 || offset >= snapshotBlocks.length || snapshotBlocks[offset] == null) {
                return BlockTypesCache.states[BlockTypesCache.ReservedIDs.AIR];
            }
            char[] section = snapshotBlocks[offset];
            int index = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
            return BlockTypesCache.states[section[index]];
        }
        BlockPos pos = new BlockPos(baseX() + x, y, baseZ() + z);
        return FabricAdapter.adapt(chunk.getBlockState(pos));
    }

    @Override
    public BaseBlock getFullBlock(int x, int y, int z) {
        BlockState state = getBlock(x, y, z);
        if (!tileOperationsEnabled() || !FabricAdapter.adapt(state).hasBlockEntity()) {
            // Most blocks carry no NBT; answering them never leaves this thread.
            return state.toBaseBlock();
        }
        // Tile NBT always comes from a capture taken on the server thread: the pre-edit
        // snapshot when there is one, otherwise a read capture cached for this get.
        // Reading the live BlockEntity here would serialise server state off-thread, and
        // EntityCreationType.IMMEDIATE would even create and register missing ones.
        FaweCompoundTag tag = readTiles().get(BlockVector3.at(baseX() + x, y, baseZ() + z));
        return tag != null ? state.toBaseBlock(LazyReference.from(tag::linTag)) : state.toBaseBlock();
    }

    @Override
    public BiomeType getBiomeType(int x, int y, int z) {
        if (snapshotBiomes != null) {
            int offset = (y >> 4) - getMinSectionPosition();
            if (offset >= 0 && offset < snapshotBiomes.length && snapshotBiomes[offset] != null) {
                int index = ((y & 15) >> 2) << 4 | ((z & 15) >> 2) << 2 | ((x & 15) >> 2);
                return snapshotBiomes[offset][index];
            }
        }
        return FabricAdapter.adapt(
                chunk.getNoiseBiome((baseX() + x) >> 2, y >> 2, (baseZ() + z) >> 2).value());
    }

    @Override
    public int getSkyLight(int x, int y, int z) {
        return chunk.getLevel().getLightEngine()
            .getLayerListener(LightLayer.SKY)
            .getLightValue(new BlockPos(baseX() + x, y, baseZ() + z));
    }

    @Override
    public int getEmittedLight(int x, int y, int z) {
        return chunk.getLevel().getLightEngine()
            .getLayerListener(LightLayer.BLOCK)
            .getLightValue(new BlockPos(baseX() + x, y, baseZ() + z));
    }

    private int baseX() {
        return chunk.getPos().getMinBlockX();
    }

    private int baseZ() {
        return chunk.getPos().getMinBlockZ();
    }

    @Override
    public int[] getHeightMap(HeightMapType type) {
        Heightmap.Types nativeType = switch (type) {
            case MOTION_BLOCKING -> Heightmap.Types.MOTION_BLOCKING;
            case MOTION_BLOCKING_NO_LEAVES -> Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
            case OCEAN_FLOOR -> Heightmap.Types.OCEAN_FLOOR;
            case WORLD_SURFACE -> Heightmap.Types.WORLD_SURFACE;
        };
        int[] map = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                map[z * 16 + x] = chunk.getHeight(nativeType, x, z);
            }
        }
        return map;
    }

    @Override
    public <T extends java.util.concurrent.Future<T>> T call(
        IQueueExtent<? extends com.fastasyncworldedit.core.queue.IChunk> owner,
        IChunkSet set,
        Runnable finalize
    ) {
        java.util.UUID actor = currentActorUuid();
        boolean async = EditModeRegistry.isAsync(actor);
        // OPAC claims: a player may only edit chunks they have access to; FAWE aborts
        // the whole edit with NO_REGION and tells the player.
        if (actor != null && !com.sk89q.worldedit.fabric.internal.OpacGuard.allows(
                (net.minecraft.server.level.ServerLevel) chunk.getLevel(),
                actor, baseX() >> 4, baseZ() >> 4)) {
            throw new com.fastasyncworldedit.core.internal.exception.FaweException(
                    com.sk89q.worldedit.util.formatting.text.TranslatableComponent.of(
                            "fawe.cancel.reason.no.region"),
                    com.fastasyncworldedit.core.internal.exception.FaweException.Type.NO_REGION);
        }
        // 26.x client sync only happens in ServerLevel.setBlock (block + BE data +
        // light + neighbor flags) — direct LevelChunk writes never notify clients.
        // All application therefore runs on the server thread through that entry.
        int flags = async ? 2 : 3; // 2 = send to clients, 3 = + neighbor updates
        int minSection = set.getMinSectionPosition();
        int maxSection = set.getMaxSectionPosition();

        // IChunkSet is pooled and reset as soon as call() returns. Everything the
        // server task needs must be copied out while we are still on the queue thread.
        List<PendingTile> pendingTiles = new ArrayList<>();
        for (Map.Entry<BlockVector3, FaweCompoundTag> entry : set.tiles().entrySet()) {
            BlockVector3 pos = entry.getKey();
            pendingTiles.add(new PendingTile(
                    new BlockPos(baseX() + pos.x(), pos.y(), baseZ() + pos.z()),
                    entry.getValue()));
        }
        // tile positions are known before collection, which resolves both the transmog
        // and the tile flag off the main thread
        Set<BlockPos> tilePositions = new HashSet<>();
        for (PendingTile tile : pendingTiles) {
            tilePositions.add(tile.pos());
        }
        List<PendingBlock> pending = new ArrayList<>();
        for (int section = minSection; section <= maxSection; section++) {
            if (!set.hasSection(section)) {
                continue;
            }
            int sectionY = section << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int worldY = sectionY + y;
                        // IChunkSet uses chunk-local x/z; y is world.
                        BlockState state = set.getBlock(x, worldY, z);
                        // Untouched slots are filled with the __RESERVED__ sentinel ordinal (0) —
                        // distinct from explicitly written air (ordinal 1), so air writes are
                        // preserved. Only skip the sentinel.
                        if (state == null
                                || state.getOrdinal() == BlockTypesCache.ReservedIDs.__RESERVED__) {
                            continue;
                        }
                        BlockPos pos = new BlockPos(baseX() + x, worldY, baseZ() + z);
                        pending.add(new PendingBlock(pos, state,
                                FabricAdapter.adapt(state), tilePositions.contains(pos)));
                    }
                }
            }
        }

        List<PendingBiome> pendingBiomes = new ArrayList<>();
        BiomeType[][] biomes = set.getBiomes();
        if (biomes != null) {
            for (int layer = minSection; layer <= maxSection; layer++) {
                if (!set.hasBiomes(layer)) {
                    continue;
                }
                int offset = layer - minSection;
                if (offset < 0 || offset >= biomes.length || biomes[offset] == null) {
                    continue;
                }
                BiomeType[] faweArr = biomes[offset];
                int sectionY = layer << 4;
                // slots are indexed as FAWE's setBiome writes them,
                //   (y & 12) << 2 | (z & 12) | ((x & 12) >> 2), i.e. qy * 16 + qz * 4 + qx;
                // CharSetBlocks.getBiomeType reads with a different formula, so the array
                // is indexed directly here rather than read back through the set.
                for (int qy = 0; qy < 4; qy++) {
                    for (int qz = 0; qz < 4; qz++) {
                        for (int qx = 0; qx < 4; qx++) {
                            int worldX = baseX() + (qx << 2);
                            int worldY = sectionY + (qy << 2);
                            int worldZ = baseZ() + (qz << 2);
                            int faweSlot = ((worldY & 12) << 2) | (worldZ & 12) | ((worldX & 12) >> 2);
                            if (faweSlot < 0 || faweSlot >= faweArr.length) {
                                continue;
                            }
                            BiomeType biome = faweArr[faweSlot];
                            if (biome != null) {
                                pendingBiomes.add(new PendingBiome(worldX, worldY, worldZ, biome));
                            }
                        }
                    }
                }
            }
        }
        List<FaweCompoundTag> pendingEntities = new ArrayList<>(set.entities());
        Set<UUID> entityRemoves = new HashSet<>(set.getEntityRemoves());

        if (!pending.isEmpty()
                || !pendingTiles.isEmpty()
                || !pendingBiomes.isEmpty()
                || !pendingEntities.isEmpty()
                || !entityRemoves.isEmpty()) {
            ServerLevel level = (ServerLevel) chunk.getLevel();
            List<PendingBlock> applyBlocks = orderPlainBeforeTiled(pending, tilePositions);
            List<PendingTile> applyTiles = List.copyOf(pendingTiles);
            List<PendingBiome> applyBiomes = List.copyOf(pendingBiomes);
            List<FaweCompoundTag> applyEntities = List.copyOf(pendingEntities);
            Set<UUID> applyRemoves = Set.copyOf(entityRemoves);
            CompletableFuture<Void> applied = new CompletableFuture<>();
            Runnable apply = () -> {
                // stages are isolated: a failing stage no longer swallows the rest,
                // and the log names it
                var log = com.sk89q.worldedit.internal.util.LogManagerCompat.getLogger();
                // Re-resolve the chunk at apply time. A deferred apply can outlive the
                // ticket that loaded it (TicketType.UNKNOWN expires after one tick), and
                // writing into a detached LevelChunk silently loses the edit. If the
                // chunk unloaded, the server reloads it here and the edit lands on the
                // authoritative instance, which is then saved normally.
                LevelChunk liveChunk = level.getChunk(chunk.getPos().x(), chunk.getPos().z());
                try {
                    if (async) {
                        // fast path: write the section palette instead of ServerLevel.setBlock,
                        // so a block costs a palette write plus heightmap updates, and clients
                        // get one full chunk packet at tick end
                        LevelChunkSection section = null;
                        int sectionIndex = Integer.MIN_VALUE;
                        // heightmap instances are fixed per chunk, resolve them once
                        List<Heightmap> heightmaps = List.copyOf(liveChunk.getHeightmaps())
                                .stream().map(Map.Entry::getValue).toList();
                        for (PendingBlock p : applyBlocks) {
                            int idx = liveChunk.getSectionIndex(p.pos().getY());
                            if (idx != sectionIndex) {
                                section = liveChunk.getSection(idx);
                                sectionIndex = idx;
                            }
                            applyBlockDirect(level, liveChunk, section, heightmaps, p);
                        }
                        liveChunk.markUnsaved();
                    } else {
                        for (PendingBlock p : applyBlocks) {
                            // Clearing a tile container before placing its replacement can
                            // wipe the shared inventory of a double chest. The NBT applied
                            // below is authoritative for tile blocks.
                            if (!tilePositions.contains(p.pos())) {
                                clearContainer(level, p.pos());
                            }
                            level.setBlock(p.pos(), FabricAdapter.adapt(p.state()), flags);
                        }
                    }
                } catch (Throwable t) {
                    log.error("[FAWE-APPLY] blocks 阶段失败（{} 块）", applyBlocks.size(), t);
                }
                try {
                    applyTiles(level, applyTiles);
                } catch (Throwable t) {
                    log.error("[FAWE-APPLY] tiles 阶段失败（{} 个）", applyTiles.size(), t);
                }
                try {
                    applyBiomes(level, liveChunk, applyBiomes);
                } catch (Throwable t) {
                    log.error("[FAWE-APPLY] biomes 阶段失败（{} 个）", applyBiomes.size(), t);
                }
                try {
                    applyEntities(level, applyEntities, applyRemoves);
                } catch (Throwable t) {
                    log.error("[FAWE-APPLY] entities 阶段失败（{} 个）", applyEntities.size(), t);
                }
                try {
                    if (async) {
                        // direct writes send no per-block packets; the tick-end chunk resend
                        // runs after this tick's light queue, so it carries the edited light
                        ChunkPackets.queueChunkResend(liveChunk);
                    }
                    if (finalize != null) {
                        finalize.run();
                    }
                } catch (Throwable t) {
                    log.error("[FAWE-APPLY] resend/finalize 阶段失败", t);
                }
                applied.complete(null);
            };
            // Registered before it is scheduled: the next command drains whatever is
            // still pending, so //set 0 followed by //stack 2 in the same tick reads
            // the applied chunk instead of the pre-edit blocks. Off-thread applies are
            // run by the start-of-tick handler, inside a time budget, so a large //undo
            // cannot stall a single server tick.
            Runnable task = com.sk89q.worldedit.fabric.internal.FabricPendingApplies.register(level, apply);
            if (level.getServer().isSameThread()) {
                task.run();
            }
            @SuppressWarnings({"unchecked", "rawtypes"})
            T future = (T) (java.util.concurrent.Future) applied;
            return future;
        }
        if (finalize != null) {
            finalize.run();
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        T future = (T) (java.util.concurrent.Future) CompletableFuture.completedFuture(null);
        return future;
    }

    /**
     * Plain blocks first, block-entity blocks last: supports must exist before
     * signs and hanging signs are placed, or their neighbour update pops the sign
     * off again.
     */
    private static List<PendingBlock> orderPlainBeforeTiled(List<PendingBlock> pending, Set<BlockPos> tilePositions) {
        List<PendingBlock> plainBlocks = new ArrayList<>(pending.size());
        List<PendingBlock> tiledBlocks = new ArrayList<>();
        for (PendingBlock block : pending) {
            if (tilePositions.contains(block.pos())) {
                tiledBlocks.add(block);
            } else {
                plainBlocks.add(block);
            }
        }
        List<PendingBlock> apply = new ArrayList<>(plainBlocks.size() + tiledBlocks.size());
        apply.addAll(plainBlocks);
        apply.addAll(tiledBlocks);
        return List.copyOf(apply);
    }

    /**
     * Writes the section palette directly and replays the minimal subset of the
     * vanilla {@code LevelChunk.setBlockState} side effects a large edit needs, in
     * vanilla order: heightmap updates, section emptiness change (light and client
     * render), light check on changed light properties, block entity removal and
     * creation.
     *
     * <p>onPlace, neighbour updates, POI and affectNeighborsAfterRemoval are skipped:
     * async edits never resolve physics. Clients are synced by the tick-end chunk
     * resend, not per block.
     */
    private static void applyBlockDirect(ServerLevel level, LevelChunk chunk,
            LevelChunkSection section, List<Heightmap> heightmaps, PendingBlock p) {
        BlockPos pos = p.pos();
        net.minecraft.world.level.block.state.BlockState newState = p.nmsState();
        boolean wasEmpty = section.hasOnlyAir();
        net.minecraft.world.level.block.state.BlockState old = section.setBlockState(
                pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, newState);
        for (Heightmap heightmap : heightmaps) {
            // chunk-local x/z (vanilla passes getX() & 15) and world y
            heightmap.update(pos.getX() & 15, pos.getY(), pos.getZ() & 15, newState);
        }
        boolean isEmpty = section.hasOnlyAir();
        if (wasEmpty != isEmpty) {
            LevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
            lightEngine.updateSectionStatus(SectionPos.of(pos), isEmpty);
            level.getChunkSource().onSectionEmptinessChanged(
                    pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4, isEmpty);
        }
        if (old != null && LightEngine.hasDifferentLightProperties(old, newState)) {
            level.getChunkSource().getLightEngine().checkBlock(pos);
            // as the reference adapter does: flag the chunk light after a light change
            chunk.setLightCorrect(true);
        }
        if (newState.hasBlockEntity()) {
            BlockEntity existing = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
            if (existing == null || !existing.isValidBlockState(newState)) {
                // hasBlockEntity() implies an EntityBlock (vanilla makes the same cast)
                BlockEntity created = ((net.minecraft.world.level.block.EntityBlock) newState.getBlock())
                        .newBlockEntity(pos, newState);
                if (created != null) {
                    chunk.setBlockEntity(created);
                }
            }
        } else if (old != null && old.hasBlockEntity()) {
            // a block entity replaced by a plain block (chest to stone): clear the shared
            // inventory first; tile targets skip this, the NBT applied later is authoritative
            if (!p.tile()) {
                clearContainerStatic(level, pos);
            }
            chunk.removeBlockEntity(pos);
        }
    }

    private static void clearContainerStatic(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof net.minecraft.world.Clearable clearable) {
            clearable.clearContent();
        }
    }

    private void clearContainer(ServerLevel level, BlockPos pos) {
        clearContainerStatic(level, pos);
    }

    private void applyTiles(ServerLevel level, List<PendingTile> tiles) {
        if (!tileOperationsEnabled()) {
            return;
        }
        var log = com.sk89q.worldedit.internal.util.LogManagerCompat.getLogger();
        for (PendingTile pending : tiles) {
            BlockEntity blockEntity = level.getBlockEntity(pending.pos());
            if (blockEntity == null) {
                blockEntity = level.getChunkAt(pending.pos())
                        .getBlockEntity(pending.pos(), LevelChunk.EntityCreationType.IMMEDIATE);
            }
            if (blockEntity == null) {
                log.warn("[FAWE-TILE] {} 无 BE 可加载（目标方块缺失？）", pending.pos());
                continue;
            }
            // A fresh native tag per block entity: CompoundTag is mutable, and a block
            // entity (modded ones especially) may keep sub-tags of its input, so one
            // shared instance would alias state between every tile pasted from it.
            var nativeTag = NBTConverter.toNative(pending.tag().linTag());
            // Collector records what loadWithComponents dropped and why (DISCARDING is silent)
            var problems = new ProblemReporter.Collector();
            blockEntity.loadWithComponents(TagValueInput.create(
                    problems, level.registryAccess(), nativeTag));
            problems.forEach((path, problem) ->
                    log.warn("[FAWE-TILE] {} 加载问题: {} — {}", pending.pos(), path, problem.description()));
            blockEntity.setChanged();
            ChunkPackets.resendBlockEntity(blockEntity);
        }
    }

    private void applyBiomes(ServerLevel level, LevelChunk chunk, List<PendingBiome> biomes) {
        if (biomes.isEmpty()) {
            return;
        }
        for (PendingBiome pending : biomes) {
            setBiomeAt(level, pending.x(), pending.y(), pending.z(), pending.biome());
        }
        ChunkPackets.queueBiomeResend(chunk);
    }

    @SuppressWarnings("unchecked")
    private void setBiomeAt(ServerLevel level, int x, int y, int z, BiomeType biome) {
        LevelChunk target = level.getChunk(x >> 4, z >> 4);
        LevelChunkSection section = target.getSection(target.getSectionIndex(y));
        PalettedContainer<Holder<Biome>> biomes = (PalettedContainer<Holder<Biome>>) section.getBiomes();
        Holder<Biome> holder = level.registryAccess()
                .lookup(Registries.BIOME)
                .orElseThrow()
                .getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.parse(biome.id())));
        // quart coordinates are the in-section block coordinates >> 2 on every axis, not world & 3
        biomes.getAndSetUnchecked((x & 15) >> 2, (y & 15) >> 2, (z & 15) >> 2, holder);
        target.markUnsaved();
    }

    private void applyEntities(ServerLevel level, List<FaweCompoundTag> entities, Set<UUID> removes) {
        for (UUID uuid : removes) {
            var entity = level.getEntity(uuid);
            if (entity != null) {
                entity.remove(net.minecraft.world.entity.Entity.RemovalReason.KILLED);
            }
        }
        for (FaweCompoundTag tag : entities) {
            net.minecraft.nbt.CompoundTag nativeTag = NBTConverter.toNative(tag.linTag());
            // Paste/edit sets get a fresh identity. Undo/redo restores use
            // World.createEntity(location, state, uuid) instead of this path.
            nativeTag.remove("UUID");
            nativeTag.remove("UUIDMost");
            nativeTag.remove("UUIDLeast");
            nativeTag.remove("PersistentIDMSB");
            nativeTag.remove("PersistentIDLSB");
            var input = TagValueInput.create(
                    ProblemReporter.DISCARDING, level.registryAccess(), nativeTag);
            var type = EntityType.by(input).orElse(null);
            if (type == null) {
                continue;
            }
            var entity = EntityType.loadEntityRecursive(
                    type, nativeTag, level, EntitySpawnReason.COMMAND, EntityProcessor.NOP);
            if (entity != null) {
                level.addFreshEntity(entity);
            }
        }
    }

    /** The acting player of the command that triggered this apply, if attributable. */
    private static java.util.UUID currentActorUuid() {
        var actor = com.sk89q.worldedit.session.request.Request.request().getActor();
        return actor instanceof FabricPlayer fabricPlayer ? fabricPlayer.getUniqueId() : null;
    }

    @Override
    public FaweCompoundTag entity(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        return readEntities().get(uuid);
    }

    @Override
    public Set<Entity> getFullEntities() {
        // The entity section storage is server-thread state, so the chunk's entities
        // are collected there. The wrappers stay live because callers may remove()
        // them (//cut -e); FabricEntity.getState() on them is the caller's concern.
        List<net.minecraft.world.entity.Entity> nmsEntities = onServerThread("entity list", this::chunkEntities);
        Set<Entity> result = new HashSet<>();
        for (var nmsEntity : nmsEntities) {
            result.add(new FabricEntity(nmsEntity));
        }
        return Collections.unmodifiableSet(result);
    }

    /** Server thread only. */
    private List<net.minecraft.world.entity.Entity> chunkEntities() {
        AABB box = new AABB(
                baseX(), chunk.getMinY(), baseZ(),
                baseX() + 16, chunk.getMaxY(), baseZ() + 16);
        return chunk.getLevel().getEntities(
                (net.minecraft.world.entity.Entity) null,
                box,
                this::inChunk);
    }

    private boolean inChunk(net.minecraft.world.entity.Entity entity) {
        BlockPos pos = entity.blockPosition();
        return (pos.getX() >> 4) == chunk.getPos().x() && (pos.getZ() >> 4) == chunk.getPos().z();
    }

    @Override
    public boolean isCreateCopy() {
        return createCopy;
    }

    @Override
    public int setCreateCopy(boolean createCopy) {
        this.createCopy = createCopy;
        if (createCopy) {
            if (snapshotBlocks == null) {
                snapshotBlocks = captureBlocks();
            }
            if (snapshotBiomes == null) {
                snapshotBiomes = captureBiomes();
            }
            // Clipboard reads also use this snapshot. The history toggles must
            // not decide what copy/paste can see, or //copy silently loses tiles.
            if (!tileOperationsEnabled()) {
                snapshotTiles = Collections.emptyMap();
            }
            boolean needTiles = snapshotTiles == null;
            boolean needEntities = snapshotEntities == null;
            if (needTiles || needEntities) {
                // Block entities and entities are server-thread state: capture both in
                // one hop instead of serialising entities on FAWE's queue thread.
                ServerCapture capture = onServerThread("pre-edit snapshot", () -> new ServerCapture(
                        needTiles ? captureTilesNow() : null,
                        needEntities ? captureEntitiesNow() : null));
                if (needTiles) {
                    snapshotTiles = capture.tiles();
                }
                if (needEntities) {
                    snapshotEntities = capture.entities();
                }
            }
        }
        return 0;
    }

    private static FabricConfiguration config() {
        return FabricWorldEdit.inst == null ? null : FabricWorldEdit.inst.getConfig();
    }

    private static boolean historyTilesEnabled() {
        FabricConfiguration config = config();
        return config == null || config.historyTiles;
    }

    private static boolean tileOperationsEnabled() {
        FabricConfiguration config = config();
        return config == null || config.tileOperations;
    }

    private static boolean historyBiomesEnabled() {
        FabricConfiguration config = config();
        return config == null || config.historyBiomes;
    }

    private static boolean historyEntitiesEnabled() {
        FabricConfiguration config = config();
        return config != null && config.historyEntities;
    }

    /**
     * Returns the immutable pre-edit snapshot captured by {@link #setCreateCopy(boolean)}.
     *
     * <p>FAWE calls this from {@code ChunkHolder}'s post-processor. The default
     * {@link IChunkGet#getCopy(int)} returns {@code null}, which is what made
     * {@code AbstractChangeSet.processSet} fail on {@code get.tiles()}.</p>
     */
    @Override
    public IChunkGet getCopy(int key) {
        if (snapshotBlocks == null) {
            // Fallback for callers that did not request a copy first. This is
            // still better than returning null, but history should call
            // setCreateCopy(true) before applying the chunk.
            snapshotBlocks = captureBlocks();
            snapshotBiomes = captureBiomes();
            boolean tiles = tileOperationsEnabled();
            ServerCapture capture = onServerThread("pre-edit snapshot", () -> new ServerCapture(
                    tiles ? captureTilesNow() : Collections.emptyMap(),
                    captureEntitiesNow()));
            snapshotTiles = capture.tiles();
            snapshotEntities = capture.entities();
        }
        return new FabricChunkGet(chunk, snapshotBlocks, snapshotBiomes, snapshotTiles, snapshotEntities);
    }

    /**
     * Captures all non-air sections before a write. The arrays are treated as
     * immutable afterwards; {@link #getBlock(int, int, int)} and
     * {@link #load(int)} read from the snapshot when it is present.
     */
    private char[][] captureBlocks() {
        LevelChunkSection[] sections = chunk.getSections();
        char[][] snapshot = new char[sections.length][];
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            char[] data = new char[4096];
            for (int i = 0; i < 4096; i++) {
                BlockState state = FabricAdapter.adapt(
                        section.getBlockState(i & 15, (i >> 8) & 15, (i >> 4) & 15));
                data[i] = state.getOrdinalChar();
            }
            snapshot[sectionIndex] = data;
        }
        return snapshot;
    }

    private BiomeType[][] captureBiomes() {
        LevelChunkSection[] sections = chunk.getSections();
        BiomeType[][] snapshot = new BiomeType[sections.length][];
        int minSection = getMinSectionPosition();
        int baseQuartX = baseX() >> 2;
        int baseQuartZ = baseZ() >> 2;
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section == null) {
                continue;
            }
            BiomeType[] data = new BiomeType[64];
            int baseQuartY = (minSection + sectionIndex) << 2;
            for (int qy = 0; qy < 4; qy++) {
                for (int qz = 0; qz < 4; qz++) {
                    for (int qx = 0; qx < 4; qx++) {
                        data[qy << 4 | qz << 2 | qx] = FabricAdapter.adapt(
                                chunk.getNoiseBiome(baseQuartX + qx, baseQuartY + qy, baseQuartZ + qz).value());
                    }
                }
            }
            snapshot[sectionIndex] = data;
        }
        return snapshot;
    }

    /**
     * Runs {@code task} on the server thread and waits for it. LevelChunk's block
     * entity map and the entity sections are server-thread state, and FAWE reaches
     * this class from its queue threads, so captures hop instead of reading live
     * state off-thread.
     *
     * <p>A capture that cannot complete fails the operation. Returning an empty
     * result instead would silently drop every tile and entity from copy, paste and
     * history (empty chests on paste, containers not restored on undo).</p>
     */
    private <T> T onServerThread(String what, java.util.function.Supplier<T> task) {
        net.minecraft.server.MinecraftServer server = chunk.getLevel().getServer();
        if (server == null || server.isSameThread()) {
            return task.get();
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        server.execute(() -> {
            try {
                result.complete(task.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        try {
            return result.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while capturing " + what + " for chunk " + chunk.getPos(), e);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException(
                    "Failed to capture " + what + " for chunk " + chunk.getPos()
                    + ": the server thread stayed busy for over 5s", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(
                    "Failed to capture " + what + " for chunk " + chunk.getPos(), e.getCause());
        }
    }

    /** Tiles to answer reads with: the pre-edit snapshot if any, else a cached capture. */
    private Map<BlockVector3, FaweCompoundTag> readTiles() {
        Map<BlockVector3, FaweCompoundTag> tiles = snapshotTiles;
        if (tiles != null) {
            return tiles;
        }
        tiles = liveTileCapture;
        if (tiles == null) {
            // Two threads may race to capture; both results are equivalent.
            tiles = onServerThread("block entities", this::captureTilesNow);
            liveTileCapture = tiles;
        }
        return tiles;
    }

    /** Entities to answer reads with: the pre-edit snapshot if any, else a cached capture. */
    private Map<UUID, FaweCompoundTag> readEntities() {
        Map<UUID, FaweCompoundTag> entities = snapshotEntities;
        if (entities != null) {
            return entities;
        }
        entities = liveEntityCapture;
        if (entities == null) {
            entities = onServerThread("entities", this::captureEntitiesNow);
            liveEntityCapture = entities;
        }
        return entities;
    }

    /** Server thread only. */
    private Map<BlockVector3, FaweCompoundTag> captureTilesNow() {
        Map<BlockVector3, FaweCompoundTag> snapshot = new java.util.HashMap<>();
        // getBlockEntitiesPos() is a copy that also covers block entities still pending
        // promotion after a chunk load; IMMEDIATE creates those, which is only safe here.
        for (BlockPos pos : chunk.getBlockEntitiesPos()) {
            BlockEntity blockEntity = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
            if (blockEntity != null) {
                snapshot.put(BlockVector3.at(pos.getX(), pos.getY(), pos.getZ()), toFaweTag(blockEntity));
            }
        }
        return Map.copyOf(snapshot);
    }

    private FaweCompoundTag toFaweTag(BlockEntity blockEntity) {
        TagValueOutput output = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, chunk.getLevel().registryAccess());
        blockEntity.saveWithId(output);
        // Copy/paste and history see many identical signs/chests. Share the
        // immutable NBT object through the bounded process-wide interner.
        return com.sk89q.worldedit.fabric.internal.NbtInterner.internFawe(output.buildResult());
    }

    /** Server thread only. */
    private Map<UUID, FaweCompoundTag> captureEntitiesNow() {
        Map<UUID, FaweCompoundTag> snapshot = new java.util.HashMap<>();
        for (var nmsEntity : chunkEntities()) {
            var state = new FabricEntity(nmsEntity).getState();
            if (state == null) {
                continue;
            }
            UUID uuid = state.getUUID();
            if (uuid != null) {
                snapshot.put(uuid, FaweCompoundTag.of(state.getNbt()));
            }
        }
        return Map.copyOf(snapshot);
    }

    @Override
    public void setLightingToGet(char[][] lighting, int startSectionIndex, int endSectionIndex) {
        // LevelChunk lighting is read directly.
    }

    @Override
    public void setSkyLightingToGet(char[][] lighting, int startSectionIndex, int endSectionIndex) {
        // LevelChunk lighting is read directly.
    }

    @Override
    public void setHeightmapToGet(HeightMapType type, int[] heightMap) {
        // LevelChunk heightmaps are read directly.
    }

    @Override
    public int getMaxY() {
        return chunk.getMaxY();
    }

    @Override
    public int getMinY() {
        return chunk.getMinY();
    }

    @Override
    public boolean hasSection(int section) {
        int index = section - (chunk.getMinY() >> 4);
        if (snapshotBlocks != null) {
            return index >= 0 && index < snapshotBlocks.length && snapshotBlocks[index] != null;
        }
        LevelChunkSection[] sections = chunk.getSections();
        if (index < 0 || index >= sections.length) {
            return false;
        }
        LevelChunkSection chunkSection = sections[index];
        return chunkSection != null && !chunkSection.hasOnlyAir();
    }

    @Override
    public Map<BlockVector3, FaweCompoundTag> tiles() {
        if (!tileOperationsEnabled()) {
            return Collections.emptyMap();
        }
        // Cached: tile(x, y, z) goes through here once per lookup.
        return readTiles();
    }

    @Override
    public FaweCompoundTag tile(int x, int y, int z) {
        if (!tileOperationsEnabled()) {
            return null;
        }
        return tiles().get(BlockVector3.at(baseX() + x, y, baseZ() + z));
    }

    @Override
    public Collection<FaweCompoundTag> entities() {
        return readEntities().values();
    }

    @Override
    public void removeSectionLighting(int section, boolean sky) {
        // No-op for now.
    }

    @Override
    public boolean trim(boolean aggressive, int section) {
        return false;
    }

    @Override
    public boolean trim(boolean aggressive) {
        return false;
    }

    @Override
    public int getSectionCount() {
        return chunk.getSections().length;
    }

    @Override
    public int getMaxSectionPosition() {
        return chunk.getMaxY() >> 4;
    }

    @Override
    public int getMinSectionPosition() {
        return chunk.getMinY() >> 4;
    }

    @Override
    public int getX() {
        return chunk.getPos().x();
    }

    @Override
    public int getZ() {
        return chunk.getPos().z();
    }

    @Override
    public void optimize() {
        // No-op for the direct LevelChunk reader.
    }

    @Override
    public void recycle() {
        // No-op for the direct LevelChunk reader.
    }

    @Nullable
    public LevelChunk chunk() {
        return chunk;
    }
}

