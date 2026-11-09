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

import com.sk89q.worldedit.blocks.BaseItemStack;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.extension.platform.AbstractPlayerActor;
import com.sk89q.worldedit.extent.inventory.BlockBag;
import com.sk89q.worldedit.fabric.internal.ComponentConverter;
import com.sk89q.worldedit.fabric.net.handler.WECUIPacketHandler;
import com.sk89q.worldedit.internal.cui.CUIEvent;
import com.fastasyncworldedit.core.configuration.Caption;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.session.SessionKey;
import com.sk89q.worldedit.util.HandSide;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.formatting.WorldEditText;
import com.sk89q.worldedit.util.formatting.component.TextUtils;
import com.sk89q.worldedit.util.formatting.text.Component;
import com.sk89q.worldedit.util.formatting.text.serializer.gson.GsonComponentSerializer;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

public class FabricPlayer extends AbstractPlayerActor {

    private final ServerPlayer player;

    protected FabricPlayer(ServerPlayer player) {
        this.player = player;
        ThreadSafeCache.getInstance().getOnlineIds().add(getUniqueId());
    }

    /**
     * Exposes the backing server player for integrations that need native APIs beyond
     * the WorldEdit actor surface (e.g. item components on client-visible stacks).
     */
    public ServerPlayer getServerPlayer() {
        return this.player;
    }

    /**
     * Meta must live per-player UUID, not per-wrapper instance: adaptPlayer() creates a
     * fresh FabricPlayer per call, and FAWE's @Confirm handshake (setMeta("cmdConfirm")
     * during the blocked command, deleteMeta in //confirm) crosses two separate actor
     * instances. Keying by UUID also keeps respawn/world-change wrappers consistent.
     */
    private static final Map<UUID, Map<String, Object>> META_BY_UUID = new ConcurrentHashMap<>();

    @Override
    public Map<String, Object> getRawMeta() {
        return META_BY_UUID.computeIfAbsent(this.player.getUUID(), u -> new ConcurrentHashMap<>());
    }

    /** Drops a disconnected player's meta map so the store stays bounded. */
    public static void clearMeta(UUID uuid) {
        META_BY_UUID.remove(uuid);
    }

    @Override
    public UUID getUniqueId() {
        return player.getUUID();
    }

