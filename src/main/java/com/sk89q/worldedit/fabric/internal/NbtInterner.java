package com.sk89q.worldedit.fabric.internal;

import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import org.enginehub.linbus.tree.LinCompoundTag;

/**
 * Bounded, process-wide interner for tile-entity NBT. Copy/paste and history often
 * see identical sign/chest tags; sharing the immutable {@link LinCompoundTag}
 * avoids retaining one deep copy per position.
 */
public final class NbtInterner {

    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("fawe.nbtInterner.enabled", "true"));
    private static final int MAX_ENTRIES =
            Math.max(1, Integer.getInteger("fawe.nbtInterner.maxEntries", 2048));
    private static final long MAX_BYTES =
            Math.max(1L, Long.getLong("fawe.nbtInterner.maxBytes", 8L * 1024 * 1024));

    private record Cached(FaweCompoundTag tag, int bytes) {
    }

    private static final LinkedHashMap<CompoundTag, Cached> CACHE =
            new LinkedHashMap<>(256, 0.75f, true);
    private static long cachedBytes;

    private NbtInterner() {
    }

    public static FaweCompoundTag internFawe(CompoundTag nativeTag) {
        if (!ENABLED) {
            return FaweCompoundTag.of((LinCompoundTag) NBTConverter.fromNative(nativeTag));
        }
        Cached cached;
        synchronized (CACHE) {
            cached = CACHE.get(nativeTag);
        }
        if (cached != null) {
            return cached.tag();
        }
        int bytes = nativeTag.sizeInBytes();
        FaweCompoundTag tag = FaweCompoundTag.of((LinCompoundTag) NBTConverter.fromNative(nativeTag));
        synchronized (CACHE) {
            Cached raced = CACHE.get(nativeTag);
            if (raced != null) {
                // Another thread converted the same tag while we were converting.
                return raced.tag();
            }
            if (bytes <= MAX_BYTES) {
                CACHE.put(nativeTag, new Cached(tag, bytes));
                cachedBytes += bytes;
                trim();
            }
        }
        return tag;
    }

    public static LinCompoundTag internLin(CompoundTag nativeTag) {
        return internFawe(nativeTag).linTag();
    }

    public static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
            cachedBytes = 0L;
        }
    }

    /** Caller must hold the monitor on {@link #CACHE}. */
    private static void trim() {
        Iterator<Map.Entry<CompoundTag, Cached>> iterator = CACHE.entrySet().iterator();
        while ((CACHE.size() > MAX_ENTRIES || cachedBytes > MAX_BYTES) && iterator.hasNext()) {
            Cached removed = iterator.next().getValue();
            cachedBytes -= removed.bytes();
            iterator.remove();
        }
    }
}
