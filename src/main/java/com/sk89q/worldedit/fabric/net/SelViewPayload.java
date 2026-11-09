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

package com.sk89q.worldedit.fabric.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Selection display mode sync (S2C), pushed when the server-side mode changes.
 *
 * @param mode 0 = hidden (client clears CUI state), 1 = CUI (default display)
 */
public record SelViewPayload(int mode) implements CustomPacketPayload {

    public static final int MODE_OFF = 0;
    public static final int MODE_CUI = 1;

    public static final Type<SelViewPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("fawe", "sel_view"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SelViewPayload> CODEC =
            StreamCodec.ofMember(SelViewPayload::write, SelViewPayload::new);

    private SelViewPayload(RegistryFriendlyByteBuf buf) {
        this(buf.readByte());
    }

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeByte(mode);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