    @Override
    public BaseItemStack getItemInHand(HandSide handSide) {
        ItemStack is = this.player.getItemInHand(handSide == HandSide.MAIN_HAND ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
        return FabricAdapter.adapt(is);
    }

    @Override
    public String getName() {
        return this.player.getName().getString();
    }

    @Override
    public BaseEntity getState() {
        throw new UnsupportedOperationException("Cannot create a state from this object");
    }

    @Override
    public Location getLocation() {
        Vector3 position = Vector3.at(this.player.getX(), this.player.getY(), this.player.getZ());
        return new Location(
            FabricWorldEdit.inst.getWorld(this.player.level()),
            position,
            this.player.getYRot(),
            this.player.getXRot());
    }

    @Override
    public boolean setLocation(Location location) {
        ServerLevel level = (ServerLevel) FabricAdapter.adapt((World) location.getExtent());
        this.player.teleportTo(
            level,
            location.x(), location.y(), location.z(),
            Set.of(),
            location.getYaw(), location.getPitch(),
            true
        );
        // This check doesn't really ever get to be false in Fabric
        // Since Fabric API doesn't allow cancelling the teleport.
        // However, other mods could theoretically mix this in, so allow the detection.
        return this.player.level() == level;
    }

    @Override
    public World getWorld() {
        return FabricWorldEdit.inst.getWorld(this.player.level());
    }

    @Override
    public void giveItem(BaseItemStack itemStack) {
        this.player.getInventory().add(FabricAdapter.adapt(itemStack));
    }

    @Override
    public void dispatchCUIEvent(CUIEvent event) {
        // Guarded + isolated in WECUIPacketHandler so this class never touches
        // CUIPacket when the optional protocol mod is absent.
        WECUIPacketHandler.dispatch(this.player, event);
    }

    @Override
    public Locale getLocale() {
        return TextUtils.getLocaleByMinecraftTag(this.player.clientInformation().language());
    }

    @Override
    @Deprecated
    public void printRaw(String msg) {
        for (String part : msg.split("\n")) {
            this.player.sendSystemMessage(
                net.minecraft.network.chat.Component.literal(part)
            );
        }
    }

    @Override
    @Deprecated
    public void printDebug(String msg) {
        sendColorized(msg, ChatFormatting.GRAY);
    }

    @Override
    @Deprecated
    public void print(String msg) {
        sendColorized(msg, ChatFormatting.LIGHT_PURPLE);
    }

    @Override
    @Deprecated
    public void printError(String msg) {
        sendColorized(msg, ChatFormatting.RED);
    }

    @Override
    public void print(Component component) {
        // FAWE start - no global "(FAWE)" prefix in this Fabric port. The component keeps
        // its own styling; the Bukkit-style prefix template is intentionally not applied.
        var formatted = Caption.color(WorldEditText.format(component, getLocale()), getLocale());
        this.player.sendSystemMessage(ComponentConverter.Serializer.fromJson(
            GsonComponentSerializer.INSTANCE.serialize(formatted),
            player.level().registryAccess()
        ));
    }

    @Override
    public void sendTitle(Component title, Component sub) {
    }

    private void sendColorized(String msg, ChatFormatting formatting) {
        for (String part : msg.split("\n")) {
            this.player.sendSystemMessage(ComponentConverter.fromLegacy(part, formatting));
        }
    }

    @Override
    public boolean trySetPosition(Vector3 pos, float pitch, float yaw) {
        this.player.connection.teleport(pos.x(), pos.y(), pos.z(), yaw, pitch);
        return true;
    }

    @Override
    public String[] getGroups() {
        return new String[]{}; // WorldEditMod.inst.getPermissionsResolver().getGroups(this.player.username);
    }

    @Override
    public BlockBag getInventoryBlockBag() {
        return null;
    }

    @Override
    public boolean hasPermission(String perm) {
        return FabricWorldEdit.inst.getPermissionsProvider().hasPermission(player, perm);
    }

    @Override
    public void setPermission(String permission, boolean value) {
    }

    @Nullable
    @Override
    public <T> T getFacet(Class<? extends T> cls) {
        return null;
    }

    @Override
    public boolean isAllowedToFly() {
        return player.getAbilities().mayfly;
    }

    @Override
    public void setFlying(boolean flying) {
        if (player.getAbilities().flying != flying) {
            player.getAbilities().flying = flying;
            player.onUpdateAbilities();
        }
    }

    @Override
    public <B extends BlockStateHolder<B>> void sendFakeBlock(BlockVector3 pos, B block) {
        World world = getWorld();
        if (!(world instanceof FabricWorld)) {
            return;
        }
        BlockPos loc = FabricAdapter.toBlockPos(pos);
        final ClientboundBlockUpdatePacket packetOut;
        if (block == null) {
            packetOut = new ClientboundBlockUpdatePacket(((FabricWorld) world).getWorld(), loc);
        } else {
            packetOut = new ClientboundBlockUpdatePacket(
                    loc,
                    FabricAdapter.adapt(block.toImmutableState())
            );
        }
        player.connection.send(packetOut);
    }

    @Override
    public SessionKey getSessionKey() {
        return new SessionKeyImpl(player);
    }

    static class SessionKeyImpl implements SessionKey {
        // If not static, this will leak a reference

        private final UUID uuid;
        private final String name;

        SessionKeyImpl(ServerPlayer player) {
            this.uuid = player.getUUID();
            this.name = player.getName().getString();
        }

        @Override
        public UUID getUniqueId() {
            return uuid;
        }

        @Nullable
        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isActive() {
            // We can't directly check if the player is online because
            // the list of players is not thread safe
            return ThreadSafeCache.getInstance().getOnlineIds().contains(uuid);
        }

        @Override
        public boolean isPersistent() {
            return true;
        }

    }

}
