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

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Reusable placement context for {@code //fixblocks}: click location, face and facing
 * are injected by {@link #withSetting} from the block's current state, independent of
 * any player - taking the facing from a player's look would turn every stair toward
 * whoever ran the command.
 *
 * <p>Port of upstream PaperweightFaweMutableBlockPlaceContext.
 */
class FabricMutableBlockPlaceContext extends BlockPlaceContext {

    private static final BlockHitResult DEFAULT_BLOCK_HIT = new BlockHitResult(
            new Vec3(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE),
            Direction.NORTH,
            new BlockPos(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE),
            false
    );

    private final ServerLevel level;
    private BlockHitResult hitResult = null;
    private Direction direction = null;
    private BlockPos relativePos;

    @SuppressWarnings("DataFlowIssue")
    FabricMutableBlockPlaceContext(ServerLevel level) {
        // the super constructor only stores fields, then reads the sentinel position,
        // which the proxy level answers with AIR
        super(level, null, null, null, DEFAULT_BLOCK_HIT);
        this.level = level;
        this.replaceClicked = false;
    }

    FabricMutableBlockPlaceContext withSetting(BlockHitResult hitResult, Direction direction) {
        this.hitResult = hitResult;
        this.direction = direction;
        this.relativePos = hitResult.getBlockPos().relative(hitResult.getDirection());
        return this;
    }

    @Override
    public BlockPos getClickedPos() {
        return this.relativePos;
    }

    @Override
    public Direction getClickedFace() {
        return this.hitResult.getDirection();
    }

    @Override
    public Vec3 getClickLocation() {
        return this.hitResult.getLocation();
    }

    @Override
    public boolean isInside() {
        return this.hitResult.isInside();
    }

    @Override
    public ItemStack getItemInHand() {
        return ItemStack.EMPTY;
    }

    @Nullable
    @Override
    public Player getPlayer() {
        return null;
    }

    @Override
    @SuppressWarnings("DataFlowIssue")
    public InteractionHand getHand() {
        return null;
    }

    @Override
    public Level getLevel() {
        return this.level;
    }

    @Override
    public Direction getHorizontalDirection() {
        return this.direction.getAxis() == Direction.Axis.Y ? Direction.NORTH : this.direction;
    }

    @Override
    public boolean isSecondaryUseActive() {
        return false;
    }

    @Override
    public float getRotation() {
        return (float) (this.direction.get2DDataValue() * 90);
    }

    @Override
    public boolean canPlace() {
        return this.getLevel().getBlockState(this.getClickedPos()).canBeReplaced(this);
    }

    @Override
    public boolean replacingClickedOnBlock() {
        return false;
    }

    @Override
    public Direction getNearestLookingDirection() {
        return this.direction;
    }

    @Override
    public Direction getNearestLookingVerticalDirection() {
        return this.direction.getAxis() == Direction.Axis.Y ? Direction.UP : Direction.DOWN;
    }

    @Override
    public Direction[] getNearestLookingDirections() {
        return new Direction[]{this.direction};
    }
}
