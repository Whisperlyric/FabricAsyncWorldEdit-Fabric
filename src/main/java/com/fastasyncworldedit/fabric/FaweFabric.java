package com.fastasyncworldedit.fabric;

import com.fastasyncworldedit.core.FAWEPlatformAdapterImpl;
import com.fastasyncworldedit.core.IFawe;
import com.fastasyncworldedit.core.queue.implementation.QueueHandler;
import com.fastasyncworldedit.core.queue.implementation.preloader.Preloader;
import com.fastasyncworldedit.core.regions.FaweMaskManager;
import com.fastasyncworldedit.core.util.TaskManager;
import com.sk89q.worldedit.fabric.FabricWorldEdit;
import net.minecraft.server.MinecraftServer;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Supplier;

public class FaweFabric implements IFawe {

    private final FabricWorldEdit worldEdit;
    private final Supplier<MinecraftServer> serverSupplier;
    private final FabricTaskManager taskManager;
    private final FabricQueueHandler queueHandler;
    private final FAWEPlatformAdapterImpl platformAdapter;

    public FaweFabric(FabricWorldEdit worldEdit) {
        this.worldEdit = worldEdit;
        this.serverSupplier = () -> FabricWorldEdit.LIFECYCLED_SERVER.value().orElse(null);
        this.taskManager = new FabricTaskManager(serverSupplier);
        this.queueHandler = new FabricQueueHandler();
        this.platformAdapter = new FabricPlatformAdapter();
    }

    @Override
    public File getDirectory() {
        return worldEdit.getWorkingDir().toFile();
    }

    @Override
    public TaskManager getTaskManager() {
        return taskManager;
    }

    @Override
    public Collection<FaweMaskManager> getMaskManagers() {
        return Collections.emptyList();
    }

    @Override
    public String getPlatform() {
        return "Fabric";
    }

    @Override
    public UUID getUUID(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String getName(UUID uuid) {
        return uuid.toString();
    }

    @Override
    public QueueHandler getQueueHandler() {
        return queueHandler;
    }

    @Override
    public Preloader getPreloader(boolean initialise) {
        return null;
    }

    @Override
    public FAWEPlatformAdapterImpl getPlatformAdapter() {
        return platformAdapter;
    }
}
