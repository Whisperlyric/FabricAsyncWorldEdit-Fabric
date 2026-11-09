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

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends authoritative chunk re-synchronisation packets for edited chunks.
 */
public final class ChunkPackets {

    /**
     * Biome edits happen per 4x4x4 quart, so defer the packet until the end of the tick.
     * One packet per touched chunk instead of one per quart.
     */
    private static final Set<LevelChunk> PENDING_BIOMES = ConcurrentHashMap.newKeySet();
    /**
     * After a direct chunk write (async fast path), resend the whole chunk at tick end:
     * END_SERVER_TICK runs after the light queue, so the packet carries the edited light.
     */
    private static final Set<LevelChunk> PENDING_CHUNK_RESEND = ConcurrentHashMap.newKeySet();

    private ChunkPackets() {
    }

    /** True if {@code chunk} is still the instance the chunk map tracks. */
    private static boolean isLive(ServerLevel level, LevelChunk chunk) {
        // A queued resend can outlive the chunk's ticket; serialising a detached
        // instance would resend stale data over the live one.
        return level.getChunkSource().getChunkNow(chunk.getPos().x(), chunk.getPos().z()) == chunk;
    }

    /** Re-sends the full chunk + light payload to every player tracking {@code chunk}. */
    public static void resend(LevelChunk chunk) {
        if (!(chunk.getLevel() instanceof ServerLevel level) || !isLive(level, chunk)) {
            return;
        }
        var players = level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false);
        // Serialising a chunk + light for nobody is pure tick cost: a large //undo
        // touches hundreds of chunks nobody is near.
        if (players.isEmpty()) {
            return;
        }
        var packet = new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
        for (ServerPlayer player : players) {
            player.connection.send(packet);
        }
    }

    /** Queue a full-chunk re-send for the end of the current server tick. */
    public static void queueChunkResend(LevelChunk chunk) {
        if (chunk.getLevel() instanceof ServerLevel) {
            PENDING_CHUNK_RESEND.add(chunk);
        }
    }

    /** Re-sends only the biome palettes of {@code chunk}. */
    public static void resendBiomes(LevelChunk chunk) {
        if (!(chunk.getLevel() instanceof ServerLevel level)) {
            return;
        }
        var players = level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false);
        if (players.isEmpty()) {
            return;
        }
        var packet = ClientboundChunksBiomesPacket.forChunks(List.of(chunk));
        for (ServerPlayer player : players) {
            player.connection.send(packet);
        }
    }

    /** Queue a biome re-send for the end of the current server tick. */
    public static void queueBiomeResend(LevelChunk chunk) {
        if (chunk.getLevel() instanceof ServerLevel) {
            PENDING_BIOMES.add(chunk);
        }
    }

    /**
     * Sends at most {@link #MAX_RESENDS_PER_TICK} chunks and leaves the rest queued:
     * sending every chunk of a large edit in one tick stalls the server and floods the
     * client, which reads as "undo froze / broke".
     */
    private static final int MAX_RESENDS_PER_TICK = 64;

    /** Called once per server tick. */
    public static void flushBiomeResends() {
        int budget = MAX_RESENDS_PER_TICK;
        Iterator<LevelChunk> resends = PENDING_CHUNK_RESEND.iterator();
        while (resends.hasNext() && budget-- > 0) {
            LevelChunk chunk = resends.next();
            resends.remove();
            resend(chunk);
        }

        if (PENDING_BIOMES.isEmpty()) {
            return;
        }
        budget = MAX_RESENDS_PER_TICK;
        Map<ServerLevel, List<net.minecraft.world.level.chunk.ChunkAccess>> byLevel = new HashMap<>();
        Iterator<LevelChunk> biomes = PENDING_BIOMES.iterator();
        while (biomes.hasNext() && budget-- > 0) {
            LevelChunk chunk = biomes.next();
            biomes.remove();
            if (chunk.getLevel() instanceof ServerLevel level) {
                var accessible = level.getChunk(
                        chunk.getPos().getMinBlockX() >> 4,
                        chunk.getPos().getMinBlockZ() >> 4,
                        ChunkStatus.BIOMES, false);
                if (accessible != null) {
                    byLevel.computeIfAbsent(level, ignored -> new ArrayList<>()).add(accessible);
                }
            }
        }
        for (Map.Entry<ServerLevel, List<net.minecraft.world.level.chunk.ChunkAccess>> entry : byLevel.entrySet()) {
            entry.getKey().getChunkSource().chunkMap.resendBiomesForChunks(entry.getValue());
        }
    }

    /** Re-sends one block entity's NBT (contents, sign text, ...). */
    public static void resendBlockEntity(BlockEntity blockEntity) {
        if (!(blockEntity.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = blockEntity.getBlockPos();
        ChunkPos chunkPos = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
        var players = level.getChunkSource().chunkMap.getPlayers(chunkPos, false);
        if (players.isEmpty()) {
            return;
        }
        var packet = ClientboundBlockEntityDataPacket.create(blockEntity);
        for (ServerPlayer player : players) {
            player.connection.send(packet);
        }
    }
}
