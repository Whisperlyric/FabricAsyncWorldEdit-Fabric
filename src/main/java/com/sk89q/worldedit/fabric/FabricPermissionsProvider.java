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

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

public interface FabricPermissionsProvider {

    boolean hasPermission(ServerPlayer player, String permission);

    void registerPermission(String permission);

    class VanillaPermissionsProvider implements FabricPermissionsProvider {

        private final FabricPlatform platform;

        VanillaPermissionsProvider(FabricPlatform platform) {
            this.platform = platform;
        }

        @Override
        public boolean hasPermission(ServerPlayer player, String permission) {
            FabricConfiguration configuration = platform.getConfiguration();
            var server = player.level().getServer();
            boolean singlePlayerOwner = !server.isDedicatedServer()
                && server.isSingleplayerOwner(player.nameAndId());
            boolean operator = server.getPlayerList().isOp(player.nameAndId());
            return configuration.cheatMode
                || singlePlayerOwner
                || operator
                || (configuration.creativeEnable && player.gameMode.getGameModeForPlayer() == GameType.CREATIVE);
        }

        @Override
        public void registerPermission(String permission) {
        }
    }

    /**
     * Queries fabric-api's built-in permission module (fabric-permission-api-v1): checks
     * registered by permission mods (LuckPerms Fabric, open-parties-and-claims, ...) win,
     * and an unanswered query (null) falls back to vanilla op checks. Mirrors the v1
     * path of the official WorldEdit fabric FabricPermissionsProvider.
     *
     * <p>Only newer fabric-api bundles the module (0.155.3 on 26.1.2 ships 1.0.3; the
     * 26.3 aggregate does not, official WE ships it jar-in-jar), so access is fully
     * reflective and {@link #create} returns null when it is missing.
     */
    class FabricApiPermissionsProvider implements FabricPermissionsProvider {

        private static final Method NODE_OF;
        private static final Method CHECK_PERMISSION;

        static {
            Method nodeOf = null;
            Method check = null;
            try {
                Class<?> nodeClass = Class.forName("net.fabricmc.fabric.api.permission.v1.PermissionNode");
                nodeOf = nodeClass.getMethod("of", String.class, String.class);
                Class<?> ownerClass = Class.forName("net.fabricmc.fabric.api.permission.v1.PermissionContextOwner");
                check = ownerClass.getMethod("checkPermission", nodeClass);
            } catch (ReflectiveOperationException e) {
                // module missing: fields stay null and create() returns null
            }
            NODE_OF = nodeOf;
            CHECK_PERMISSION = check;
        }

        /** The provider if the module is available, otherwise null (callers fall back to vanilla). */
        public static FabricApiPermissionsProvider create(FabricPermissionsProvider fallback) {
            return NODE_OF != null && CHECK_PERMISSION != null
                ? new FabricApiPermissionsProvider(fallback)
                : null;
        }

        private final FabricPermissionsProvider fallback;
        private final Map<String, Object> registeredNodes = new ConcurrentHashMap<>();

        private FabricApiPermissionsProvider(FabricPermissionsProvider fallback) {
            this.fallback = fallback;
        }

        @Override
        public boolean hasPermission(ServerPlayer player, String permission) {
            Object node = registeredNodes.computeIfAbsent(permission, FabricApiPermissionsProvider::createNode);
            if (node == null) {
                return fallback.hasPermission(player, permission);
            }
            try {
                Object result = CHECK_PERMISSION.invoke(player, node);
                return result instanceof Boolean b ? b : fallback.hasPermission(player, permission);
            } catch (ReflectiveOperationException e) {
                return fallback.hasPermission(player, permission);
            }
        }

        @Override
        public void registerPermission(String permission) {
            registeredNodes.computeIfAbsent(permission, FabricApiPermissionsProvider::createNode);
            fallback.registerPermission(permission);
        }

        /**
         * Splits a node like {@code worldedit.region.set} at the first '.' into the
         * Identifier namespace and path; returns null (use the fallback) for characters
         * Identifier rejects ([a-z0-9_.-], plus '/' in the path).
         */
        private static Object createNode(String permission) {
            int dot = permission.indexOf('.');
            if (dot < 1 || dot == permission.length() - 1) {
                return null;
            }
            String namespace = sanitize(permission.substring(0, dot), false);
            String path = sanitize(permission.substring(dot + 1), true);
            if (namespace == null || path == null) {
                return null;
            }
            try {
                return NODE_OF.invoke(null, namespace, path);
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }

        private static String sanitize(String part, boolean isPath) {
            String lowered = part.toLowerCase(java.util.Locale.ROOT);
            StringBuilder sb = new StringBuilder(lowered.length());
            for (int i = 0; i < lowered.length(); i++) {
                char c = lowered.charAt(i);
                boolean valid = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.'
                    || c == '-' || (isPath && c == '/');
                if (!valid) {
                    return null;
                }
                sb.append(c);
            }
            return sb.toString();
        }
    }
}
