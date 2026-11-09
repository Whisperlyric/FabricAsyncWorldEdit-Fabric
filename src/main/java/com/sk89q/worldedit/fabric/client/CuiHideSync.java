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

package com.sk89q.worldedit.fabric.client;

import com.sk89q.worldedit.fabric.net.SelViewPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Client side of the hide mode ({@code /faweselmode hide}): on a non-CUI mode packet,
 * clear WorldEditCUI's session state by reflection so it stops drawing. Switching back
 * to CUI makes the server re-handshake and resend the selection, and WorldEditCUI
 * recovers on its own.
 */
public final class CuiHideSync {

    private static volatile int mode = SelViewPayload.MODE_CUI;
    private static volatile boolean cuiCleared;

    private CuiHideSync() {
    }

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(SelViewPayload.TYPE,
                (payload, context) -> apply(payload));
    }

    private static void apply(SelViewPayload payload) {
        mode = payload.mode();
        if (mode != SelViewPayload.MODE_CUI) {
            // the server stopped sending CUI packets; drop the stale selection on the client
            clearCuiState();
        } else {
            cuiCleared = false;
        }
    }

    private static void clearCuiState() {
        if (cuiCleared || !FabricLoader.getInstance().isModLoaded("worldeditcui")) {
            return;
        }
        cuiCleared = true;
        try {
            Class<?> fabMod = Class.forName("org.enginehub.worldeditcui.fabric.FabricModWorldEditCUI");
            Object instance = fabMod.getMethod("getInstance").invoke(null);
            Object controller = fabMod.getMethod("getController").invoke(instance);
            controller.getClass().getMethod("clear").invoke(controller);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // CUI absent or its internals changed: nothing to clear
        }
    }
}
