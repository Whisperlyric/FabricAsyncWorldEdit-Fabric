package com.sk89q.worldedit.fabric.mixin;

import com.fastasyncworldedit.core.function.mask.SingleBlockTypeMask;
import com.sk89q.worldedit.command.BrushCommands;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.mask.AbstractExtentMask;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.mask.MaskUnion;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(BrushCommands.class)
public class MixinBrushCommands {

    @ModifyArg(
        method = "extinguishBrush",
        at = @At(
            value = "INVOKE",
            target = "Lcom/fastasyncworldedit/core/command/tool/brush/BrushSettings;setMask(Lcom/sk89q/worldedit/function/mask/Mask;)Lcom/fastasyncworldedit/core/command/tool/brush/BrushSettings;"
        ),
        index = 0
    )
    private Mask fawe$includeSoulFire(Mask mask) {
        Extent extent = mask instanceof AbstractExtentMask extentMask
            ? extentMask.getExtent()
            : null;
        if (extent == null) {
            return mask;
        }
        return MaskUnion.of(mask, new SingleBlockTypeMask(extent, BlockTypes.SOUL_FIRE));
    }
}