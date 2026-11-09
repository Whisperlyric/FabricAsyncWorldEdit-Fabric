package com.sk89q.worldedit.fabric.internal;

import com.fastasyncworldedit.core.extent.processor.lighting.Relighter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Relighter backed by the vanilla light engine. Rechecks are queued through
 * {@link FabricRelightQueue} so they run after deferred chunk applies.
 */
public final class FabricVanillaRelighter implements Relighter {

    private final ServerLevel level;
    private final Set<ChunkPos> chunks = new LinkedHashSet<>();
    private final ReentrantLock lock = new ReentrantLock();
    private boolean finished;

    public FabricVanillaRelighter(ServerLevel level) {
        this.level = level;
    }

    @Override
    public boolean addChunk(int cx, int cz, byte[] skipReason, int bitmask) {
        lock.lock();
        try {
            this.chunks.add(new ChunkPos(cx, cz));
            this.finished = false;
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void addLightUpdate(int x, int y, int z) {
        // MODE=2's per-block updates collapse to chunk-level rechecks.
        lock.lock();
        try {
            this.chunks.add(new ChunkPos(x >> 4, z >> 4));
            this.finished = false;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear() {
        lock.lock();
        try {
            this.chunks.clear();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void removeLighting() {
        // The engine recomputes from the applied blocks; no prior removal needed.
    }

    @Override
    public void fixBlockLighting() {
        fixLightingSafe(false);
    }

    @Override
    public void fixSkyLighting() {
        fixLightingSafe(true);
    }

    @Override
    public void fixLightingSafe(boolean sky) {
        lock.lock();
        try {
            if (!this.chunks.isEmpty()) {
                FabricRelightQueue.enqueue(this.level, List.copyOf(this.chunks));
                this.chunks.clear();
            }
            this.finished = true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isEmpty() {
        lock.lock();
        try {
            return this.chunks.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public ReentrantLock getLock() {
        return this.lock;
    }

    @Override
    public boolean isFinished() {
        lock.lock();
        try {
            return this.finished;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        fixLightingSafe(true);
    }
}
