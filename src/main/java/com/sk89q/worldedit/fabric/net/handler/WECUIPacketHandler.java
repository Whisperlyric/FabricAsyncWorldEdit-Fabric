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

package com.sk89q.worldedit.fabric.net.handler;

import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.fabric.FabricPlayer;
import com.sk89q.worldedit.fabric.FabricWorldEdit;
import com.sk89q.worldedit.fabric.SelViewRegistry;
import com.sk89q.worldedit.internal.cui.CUIEvent;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import org.enginehub.worldeditcui.protocol.CUIPacket;
import org.enginehub.worldeditcui.protocol.CUIPacketHandler;

/**
 * Server side of the WorldEdit CUI protocol, wired through the shared
 * {@code worldeditcui_protocol} mod — the same integration the official
 * WorldEdit 26.x build uses: handlers are registered through the protocol
 * library's {@link CUIPacketHandler#instance()} API instead of owning a
 * Fabric receiver, so entry-point ordering between this mod and the
 * protocol mod is irrelevant.
 *
 * <p>The protocol mod is an <b>optional</b> dependency: with no CUI client
 * installed this class is inert. To keep the optional boundary clean, every
 * {@link CUIPacket} reference lives in this class only.</p>
 */
public final class WECUIPacketHandler {
    private static final boolean AVAILABLE =
            FabricLoader.getInstance().isModLoaded("worldeditcui_protocol");

    private WECUIPacketHandler() {
    }

    /** True when the CUI protocol mod is loaded and CUI events can be dispatched. */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static void init() {
        if (!AVAILABLE) {
            return;
        }
        // Order-independent handler registration through the protocol library —
        // the library owns the underlying Fabric receiver and its entrypoint
        // registers the payload channels themselves.
        CUIPacketHandler.instance().registerServerboundHandler((packet, context) -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            LocalSession session = FabricWorldEdit.inst.getSession(player);
            FabricPlayer actor = FabricAdapter.adaptPlayer(player);
            StringBuilder text = new StringBuilder(packet.eventType());
            for (String arg : packet.args()) {
                text.append('|').append(arg);
            }
            session.handleCUIInitializationMessage(text.toString(), actor);
        });
        // Prime every joining player's session: WE core only dispatches selection
        // events once a session is marked CUI-capable, and some clients (or a
        // client joining before the receiver) may never deliver their own
        // handshake. Idempotent.
        //
        // The handshake must be "v|<version>", not "init|...": for a session without CUI
        // support LocalSession only parses the "v" prefix (enables CUI and resends the
        // selection) and silently ignores "init".
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            // the mode is per session (a reconnect restores CUI); dispatch on it
            if (SelViewRegistry.mode(player.getUUID()) == SelViewRegistry.Mode.CUI) {
                reinitialize(player);
            }
        });
    }

    /**
     * Re-handshakes and resends the current selection (shape and points): the
     * server-side recovery behind {@code /fawecui} when the client lost its CUI state.
     */
    public static void reinitialize(ServerPlayer player) {
        if (!AVAILABLE) {
            return;
        }
        LocalSession session = FabricWorldEdit.inst.getSession(player);
        String handshake = "v|" + CUIPacket.protocolVersion();
        session.handleCUIInitializationMessage(handshake, FabricAdapter.adaptPlayer(player));
    }

    /** Sends a CUI event to the player; no-op when the protocol mod is absent. */
    public static void dispatch(ServerPlayer player, CUIEvent event) {
        if (!AVAILABLE) {
            return;
        }
        // hide mode: stop sending CUI packets
        if (SelViewRegistry.mode(player.getUUID()) == SelViewRegistry.Mode.OFF) {
            return;
        }
        ServerPlayNetworking.send(player, new CUIPacket(event.getTypeId(), event.getParameters()));
    }
}
