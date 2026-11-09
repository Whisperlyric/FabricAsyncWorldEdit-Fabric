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

package com.sk89q.worldedit.fabric.mixin;

import com.sk89q.worldedit.fabric.FabricWorldEdit;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Left-click-on-air detection, ported from the official WorldEdit fabric adapter.
 * Fabric API has no such callback, so it is inferred from swing packets: a
 * START_DESTROY_BLOCK action carries its own swing and is counted off first; any swing
 * left over is a left click on air and goes to the WE tool chain (superpickaxe etc.).
 * 26.3 renamed handleAnimate/ServerboundSwingPacket to handlePunch/ServerboundPunchPacket,
 * picked per version by //#if. Both handlers call {@code resetLastActionTime} first,
 * the stable injection anchor, and the callback takes no packet argument to stay clear
 * of the renamed packet class.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinServerGamePacketListenerImpl {

    @Shadow
    public ServerPlayer player;

    @Unique
    private int fawe$ignoreSwingPackets = 0;

    //#if MC >= 260300
    //$$ @Inject(method = "handlePunch",
    //#else
    @Inject(method = "handleAnimate",
    //#endif
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;resetLastActionTime()V"))
    private void fawe$onSwing(CallbackInfo ci) {
        fawe$handleSwing();
    }

    @Inject(method = "handlePlayerAction", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerPlayer;resetLastActionTime()V"))
    private void fawe$onPlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (packet.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
            fawe$ignoreSwingPackets++;
        }
    }

    @Unique
    private void fawe$handleSwing() {
        if (((AccessorServerPlayerGameMode) this.player.gameMode).isDestroyingBlock()) {
            return;
        }
        if (fawe$ignoreSwingPackets > 0) {
            fawe$ignoreSwingPackets--;
            return;
        }
        FabricWorldEdit mod = FabricWorldEdit.inst;
        if (mod != null) {
            mod.onLeftClickAir(this.player, InteractionHand.MAIN_HAND);
        }
    }
}
