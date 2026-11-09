package com.sk89q.worldedit.fabric.internal;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Queues chunk writes that must run on the server thread. FAWE's queue records an
 * apply here; the task runs before the next command or at the start of the next
 * server tick, within a time budget.
 */
public final class FabricPendingApplies {

    /** Leave most of the tick to the server; a big edit catches up over a few ticks. */
    private static final long TICK_BUDGET_NS =
            Long.getLong("fawe.apply.tickBudgetMs", 12L) * 1_000_000L;
    /** A command may block briefly, but must not freeze on a huge backlog. */
    private static final long COMMAND_BUDGET_NS =
            Long.getLong("fawe.apply.commandBudgetMs", 30L) * 1_000_000L;

    private static final Map<ServerLevel, ConcurrentLinkedQueue<Task>> PENDING = new ConcurrentHashMap<>();

    private FabricPendingApplies() {
    }

    /**
     * Registers {@code apply} and returns a one-shot task that runs it at most once,
     * whether it is run here or picked up later by the tick handler.
     */
    public static Runnable register(ServerLevel level, Runnable apply) {
        ConcurrentLinkedQueue<Task> tasks = PENDING.computeIfAbsent(level, ignored -> new ConcurrentLinkedQueue<>());
        Task task = new Task(apply);
        tasks.add(task);
        return task;
    }

    /** Whether {@code level} still has chunk writes waiting to run on its server thread. */
    public static boolean hasPending(ServerLevel level) {
        ConcurrentLinkedQueue<Task> queue = PENDING.get(level);
        return queue != null && !queue.isEmpty();
    }

    /** Runs pending applies for one server, within this tick's budget. */
    public static void tick(MinecraftServer server) {
        drain(server, TICK_BUDGET_NS);
    }

    /**
     * Runs pending applies for the current server thread before a command reads the
     * world, within {@link #COMMAND_BUDGET_NS}. Anything left stays queued for the
     * tick handler.
     */
    public static void drainAll() {
        drain(null, COMMAND_BUDGET_NS);
    }

    /**
     * Runs every pending apply of {@code server} without a budget. Called from the
     * SERVER_STOPPING handler. Returns the number of tasks that could not be run
     * because this thread does not own their server (for example an integrated
     * server event fired off-thread); the caller must not silently discard those.
     */
    public static int drainCompletely(MinecraftServer server) {
        drain(server, Long.MAX_VALUE);
        int left = 0;
        for (Map.Entry<ServerLevel, ConcurrentLinkedQueue<Task>> entry : PENDING.entrySet()) {
            if (entry.getKey().getServer() == server) {
                left += entry.getValue().size();
            }
        }
        if (left > 0) {
            com.sk89q.worldedit.internal.util.LogManagerCompat.getLogger().warn(
                    "[FAWE-APPLY] {} deferred chunk applies never ran (their server thread did not reach them)",
                    left);
        }
        return left;
    }

    /** Drops all state; called on server stop after {@link #drainCompletely}. */
    public static void clear() {
        PENDING.clear();
    }

    private static int drain(MinecraftServer server, long budgetNanos) {
        if (PENDING.isEmpty()) {
            return 0;
        }
        long deadline = budgetNanos == Long.MAX_VALUE ? Long.MAX_VALUE : System.nanoTime() + budgetNanos;
        int applied = 0;
        boolean deferred = false;

        for (Map.Entry<ServerLevel, ConcurrentLinkedQueue<Task>> entry : PENDING.entrySet()) {
            ServerLevel level = entry.getKey();
            MinecraftServer owner = level.getServer();
            if (owner == null || !owner.isSameThread()) {
                continue;
            }
            if (server != null && owner != server) {
                continue;
            }
            Task task;
            while ((task = entry.getValue().poll()) != null) {
                try {
                    task.run();
                } catch (Exception e) {
                    // One chunk must not abort the whole tick/command drain; the
                    // apply itself logs its stage failures, this is the last resort.
                    com.sk89q.worldedit.internal.util.LogManagerCompat.getLogger().error(
                            "[FAWE-APPLY] deferred chunk apply failed", e);
                }
                applied++;
                // Checked after running: a drain always makes progress.
                if (System.nanoTime() >= deadline) {
                    deferred = true;
                    break;
                }
            }
            if (deferred) {
                break;
            }
        }

        return applied;
    }

    private static final class Task implements Runnable {

        private final Runnable apply;
        private final AtomicBoolean done = new AtomicBoolean();

        private Task(Runnable apply) {
            this.apply = apply;
        }

        @Override
        public void run() {
            // No queue bookkeeping here: an inline (server-thread) task stays queued
            // until the next drain polls it, and the done flag makes that poll a no-op.
            if (!done.compareAndSet(false, true)) {
                return;
            }
            apply.run();
        }
    }
}
