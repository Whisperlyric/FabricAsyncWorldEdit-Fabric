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

import com.sk89q.worldedit.world.registry.BlockMaterial;
import com.sk89q.worldedit.world.registry.PassthroughBlockMaterial;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * Fabric block material that pulls as much info as possible from the Minecraft
 * Material, and passes the rest to another implementation, typically the
 * bundled block info.
 */
public class FabricBlockMaterial extends PassthroughBlockMaterial {

    private final BlockState block;

    public FabricBlockMaterial(BlockState block, @Nullable BlockMaterial secondary) {
        super(secondary);
        this.block = block;
    }

    @Override
    public boolean isAir() {
        return block.isAir() || super.isAir();
    }

    @Override
    public boolean isOpaque() {
        return block.canOcclude();
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean isLiquid() {
        return block.liquid();
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean isSolid() {
        return block.isSolid();
    }

    @Override
    public boolean isFragileWhenPushed() {
        return false;
    }

    @Override
    public boolean isUnpushable() {
        return false;
    }

    @Override
    public boolean isMovementBlocker() {
        return block.isSolid();
    }

    @Override
    public boolean isBurnable() {
        return block.ignitedByLava();
    }

    @Override
    public boolean isToolRequired() {
        return block.requiresCorrectToolForDrops();
    }

    @Override
    public boolean isReplacedDuringPlacement() {
        return block.canBeReplaced();
    }

    /**
     * Must not fall back to the bundled JSON: FAWE's built-in registry stops at the
     * 1.21.4 data version (signs marked false, 26.x blocks such as copper chests
     * missing), and clipboard read-back (toBaseBlock) gates tile/NBT reattachment on
     * hasContainer(), so a miss silently drops sign text and chest contents. Vanilla
     * EntityBlock is the source of truth.
     */
    @Override
    public boolean hasContainer() {
        return this.block.getBlock() instanceof EntityBlock;
    }

}
