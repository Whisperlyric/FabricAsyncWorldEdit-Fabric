package com.sk89q.worldedit.fabric.mixin;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.function.operation.ChangeSetExecutor;
import com.sk89q.worldedit.history.changeset.ChangeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.EOFException;

/**
 * 功能名：历史条目容错
 * A history file truncated by a hard crash (force-killing the game mid-write) makes
 * every //undo and //redo retry the same entry and fail with an EOFException wrapped
 * in a RuntimeException — and because LocalSession only advances its history pointer
 * after setBlocks succeeds, the corrupt entry blocks all older entries forever.
 * Treat an EOF as an empty change set: the entry is consumed, later entries stay
 * reachable, and the loss is limited to the one broken snapshot.
 */
@Mixin(value = LocalSession.class, remap = false)
public class MixinLocalSession {

    private static final Logger LOGGER = LoggerFactory.getLogger("fawe-fabric/history");

    @Redirect(
        method = {"undo", "redo"},
        at = @At(
            value = "INVOKE",
            target = "Lcom/sk89q/worldedit/EditSession;setBlocks(Lcom/sk89q/worldedit/history/changeset/ChangeSet;Lcom/sk89q/worldedit/function/operation/ChangeSetExecutor$Type;)V",
            remap = false))
    private void faweFabric$skipTruncatedHistory(EditSession session, ChangeSet changeSet, ChangeSetExecutor.Type type) {
        try {
            session.setBlocks(changeSet, type);
        } catch (RuntimeException e) {
            if (hasEOF(e)) {
                LOGGER.warn("History entry is truncated (previous crash mid-write); skipping this entry, older entries remain available.");
                return;
            }
            throw e;
        }
    }

    private static boolean hasEOF(Throwable throwable) {
        while (throwable != null) {
            if (throwable instanceof EOFException) {
                return true;
            }
            throwable = throwable.getCause();
        }
        return false;
    }
}
