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

package com.sk89q.worldedit.fabric.internal;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Open Parties and Claims edit access, via the official static entry
 * {@code OpenPACServerAPI.get(server)} then
 * {@code IChunkProtectionAPI.hasChunkAccess(uuid, dim, cx, cz)} (covers personal
 * claims, party sharing and trust groups).
 *
 * <p>Fully reflective: OPAC is optional, and an absent or uninitialized OPAC always
 * allows. Methods are resolved once; call() checks once per chunk, never per block.
 */
public final class OpacGuard {

    private static volatile boolean probed;
    private static Method openApiGet;
    private static Method chunkProtectionGetter;
    private static Method hasChunkAccess;

    private OpacGuard() {
    }

    private static boolean probe() {
        if (probed) {
            return openApiGet != null;
        }
        probed = true;
        try {
            Class<?> serverApi = Class.forName("xaero.pac.common.server.api.OpenPACServerAPI");
            openApiGet = serverApi.getMethod("get", MinecraftServer.class);
            Class<?> protectionApi = Class.forName(
                    "xaero.pac.common.server.claims.protection.api.IChunkProtectionAPI");
            Class<?> serverDataApi = Class.forName("xaero.pac.common.server.IServerDataAPI");
            chunkProtectionGetter = serverDataApi.getMethod("getChunkProtection");
            hasChunkAccess = protectionApi.getMethod("hasChunkAccess",
                    UUID.class, Identifier.class, int.class, int.class);
            return true;
        } catch (ReflectiveOperationException | NoClassDefFoundError e) {
            // OPAC not installed
            return false;
        }
    }

    /** Whether OPAC is hooked up (for hints or toggles). */
    public static boolean available() {
        return probe();
    }

    /**
     * Whether {@code playerId} may edit chunk {@code (chunkX, chunkZ)} of {@code level}.
     *
     * @return true if allowed, or if OPAC is absent or the check fails (allow by default)
     */
    public static boolean allows(ServerLevel level, UUID playerId, int chunkX, int chunkZ) {
        if (!probe()) {
            return true;
        }
        try {
            MinecraftServer server = level.getServer();
            Object protection = chunkProtectionGetter.invoke(openApiGet.invoke(null, server));
            Identifier dim = level.dimension().identifier();
            return (Boolean) hasChunkAccess.invoke(protection, playerId, dim, chunkX, chunkZ);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }
}
