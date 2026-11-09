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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player edit preferences.
 *
 * <ul>
 *   <li><b>async/sync</b>: how block writes reach clients. sync updates block by
 *       block (vanilla look); async writes silently and resends whole chunks
 *       (frozen, batched reveal).</li>
 *   <li><b>biomeBox</b>: vanilla-aligned mode for //setbiome. When non-null, only
 *       quarts fully inside the box (world quart coordinates) are applied, matching
 *       /fillbiome's inward semantics; null means outward-touching (FAWE default).</li>
 * </ul>
 */
public final class EditModeRegistry {

    private static final Map<UUID, Boolean> ASYNC_BY_PLAYER = new ConcurrentHashMap<>();

    private EditModeRegistry() {
    }

    /**
     * Defaults to async: silent batched writes plus chunk resends, without per-block
     * neighbour updates. sync is only a visual fallback (vanilla per-block look with
     * neighbour updates, much slower on large edits).
     *
     * <p>A null {@code playerId} (e.g. the actor context not reaching the queue thread on
     * the fast path) must return the default directly: a null key throws in
     * ConcurrentHashMap and aborts call(), so FAWE reports success while no block lands.
     */
    public static boolean isAsync(UUID playerId) {
        if (playerId == null) {
            return true;
        }
        return ASYNC_BY_PLAYER.getOrDefault(playerId, true);
    }

    public static void setAsync(UUID playerId, boolean async) {
        ASYNC_BY_PLAYER.put(playerId, async);
    }

    /** Drops a disconnected player's preference; keeps the map bounded. */
    public static void clear(UUID playerId) {
        ASYNC_BY_PLAYER.remove(playerId);
    }

}
