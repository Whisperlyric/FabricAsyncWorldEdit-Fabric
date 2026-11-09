package com.fastasyncworldedit.fabric;

import com.fastasyncworldedit.core.util.TaskManager;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class FabricTaskManager extends TaskManager {

    private static final long TICK_MS = 50L;

    private final Supplier<MinecraftServer> serverSupplier;
    private final ScheduledExecutorService mainScheduler;
    private final ScheduledExecutorService asyncScheduler;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    public FabricTaskManager(Supplier<MinecraftServer> serverSupplier) {
        this.serverSupplier = serverSupplier;
        this.mainScheduler = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory("fawe-fabric-main"));
        this.asyncScheduler = Executors.newScheduledThreadPool(2, new DaemonThreadFactory("fawe-fabric-async"));
    }

    @Override
    public int repeat(@Nonnull Runnable runnable, int interval) {
        long delayMs = Math.max(0, interval) * TICK_MS;
        int id = nextId.getAndIncrement();
        ScheduledFuture<?> future = mainScheduler.scheduleAtFixedRate(
            () -> runOnMain(runnable),
            delayMs,
            delayMs,
            TimeUnit.MILLISECONDS
        );
        tasks.put(id, future);
        return id;
    }

    @Override
    public int repeatAsync(@Nonnull Runnable runnable, int interval) {
        long delayMs = Math.max(0, interval) * TICK_MS;
        int id = nextId.getAndIncrement();
        ScheduledFuture<?> future = asyncScheduler.scheduleAtFixedRate(
            runnable,
            delayMs,
            delayMs,
            TimeUnit.MILLISECONDS
        );
        tasks.put(id, future);
        return id;
    }

    @Override
    public void async(@Nonnull Runnable runnable) {
        asyncScheduler.execute(runnable);
    }

    @Override
    public void task(@Nonnull Runnable runnable) {
        runOnMain(runnable);
    }

    @Override
    public void later(@Nonnull Runnable runnable, int delay) {
        long delayMs = Math.max(0, delay) * TICK_MS;
        int id = nextId.getAndIncrement();
        ScheduledFuture<?> future = mainScheduler.schedule(() -> runOnMain(runnable), delayMs, TimeUnit.MILLISECONDS);
        tasks.put(id, future);
    }

    @Override
    public void laterAsync(@Nonnull Runnable runnable, int delay) {
        long delayMs = Math.max(0, delay) * TICK_MS;
        int id = nextId.getAndIncrement();
        ScheduledFuture<?> future = asyncScheduler.schedule(runnable, delayMs, TimeUnit.MILLISECONDS);
        tasks.put(id, future);
    }

    @Override
    public void cancel(int task) {
        if (task == -1) {
            return;
        }
        ScheduledFuture<?> future = tasks.remove(task);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void runOnMain(Runnable runnable) {
        MinecraftServer server = serverSupplier.get();
        if (server != null) {
            // Integrated servers run SERVER_STARTED on the render thread, so the
            // one-shot pin in FabricWorldEdit can race with FAWE's first queue
            // task. Pin again on the task that is about to run: server.execute()
            // guarantees this is the real server thread (or runs inline during
            // shutdown, where it is already the current thread).
            server.execute(() -> {
                if (com.fastasyncworldedit.core.Fawe.instance() != null) {
                    com.fastasyncworldedit.core.Fawe.instance().setMainThread();
                }
                runnable.run();
            });
        } else {
            runnable.run();
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private final AtomicInteger threadId = new AtomicInteger(1);

        private DaemonThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, namePrefix + "-" + threadId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
