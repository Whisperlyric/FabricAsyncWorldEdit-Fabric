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

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.fabric.FaweFabric;
import com.mojang.brigadier.CommandDispatcher;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.command.util.PermissionCondition;
import com.sk89q.worldedit.event.platform.ConfigurationLoadEvent;
import com.sk89q.worldedit.event.platform.PlatformReadyEvent;
import com.sk89q.worldedit.event.platform.PlatformUnreadyEvent;
import com.sk89q.worldedit.event.platform.PlatformsRegisteredEvent;
import com.sk89q.worldedit.event.platform.SessionIdleEvent;
import com.sk89q.worldedit.fabric.net.SelViewPayload;
import com.sk89q.worldedit.fabric.net.handler.WECUIPacketHandler;
import com.sk89q.worldedit.extension.platform.Capability;
import com.sk89q.worldedit.extension.platform.Platform;
import com.sk89q.worldedit.extension.platform.PlatformManager;
import com.sk89q.worldedit.internal.anvil.ChunkDeleter;
import com.sk89q.worldedit.internal.event.InteractionDebouncer;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.lifecycle.Lifecycled;
import com.sk89q.worldedit.util.lifecycle.SimpleLifecycled;
import com.sk89q.worldedit.world.biome.BiomeCategory;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BlockCategory;
import com.sk89q.worldedit.world.block.BlockTypes;
import com.sk89q.worldedit.world.entity.EntityType;
import com.sk89q.worldedit.world.gamemode.GameModes;
import com.sk89q.worldedit.world.generation.StructureType;
import com.sk89q.worldedit.world.item.ItemCategory;
import com.sk89q.worldedit.world.item.ItemType;
import com.sk89q.worldedit.world.weather.WeatherTypes;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.BlockHitResult;
import org.apache.logging.log4j.Logger;
import org.enginehub.piston.Command;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.sk89q.worldedit.fabric.FabricAdapter.adaptPlayer;
import static com.sk89q.worldedit.internal.anvil.ChunkDeleter.DELCHUNKS_FILE_NAME;

/**
 * The Fabric implementation of WorldEdit.
 */
public class FabricWorldEdit implements ModInitializer {

    private static final Logger LOGGER = LogManagerCompat.getLogger();
    public static final String MOD_ID = "fawe";

    public static final Lifecycled<MinecraftServer> LIFECYCLED_SERVER;

    static {
        SimpleLifecycled<MinecraftServer> lifecycledServer = SimpleLifecycled.invalid();
        ServerLifecycleEvents.SERVER_STARTED.register(lifecycledServer::newValue);
        ServerLifecycleEvents.SERVER_STOPPING.register(__ -> lifecycledServer.invalidate());
        LIFECYCLED_SERVER = lifecycledServer;
    }

    /**
     * {@return current server's registry access} Not for long-term storage.
     */
    public static RegistryAccess registryAccess() {
        return LIFECYCLED_SERVER.valueOrThrow().registryAccess();
    }

    /**
     * Registries seen by {@link #getRegistry}, kept so edits still draining on the queue
     * when the server stops (final {@code //undo}, a queued mask edit) can finish their
     * history capture instead of dying on {@code IllegalStateException: Currently invalid}.
     * The registry objects stay usable in memory after stop.
     */
    private static final ConcurrentMap<ResourceKey<? extends Registry<?>>, Registry<?>> REGISTRY_CACHE =
            new ConcurrentHashMap<>();

    /**
     * {@return current server's registry} Not for long-term storage.
     *
     * @param key the registry key
     */
    @SuppressWarnings("unchecked")
    public static <T> Registry<T> getRegistry(ResourceKey<Registry<T>> key) {
        try {
            Registry<T> registry = LIFECYCLED_SERVER.valueOrThrow().registryAccess().lookupOrThrow(key);
            REGISTRY_CACHE.put(key, registry);
            return registry;
        } catch (IllegalStateException e) {
            // server already stopping: fall back to the last registry seen for this key
            Registry<?> cached = REGISTRY_CACHE.get(key);
            if (cached != null) {
                return (Registry<T>) cached;
            }
            throw e;
        }
    }

