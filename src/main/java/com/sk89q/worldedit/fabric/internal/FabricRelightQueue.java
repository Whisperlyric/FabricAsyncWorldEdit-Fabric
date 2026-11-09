package com.sk89q.worldedit.fabric.internal;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Defers light rechecks until the chunk writes they depend on have been applied.
 * {@link #tick()} runs on the server thread after {@link FabricPendingApplies}.
 */
public final class FabricRelightQueue {

    private record Entry(ServerLevel level, ChunkPos pos) {
    }

    private static final ConcurrentLinkedQueue<Entry> PENDING = new ConcurrentLinkedQueue<>();

    private FabricRelightQueue() {
    }

    public static void enqueue(ServerLevel level, List<ChunkPos> positions) {
        for (ChunkPos pos : positions) {
            PENDING.add(new Entry(level, pos));
        }
    }

    /** Server thread only. Registered after {@link FabricPendingApplies#tick}. */
    public static void tick() {
        if (PENDING.isEmpty()) {
            return;
        }
        List<Entry> deferred = null;
        var log = com.sk89q.worldedit.internal.util.LogManagerCompat.getLogger();
        Entry entry;
        while ((entry = PENDING.poll()) != null) {
            ServerLevel level = entry.level();
            if (level.getServer() == null) {
                continue; // level gone with the server; nothing to relight
            }
            if (FabricPendingApplies.hasPending(level)) {
                // Its writes have not all landed yet; retry next tick.
                if (deferred == null) {
                    deferred = new ArrayList<>();
                }
                deferred.add(entry);
                continue;
            }
            try {
                var engine = level.getLightEngine();
                engine.checkBlock(new net.minecraft.core.BlockPos(
                        entry.pos().getMinBlockX(), level.getMinY(), entry.pos().getMinBlockZ()));
                engine.propagateLightSources(entry.pos());
            } catch (Throwable t) {
                log.warn("[FAWE-LIGHT] relight of chunk {} failed", entry.pos(), t);
            }
        }
        if (deferred != null) {
            PENDING.addAll(deferred);
        }
    }

    /** Drops everything; called on server stop after the final apply drain. */
    public static void clear() {
        int dropped = PENDING.size();
        PENDING.clear();
        if (dropped > 0) {
            com.sk89q.worldedit.internal.util.LogManagerCompat.getLogger().warn(
                    "[FAWE-LIGHT] discarded {} deferred chunk relights at server stop", dropped);
        }
    }
}
