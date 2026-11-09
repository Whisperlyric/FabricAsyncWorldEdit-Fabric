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

import com.fastasyncworldedit.core.extent.processor.PlacementStateProcessor;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.mask.BlockTypeMask;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypesCache;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code //fixblocks} processor for Fabric: recomputes each block's placement state
 * (stair facing and half, fence and wall connections, ...) as if it had just been
 * placed. FAWE core derives the click point and face from the current state; they
 * reach vanilla getStateForPlacement through {@link FabricMutableBlockPlaceContext},
 * with neighbour reads served by {@link FabricLevelProxy}.
 *
 * <p>Port of upstream PaperweightPlacementStateProcessor.
 */
public class FabricPlacementStateProcessor extends PlacementStateProcessor {

    /** One warning per block type, so a large selection does not flood the log. */
    private static final Set<String> WARNED_BLOCKS = ConcurrentHashMap.newKeySet();

    private final FabricMutableBlockPlaceContext mutableBlockPlaceContext;
    private final FabricLevelProxy proxyLevel;

    public FabricPlacementStateProcessor(Extent extent, BlockTypeMask mask, Region region,
            ServerLevel serverLevel) {
        super(extent, mask, region);
        this.proxyLevel = FabricLevelProxy.getInstance(serverLevel, this);
        this.mutableBlockPlaceContext = new FabricMutableBlockPlaceContext(proxyLevel);
    }

    /**
     * For {@link #fork()}: shares the second-pass map, thread instances and finished
     * flag with the parent, so {@code flush()} sees every thread's entries.
     */
    private FabricPlacementStateProcessor(Extent extent, BlockTypeMask mask,
            Map<SecondPass, Character> crossChunkSecondPasses, ServerLevel serverLevel,
            ThreadLocal<PlacementStateProcessor> threadProcessors, Region region,
            AtomicBoolean finished) {
        super(extent, mask, crossChunkSecondPasses, threadProcessors, region, finished);
        this.proxyLevel = FabricLevelProxy.getInstance(serverLevel, this);
        this.mutableBlockPlaceContext = new FabricMutableBlockPlaceContext(proxyLevel);
    }

    @Override
    protected char getStateAtFor(int x, int y, int z, BlockState state,
            Vector3 clickPos, Direction clickedFaceDirection, BlockVector3 clickedBlock) {
        Block block = FabricAdapter.adapt(state.getBlockType());
        Vec3 pos = new Vec3(clickPos.x(), clickPos.y(), clickPos.z());
        net.minecraft.core.Direction side = net.minecraft.core.Direction.valueOf(clickedFaceDirection.toString());
        BlockPos blockPos = new BlockPos(clickedBlock.x(), clickedBlock.y(), clickedBlock.z());
        net.minecraft.world.level.block.state.BlockState newState;
        try {
            newState = block.getStateForPlacement(mutableBlockPlaceContext.withSetting(
                    new BlockHitResult(pos, side, blockPos, false),
                    side.getOpposite()
            ));
        } catch (RuntimeException e) {
            // some (modded) blocks' placement logic touches proxy fields that were never
            // initialized; keep the block as is instead of aborting the whole edit
            if (WARNED_BLOCKS.add(state.getBlockType().id())) {
                LogManagerCompat.getLogger().warn(
                        "//fixblocks: {} 放置形态计算失败，保持原状", state.getBlockType().id(), e);
            }
            return state.getOrdinalChar();
        }
        return newState == null ? BlockTypesCache.ReservedIDs.AIR : FabricAdapter.adapt(newState).getOrdinalChar();
    }

    @Override
    public Extent construct(Extent child) {
        if (child == getExtent()) {
            return this;
        }
        return new FabricPlacementStateProcessor(child, mask, region, proxyLevel.serverLevel);
    }

    @Override
    public PlacementStateProcessor fork() {
        return new FabricPlacementStateProcessor(
                extent,
                mask,
                postCompleteSecondPasses,
                proxyLevel.serverLevel,
                threadProcessors,
                region,
                finished
        );
    }
}
