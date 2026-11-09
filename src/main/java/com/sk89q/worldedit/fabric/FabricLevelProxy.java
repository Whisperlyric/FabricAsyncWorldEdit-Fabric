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

import com.fastasyncworldedit.core.util.ReflectionUtils;
import com.sk89q.worldedit.fabric.internal.NBTConverter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueInput;
import org.enginehub.linbus.tree.LinCompoundTag;

import javax.annotation.Nullable;

/**
 * The level handed to blocks while {@code //fixblocks} computes placement states:
 * block, fluid and block entity reads all go through
 * {@link FabricPlacementStateProcessor#getBlockStateAt} (this edit's chunk buffers and
 * second-pass results), never the real ServerLevel - reading the live world from FAWE
 * worker threads blocks the main thread per block and misses in-flight edits.
 *
 * <p>Allocated through Unsafe without running a constructor, so any field reached by a
 * non-overridden method is null. Port of upstream PaperweightLevelProxy.
 */
class FabricLevelProxy extends ServerLevel {

    protected ServerLevel serverLevel;
    private FabricPlacementStateProcessor processor;

    @SuppressWarnings("DataFlowIssue")
    private FabricLevelProxy() {
        super(null, null, null, null, null, null, false, 0L, null, false);
        throw new IllegalStateException("Cannot be instantiated");
    }

    static FabricLevelProxy getInstance(ServerLevel serverLevel, FabricPlacementStateProcessor processor) {
        FabricLevelProxy newLevel;
        try {
            newLevel = (FabricLevelProxy) ReflectionUtils.getUnsafe().allocateInstance(FabricLevelProxy.class);
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        }
        newLevel.processor = processor;
        newLevel.serverLevel = serverLevel;
        return newLevel;
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos blockPos) {
        if (blockPos.getX() == Integer.MAX_VALUE) {
            return null;
        }
        LinCompoundTag tag = processor.getTileAt(blockPos.getX(), blockPos.getY(), blockPos.getZ());
        if (tag == null) {
            return null;
        }
        BlockState state = getBlockState(blockPos);
        if (!(state.getBlock() instanceof EntityBlock entityBlock)) {
            return null;
        }
        BlockEntity tileEntity = entityBlock.newBlockEntity(blockPos, state);
        if (tileEntity == null) {
            return null;
        }
        tileEntity.loadWithComponents(TagValueInput.create(
                ProblemReporter.DISCARDING, serverLevel.registryAccess(), NBTConverter.toNative(tag)));
        return tileEntity;
    }

    @Override
    public BlockState getBlockState(BlockPos blockPos) {
        if (blockPos.getX() == Integer.MAX_VALUE) {
            return Blocks.AIR.defaultBlockState();
        }
        return FabricAdapter.adapt(processor.getBlockStateAt(blockPos.getX(), blockPos.getY(), blockPos.getZ()));
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        if (pos.getX() == Integer.MAX_VALUE) {
            return Fluids.EMPTY.defaultFluidState();
        }
        return getBlockState(pos).getFluidState();
    }

    @Override
    public boolean isWaterAt(BlockPos pos) {
        if (pos.getX() == Integer.MAX_VALUE) {
            return false;
        }
        return getBlockState(pos).getFluidState().is(FluidTags.WATER);
    }

    @Override
    public int getHeight() {
        return serverLevel.getHeight();
    }

    @Override
    public int getMinY() {
        return serverLevel.getMinY();
    }

    @Override
    public int getMaxY() {
        return serverLevel.getMaxY();
    }

    @Override
    public boolean isInsideBuildHeight(int blockY) {
        return serverLevel.isInsideBuildHeight(blockY);
    }

    @Override
    public boolean isOutsideBuildHeight(BlockPos pos) {
        return serverLevel.isOutsideBuildHeight(pos);
    }

    @Override
    public boolean isOutsideBuildHeight(int blockY) {
        return serverLevel.isOutsideBuildHeight(blockY);
    }

    @Override
    public WorldBorder getWorldBorder() {
        return serverLevel.getWorldBorder();
    }
}
