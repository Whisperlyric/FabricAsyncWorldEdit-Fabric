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

import com.sk89q.worldedit.fabric.net.SelViewPayload;
import com.sk89q.worldedit.fabric.net.handler.WECUIPacketHandler;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * Per-player selection display mode: CUI (default, packets go to WorldEditCUI) or
 * hidden (server stops sending, client clears its CUI state). Kept per session; a
 * reconnect restores CUI.
 */
public final class SelViewRegistry {

    public enum Mode {
        OFF, CUI
    }

    private static final Map<UUID, Mode> MODES = new ConcurrentHashMap<>();

    private SelViewRegistry() {
    }

    public static Mode mode(UUID playerId) {
        return MODES.getOrDefault(playerId, Mode.CUI);
    }

    /**
     * Switches the mode and pushes it to the client at once; switching back to CUI
     * re-handshakes and resends the selection so WorldEditCUI shows it again.
     */
    public static void setMode(ServerPlayer player, Mode mode) {
        MODES.put(player.getUUID(), mode);
        switch (mode) {
            case CUI -> WECUIPacketHandler.reinitialize(player);
            case OFF -> sendClear(player);
        }
    }

    private static void sendClear(ServerPlayer player) {
        ServerPlayNetworking.send(player, new SelViewPayload(Mode.OFF.ordinal()));
    }
}