    private FabricPermissionsProvider provider;

    public static FabricWorldEdit inst;

    private InteractionDebouncer debouncer;
    private FabricPlatform platform;
    private FabricConfiguration config;
    private Path workingDir;

    private ModContainer container;

    public FabricWorldEdit() {
        inst = this;
    }

    @Override
    public void onInitialize() {
        WECUIPacketHandler.init();
        PayloadTypeRegistry.clientboundPlay().register(SelViewPayload.TYPE, SelViewPayload.CODEC);

        this.container = FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow(
            () -> new IllegalStateException("FAWE Fabric mod missing in Fabric")
        );

        // Setup working directory
        workingDir = FabricLoader.getInstance().getConfigDir().resolve("worldedit");
        if (!Files.exists(workingDir)) {
            try {
                Files.createDirectory(workingDir);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        this.platform = new FabricPlatform(this);
        debouncer = new InteractionDebouncer(platform);

        WorldEdit.getInstance().getPlatformManager().register(platform);

        config = new FabricConfiguration(this);
        this.provider = getInitialPermissionsProvider();

        ServerTickEvents.END_SERVER_TICK.register(ThreadSafeCache.getInstance());
        ServerTickEvents.END_SERVER_TICK.register(server -> ChunkPackets.flushBiomeResends());
        // Chunk writes from FAWE's queue threads are applied here, budgeted per tick,
        // so a large //undo spreads over several ticks instead of freezing one.
        ServerTickEvents.START_SERVER_TICK.register(
                com.sk89q.worldedit.fabric.internal.FabricPendingApplies::tick);
        // Registered after the applies hook on purpose: deferred light rechecks must
        // only enqueue once their chunk writes have actually landed.
        ServerTickEvents.START_SERVER_TICK.register(
                server -> com.sk89q.worldedit.fabric.internal.FabricRelightQueue.tick());
        CommandRegistrationCallback.EVENT.register(this::registerCommands);
        ServerLifecycleEvents.SERVER_STARTING.register(this::onStartingServer);
        ServerLifecycleEvents.SERVER_STARTED.register(this::onStartServer);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onStopServer);
        ServerPlayConnectionEvents.DISCONNECT.register(this::onPlayerDisconnect);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                this.onPlayerJoin(handler.player, server));
        AttackBlockCallback.EVENT.register(this::onLeftClickBlock);
        UseBlockCallback.EVENT.register(this::onRightClickBlock);
        UseItemCallback.EVENT.register(this::onRightClickItem);
        LOGGER.info("WorldEdit for Fabric (version {}) is loaded", getInternalVersion());

        // Sodium 0.9.x's NVIDIA driver workarounds (GL_DEBUG_OUTPUT_SYNCHRONOUS, threaded
        // optimizations off) serialize every startup GL call of a large pack and stall
        // launch for minutes. Sodium has no switch, so clear its workaround set by
        // reflection (filled during entrypoints and never changed afterwards).
        try {
            Class<?> workarounds = Class.forName(
                "net.caffeinemc.mods.sodium.client.compatibility.workarounds.Workarounds");
            java.lang.reflect.Field field = workarounds.getDeclaredField("ACTIVE_WORKAROUNDS");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.atomic.AtomicReference<java.util.Set<?>> ref =
                (java.util.concurrent.atomic.AtomicReference<java.util.Set<?>>) field.get(null);
            // the set is an unmodifiable wrapper (clear() throws), so swap the whole
            // reference; Sodium's init has filled it by now and never refills it, so GL
            // context creation (after all entrypoints) reads the empty set
            ref.set(java.util.Collections.emptySet());
            LOGGER.info("Sodium driver workarounds cleared (GL sync workaround disabled)");
        } catch (ClassNotFoundException ignored) {
            // Sodium absent: nothing to do
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Failed to clear Sodium workarounds", e);
        }
        // do not touch Settings/TranslationManager etc. here, before the platform is
        // registered: lazy references cache the NoCapablePlatformException and break
        // translations and config for the whole session (messages fall back to English)
    }

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess, Commands.CommandSelection environment) {
        WorldEdit.getInstance().getEventBus().post(new PlatformsRegisteredEvent());
        PlatformManager manager = WorldEdit.getInstance().getPlatformManager();
        Platform commandsPlatform = manager.queryCapability(Capability.USER_COMMANDS);
        if (commandsPlatform != platform || !platform.isHookingEvents()) {
            // We're not in control of commands/events -- do not register.
            return;
        }

        List<Command> commands = manager.getPlatformCommandManager().getCommandManager()
            .getAllCommands().toList();
        for (Command command : commands) {
            CommandWrapper.register(dispatcher, command);
            Set<String> perms = command.getCondition().as(PermissionCondition.class)
                .map(PermissionCondition::getPermissions)
                .orElseGet(Collections::emptySet);
            if (!perms.isEmpty()) {
                perms.forEach(getPermissionsProvider()::registerPermission);
            }
        }

        // Per-player edit visibility mode: sync applies with immediate client updates,
        // async batches them into full chunk re-sends at flush (freeze-update style).
        dispatcher.register(Commands.literal("fawemode")
            .requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN))
            .then(Commands.literal("sync").executes(ctx -> {
                java.util.UUID uuid = ctx.getSource().getPlayerOrException().getUUID();
                EditModeRegistry.setAsync(uuid, false);
                ctx.getSource().sendSuccess(
                    () -> net.minecraft.network.chat.Component.literal("FAWE edit mode: sync (immediate block updates)"), true);
                return 1;
            }))
            .then(Commands.literal("async").executes(ctx -> {
                java.util.UUID uuid = ctx.getSource().getPlayerOrException().getUUID();
                EditModeRegistry.setAsync(uuid, true);
                ctx.getSource().sendSuccess(
                    () -> net.minecraft.network.chat.Component.literal("FAWE edit mode: async (batched chunk re-sends)"), true);
                return 1;
            }))
            .then(Commands.literal("status").executes(ctx -> {
                java.util.UUID uuid = ctx.getSource().getPlayerOrException().getUUID();
                boolean async = EditModeRegistry.isAsync(uuid);
                ctx.getSource().sendSuccess(
                    () -> net.minecraft.network.chat.Component.literal("FAWE edit mode: " + (async ? "async" : "sync")), false);
                return 1;
            })));

        // CUI display control: /fawecui shows again and resends the selection, off hides,
        // on shows explicitly. Hidden = server stops CUI packets, client clears CUI state.
        dispatcher.register(Commands.literal("fawecui")
            .requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN))
            .executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                SelViewRegistry.setMode(player, SelViewRegistry.Mode.CUI);
                ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    "FAWE CUI: 已恢复显示并重发选区"), false);
                return 1;
            })
            .then(Commands.literal("off").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                SelViewRegistry.setMode(player, SelViewRegistry.Mode.OFF);
                ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    "FAWE CUI: 已隐藏（/fawecui 恢复）"), false);
                return 1;
            }))
            .then(Commands.literal("on").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                SelViewRegistry.setMode(player, SelViewRegistry.Mode.CUI);
                ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    "FAWE CUI: 已恢复显示并重发选区"), false);
                return 1;
            })));

        registerHistoryDataCommand(dispatcher);
    }

    private void registerHistoryDataCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        FabricConfiguration config = getConfig();
        dispatcher.register(Commands.literal("fawedata")
            .requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN))
            .then(Commands.literal("status").executes(ctx -> {
                ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    "FAWE data: tiles=" + config.historyTiles
                        + ", tile-ops=" + config.tileOperations
                        + ", biomes=" + config.historyBiomes
                        + ", entities=" + config.historyEntities
                        + ", entity-uuid-skip=" + config.historyEntityUuidSkip), false);
                return 1;
            }))
            .then(Commands.literal("tiles")
                .then(Commands.literal("on").executes(ctx -> setHistoryFlag(
                    ctx.getSource(), config, "history.tiles", true, v -> config.historyTiles = v, "tiles")))
                .then(Commands.literal("off").executes(ctx -> setHistoryFlag(
                    ctx.getSource(), config, "history.tiles", false, v -> config.historyTiles = v, "tiles"))))
            .then(Commands.literal("tile-ops")
                .then(Commands.literal("on").executes(ctx -> setHistoryFlag(
                    ctx.getSource(), config, "tiles.operations", true, v -> config.tileOperations = v, "tile-ops")))
                .then(Commands.literal("off").executes(ctx -> setHistoryFlag(
                    ctx.getSource(), config, "tiles.operations", false, v -> config.tileOperations = v, "tile-ops"))))
            .then(Commands.literal("biomes")
                .then(Commands.literal("on").executes(ctx -> setHistoryFlag(
                    ctx.getSource(), config, "history.biomes", true, v -> config.historyBiomes = v, "biomes")))
                .then(Commands.literal("off").executes(ctx -> setHistoryFlag(
                    ctx.getSource(), config, "history.biomes", false, v -> config.historyBiomes = v, "biomes"))))
            .then(Commands.literal("entities")
                .then(Commands.literal("on").executes(ctx -> setHistoryFlag(
                    ctx.getSource(), config, "history.entities", true, v -> config.historyEntities = v, "entities")))
                .then(Commands.literal("off").executes(ctx -> setHistoryFlag(
                    ctx.getSource(), config, "history.entities", false, v -> config.historyEntities = v, "entities"))))
            .then(Commands.literal("entity-uuid-skip")
                .then(Commands.literal("on").executes(ctx -> setHistoryFlag(
                    ctx.getSource(), config, "history.entity-uuid-skip", true,
                    v -> config.historyEntityUuidSkip = v, "entity-uuid-skip")))
                .then(Commands.literal("off").executes(ctx -> setHistoryFlag(
                    ctx.getSource(), config, "history.entity-uuid-skip", false,
                    v -> config.historyEntityUuidSkip = v, "entity-uuid-skip")))));
    }

    private static int setHistoryFlag(
        CommandSourceStack source,
        FabricConfiguration config,
        String key,
        boolean value,
        java.util.function.Consumer<Boolean> setter,
        String name
    ) {
        setter.accept(value);
        config.saveBoolean(key, value);
        source.sendSuccess(
            () -> net.minecraft.network.chat.Component.literal("FAWE data: " + name + " = " + value), true);
        return 1;
    }

    private FabricPermissionsProvider getInitialPermissionsProvider() {
        FabricPermissionsProvider vanilla = new FabricPermissionsProvider.VanillaPermissionsProvider(platform);
        // use fabric-api's permission module when present (permission mods such as
        // LuckPerms Fabric take effect), otherwise keep the vanilla op/creative checks
        FabricPermissionsProvider viaApi = FabricPermissionsProvider.FabricApiPermissionsProvider.create(vanilla);
        return viaApi != null ? viaApi : vanilla;
    }

    private void setupRegistries(MinecraftServer server) {
        // Ensure FAWE block registry (and block states) is initialized once from platform block registry data.
        BlockTypes.size();
        // Items
        for (Identifier name : server.registryAccess().lookupOrThrow(Registries.ITEM).keySet()) {
            String key = name.toString();
            if (ItemType.REGISTRY.get(key) == null) {
                ItemType.REGISTRY.register(key, new ItemType(key));
            }
        }
        // Entities
        for (Identifier name : server.registryAccess().lookupOrThrow(Registries.ENTITY_TYPE).keySet()) {
            String key = name.toString();
            if (EntityType.REGISTRY.get(key) == null) {
                EntityType.REGISTRY.register(key, new EntityType(key));
            }
        }
        // Biomes
        for (Identifier name : server.registryAccess().lookupOrThrow(Registries.BIOME).keySet()) {
            String key = name.toString();
            if (BiomeType.REGISTRY.get(key) == null) {
                BiomeType.REGISTRY.register(key, new BiomeType(key));
            }
        }
        // Tags
        server.registryAccess().lookupOrThrow(Registries.BLOCK).getTags().map(t -> t.key().location()).forEach(name -> {
            String key = name.toString();
            if (BlockCategory.REGISTRY.get(key) == null) {
                BlockCategory.REGISTRY.register(key, new BlockCategory(key));
            }
        });
        server.registryAccess().lookupOrThrow(Registries.ITEM).getTags().map(t -> t.key().location()).forEach(name -> {
            String key = name.toString();
            if (ItemCategory.REGISTRY.get(key) == null) {
                ItemCategory.REGISTRY.register(key, new ItemCategory(key));
            }
        });
        Registry<Biome> biomeRegistry = server.registryAccess().lookupOrThrow(Registries.BIOME);
        biomeRegistry.getTags().forEach(tag -> {
            String key = tag.key().location().toString();
            if (BiomeCategory.REGISTRY.get(key) == null) {
                BiomeCategory.REGISTRY.register(key, new BiomeCategory(
                    key,
                    () -> biomeRegistry.get(tag.key())
                        .stream()
                        .flatMap(HolderSet.Named::stream)
                        .map(Holder::value)
                        .map(FabricAdapter::adapt)
                        .collect(Collectors.toSet()))
                );
            }
        });
        // Features are not registered yet for 26.3 (Registries.CONFIGURED_FEATURE changed).
        // Structures
        for (Identifier name : server.registryAccess().lookupOrThrow(Registries.STRUCTURE).keySet()) {
            String key = name.toString();
            if (StructureType.REGISTRY.get(key) == null) {
                StructureType.REGISTRY.register(key, new StructureType(key));
            }
        }
        // Force lazy static init for generic game values used by parsers.
        GameModes.get("");
        WeatherTypes.get("");
    }

    private void onStartingServer(MinecraftServer minecraftServer) {
        // Drop any deferred work left by an earlier world/server in this JVM.
        com.sk89q.worldedit.fabric.internal.FabricPendingApplies.clear();
        final Path delChunks = workingDir.resolve(DELCHUNKS_FILE_NAME);
        if (Files.exists(delChunks)) {
            ChunkDeleter.runFromFile(delChunks, true);
        }
    }

    private void onStartServer(MinecraftServer minecraftServer) {
        initFawe();
        setupRegistries(minecraftServer);

        // SERVER_STARTED can run on the render thread for an integrated server.
        // FAWE's QueueHandler checks Fawe.isMainThread() from the server tick, so pin
        // the main thread to the actual server thread on the next server task.
        minecraftServer.execute(() -> {
            if (Fawe.instance() != null) {
                Fawe.instance().setMainThread();
            }
        });

        config.load();
        WorldEdit.getInstance().getEventBus().post(new ConfigurationLoadEvent(config));
        WorldEdit.getInstance().getEventBus().post(new PlatformReadyEvent(platform));
    }

    private void onStopServer(MinecraftServer minecraftServer) {
        // Apply deferred writes before the world closes. If the event is not on the
        // server thread, leave the queue intact; the next SERVER_STARTING clears it.
        int pendingApplies = com.sk89q.worldedit.fabric.internal.FabricPendingApplies.drainCompletely(minecraftServer);
        WorldEdit worldEdit = WorldEdit.getInstance();
        worldEdit.getSessionManager().unload();
        com.sk89q.worldedit.fabric.internal.NbtInterner.clear();
        com.sk89q.worldedit.fabric.internal.FabricRelightQueue.clear();
        WorldEdit.getInstance().getEventBus().post(new PlatformUnreadyEvent(platform));
        if (Fawe.instance() != null) {
            Fawe.instance().onDisable();
        }
        if (pendingApplies == 0) {
            com.sk89q.worldedit.fabric.internal.FabricPendingApplies.clear();
        }
    }

    private void initFawe() {
        if (Fawe.instance() != null) {
            return;
        }
        try {
            Fawe.set(new FaweFabric(this));
            Fawe.setupInjector();
            // FabricChunkGet now extends CharGetBlocks and provides real chunk access.
            Settings settings = Settings.settings();
            Settings.LIMITS defaultLimit = settings.LIMITS.get("default");
            if (defaultLimit != null) {
                // on: undo/history held up on 32M-block edits, and without fast placement
                // large edits only get the slow per-block path (~550k blocks/s)
                defaultLimit.FAST_PLACEMENT = true;
            }
            settings.HISTORY.USE_DATABASE = false;
            settings.REGION_RESTRICTIONS = false;
            // NMSRelighter (MODE=1/2's original relighter) must never come back: its
            // fix pass re-read chunks through FAWE's queue, which deadlocked against
            // the server thread (//resize 20 on 3x1x3 wedged the keyed queue). The
            // platform relighter is now FabricVanillaRelighter — it only enqueues
            // light rechecks on the vanilla facade (ScalableLux accelerates it when
            // installed) — so MODE keeps its FAWE-configured value, default 1.
            // "-Dfawe.lighting.relighter=none" restores the old hard-off behaviour.
            if ("none".equals(System.getProperty("fawe.lighting.relighter", "vanilla"))) {
                settings.LIGHTING.MODE = 0;
            }
            settings.LIGHTING.ASYNC = false;
            settings.LIGHTING.DELAY_PACKET_SENDING = false;
        } catch (Exception e) {
            LOGGER.error("Failed to initialize FAWE for Fabric", e);
        }
    }

    private boolean skipEvents() {
        return platform == null || !platform.isHookingEvents();
    }

    private boolean skipInteractionEvent(Player player, InteractionHand hand) {
        return skipEvents() || hand != InteractionHand.MAIN_HAND || player.level().isClientSide() || !(player instanceof ServerPlayer);
    }

    private InteractionResult onLeftClickBlock(Player playerEntity, Level world, InteractionHand hand, BlockPos blockPos, Direction direction) {
        if (skipInteractionEvent(playerEntity, hand)) {
            return InteractionResult.PASS;
        }

        WorldEdit we = WorldEdit.getInstance();
        FabricPlayer player = adaptPlayer((ServerPlayer) playerEntity);
        FabricWorld localWorld = getWorld(world);
        // A click may read the world right after a command that is still applying.
        com.sk89q.worldedit.fabric.internal.FabricPendingApplies.drainAll();
        Location pos = new Location(localWorld,
            blockPos.getX(),
            blockPos.getY(),
            blockPos.getZ()
        );
        com.sk89q.worldedit.util.Direction weDirection = FabricAdapter.adaptEnumFacing(direction);

        boolean result = we.handleBlockLeftClick(player, pos, weDirection) || we.handleArmSwing(player);
        debouncer.setLastInteraction(player, result);

        return result ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    private InteractionResult onRightClickBlock(Player playerEntity, Level world, InteractionHand hand, BlockHitResult blockHitResult) {
        if (skipInteractionEvent(playerEntity, hand)) {
            return InteractionResult.PASS;
        }

        WorldEdit we = WorldEdit.getInstance();
        FabricPlayer player = adaptPlayer((ServerPlayer) playerEntity);
        FabricWorld localWorld = getWorld(world);
        com.sk89q.worldedit.fabric.internal.FabricPendingApplies.drainAll();
        Location pos = new Location(localWorld,
            blockHitResult.getBlockPos().getX(),
            blockHitResult.getBlockPos().getY(),
            blockHitResult.getBlockPos().getZ()
        );
        com.sk89q.worldedit.util.Direction direction = FabricAdapter.adaptEnumFacing(blockHitResult.getDirection());

        boolean result = we.handleBlockRightClick(player, pos, direction) || we.handleRightClick(player);
        debouncer.setLastInteraction(player, result);

        return result ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    public void onLeftClickAir(ServerPlayer playerEntity, InteractionHand hand) {
        if (skipInteractionEvent(playerEntity, hand)) {
            return;
        }

        WorldEdit we = WorldEdit.getInstance();
        FabricPlayer player = adaptPlayer(playerEntity);

        Optional<Boolean> previousResult = debouncer.getDuplicateInteractionResult(player);
        if (previousResult.isPresent()) {
            return;
        }

        com.sk89q.worldedit.fabric.internal.FabricPendingApplies.drainAll();
        boolean result = we.handleArmSwing(player);
        debouncer.setLastInteraction(player, result);
    }
    private InteractionResult onRightClickItem(Player playerEntity, Level world, InteractionHand hand) {
        if (skipInteractionEvent(playerEntity, hand)) {
            return InteractionResult.PASS;
        }

        WorldEdit we = WorldEdit.getInstance();
        FabricPlayer player = adaptPlayer((ServerPlayer) playerEntity);

        Optional<Boolean> previousResult = debouncer.getDuplicateInteractionResult(player);
        if (previousResult.isPresent()) {
            return previousResult.get() ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }

        com.sk89q.worldedit.fabric.internal.FabricPendingApplies.drainAll();
        boolean result = we.handleRightClick(player);
        debouncer.setLastInteraction(player, result);

        return result ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    /**
     * Restores on-disk history through FAWE's public hook
     * {@link LocalSession#loadSessionHistoryFromDisk}: edits under
     * {@code history/<world>/<uuid>/} are loaded back so //undo survives restarts.
     * Idempotent per world (LocalSession skips by currentWorld).
     */
    private void onPlayerJoin(ServerPlayer player, MinecraftServer server) {
        if (player.level() instanceof ServerLevel serverLevel) {
            LocalSession session = getSession(player);
            session.loadSessionHistoryFromDisk(player.getUUID(), getWorld(serverLevel));
        }
    }

    private void onPlayerDisconnect(ServerGamePacketListenerImpl handler, MinecraftServer server) {
        debouncer.clearInteraction(adaptPlayer(handler.player));
        EditModeRegistry.clear(handler.player.getUUID());
        FabricPlayer.clearMeta(handler.player.getUUID());

        WorldEdit.getInstance().getEventBus()
            .post(new SessionIdleEvent(new FabricPlayer.SessionKeyImpl(handler.player)));
    }

    /**
     * Get the configuration.
     *
     * @return the Fabric configuration
     */
    FabricConfiguration getConfig() {
        return this.config;
    }

    /**
     * Get the session for a player.
     *
     * @param player the player
     * @return the session
     */
    public LocalSession getSession(ServerPlayer player) {
        checkNotNull(player);
        return WorldEdit.getInstance().getSessionManager().get(adaptPlayer(player));
    }

    /**
     * Get the WorldEdit proxy for the given world.
     *
     * @param world the world
     * @return the WorldEdit world
     */
    public FabricWorld getWorld(Level world) {
        checkNotNull(world);
        return new FabricWorld(world);
    }

    /**
     * Get the WorldEdit proxy for the platform.
     *
     * @return the WorldEdit platform
     */
    public Platform getPlatform() {
        return this.platform;
    }

    /**
     * Get the working directory where WorldEdit's files are stored.
     *
     * @return the working directory
     */
    public Path getWorkingDir() {
        return this.workingDir;
    }

    /**
     * Get the version of the WorldEdit-Fabric implementation.
     *
     * @return a version string
     */
    String getInternalVersion() {
        return container.getMetadata().getVersion().getFriendlyString();
    }

    public void setPermissionsProvider(FabricPermissionsProvider provider) {
        this.provider = provider;
    }

    public FabricPermissionsProvider getPermissionsProvider() {
        return provider;
    }
}
