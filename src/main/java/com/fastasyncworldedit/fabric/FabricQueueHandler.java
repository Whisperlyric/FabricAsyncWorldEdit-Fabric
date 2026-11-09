package com.fastasyncworldedit.fabric;

import com.fastasyncworldedit.core.queue.IChunkCache;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.implementation.QueueHandler;
import com.fastasyncworldedit.core.queue.implementation.chunk.ChunkCache;
import com.sk89q.worldedit.world.World;

public class FabricQueueHandler extends QueueHandler {

    @Override
    public void startUnsafe(boolean parallel) {
        // No-op on Fabric for now.
    }

    @Override
    public void endUnsafe(boolean parallel) {
        // No-op on Fabric for now.
    }

    /**
     * FAWE's default keeps one {@link ChunkCache} per world for the lifetime of the
     * server, so the same {@link IChunkGet} instance serves every later command on
     * that chunk.
     *
     * <p>{@code FabricChunkGet} captures a pre-edit snapshot (blocks, tiles, biomes,
     * entities) the first time FAWE asks for one, because history needs the
     * "before" state. It is never recaptured while non-null, and reads prefer it
     * over the live {@code LevelChunk}. Reusing the cached instance for the next
     * command therefore answers every read with the state from <em>before the
     * previous edit</em>: {@code //set 0} followed by {@code //stack 2} stacks the
     * blocks that {@code //set} just replaced.</p>
     *
     * <p>On Fabric the {@code LevelChunk} is the authority for block data (writes
     * land there in {@code FabricChunkGet.call}), so the get cache must be scoped
     * to one queue (= one EditSession/command) instead of one world. Within a
     * queue the cache is still shared, which keeps the snapshot stable for
     * history and for reads that must observe the pre-edit state.</p>
     */
    @Override
    public IChunkCache<IChunkGet> getOrCreateWorldCache(World world) {
        return new ChunkCache<>(world);
    }
}
