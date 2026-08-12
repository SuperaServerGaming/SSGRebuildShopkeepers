package com.nisovin.shopkeepers.util.bukkit;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.checkerframework.checker.nullness.qual.Nullable;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import com.nisovin.shopkeepers.util.java.Validate;

/**
 * Scheduler related utilities.
 * <p>
 * SSG fork note: Migrated from the legacy {@link org.bukkit.scheduler.BukkitScheduler} to
 * Paper/Folia's region-aware schedulers ({@link Bukkit#getGlobalRegionScheduler()},
 * {@link Bukkit#getRegionScheduler()}, {@link Entity#getScheduler()},
 * {@link Bukkit#getAsyncScheduler()}). These APIs are also implemented by non-Folia Paper (where
 * they simply behave like the old main-thread scheduler), so this works unmodified on both.
 * There is no longer a single "main thread" concept: callers must say whether their task is
 * global bookkeeping (no location/entity ties), tied to a specific location/chunk, or tied to
 * mutating a specific live entity, and use the matching method below.
 */
public final class SchedulerUtils {

	/**
	 * Creates an {@link Executor} that executes tasks using {@link #runGlobalOrOmit(Plugin,
	 * Runnable)}.
	 * <p>
	 * Only suitable for tasks that don't touch any specific location/entity's state.
	 *
	 * @param plugin
	 *            the plugin
	 * @return the executor
	 */
	public static Executor createSyncExecutor(Plugin plugin) {
		return (runnable) -> runGlobalOrOmit(plugin, runnable);
	}

	/**
	 * Creates an {@link Executor} that executes tasks using
	 * {@link #runAsyncTaskOrOmit(Plugin, Runnable)}.
	 *
	 * @param plugin
	 *            the plugin
	 * @return the executor
	 */
	public static Executor createAsyncExecutor(Plugin plugin) {
		return (runnable) -> runAsyncTaskOrOmit(plugin, runnable);
	}

	private static void validatePluginTask(Plugin plugin, Runnable task) {
		Validate.notNull(plugin, "plugin is null");
		Validate.notNull(task, "task is null");
	}

	/**
	 * Checks if the current thread is the server's main/global thread.
	 * <p>
	 * This is only meaningful for coordinating-thread checks (e.g. during plugin
	 * enable/disable). It does NOT mean "safe to touch any entity/location" on Folia, since
	 * regions tick on their own separate threads.
	 *
	 * @return <code>true</code> if currently running on the main/global thread
	 */
	public static boolean isMainThread() {
		return Bukkit.isPrimaryThread();
	}

	/**
	 * Schedules the given task to run on the global region thread, for work that isn't tied to
	 * any specific location or entity (e.g. config/file I/O, cross-shopkeeper bookkeeping,
	 * admin command feedback to a non-player sender).
	 *
	 * @param plugin
	 *            the plugin to use for scheduling, not <code>null</code>
	 * @param task
	 *            the task, not <code>null</code>
	 * @return <code>true</code> if the task was successfully scheduled, <code>false</code>
	 *         otherwise
	 */
	public static boolean runGlobalOrOmit(Plugin plugin, Runnable task) {
		return (runGlobalLaterOrOmit(plugin, task, 1L) != null);
	}

	public static @Nullable ScheduledTask runGlobalLaterOrOmit(
			Plugin plugin,
			Runnable task,
			long delay
	) {
		validatePluginTask(plugin, task);
		// Tasks can only be registered while enabled:
		if (!plugin.isEnabled()) return null;
		// The global region scheduler requires a delay of at least 1 tick.
		long effectiveDelay = Math.max(1L, delay);
		return Bukkit.getGlobalRegionScheduler().runDelayed(
				plugin,
				scheduledTask -> task.run(),
				effectiveDelay
		);
	}

	/**
	 * Schedules the given task to run on the region thread that owns the given entity's current
	 * region, for work that mutates that entity's state (position, AI, equipment, etc.).
	 * <p>
	 * If the entity has meanwhile been removed/is otherwise no longer schedulable, the task is
	 * silently omitted (mirrors the old "omit if plugin disabled" behavior).
	 *
	 * @param plugin
	 *            the plugin to use for scheduling, not <code>null</code>
	 * @param entity
	 *            the entity whose region the task must run on, not <code>null</code>
	 * @param task
	 *            the task, not <code>null</code>
	 * @return <code>true</code> if the task was successfully scheduled, <code>false</code>
	 *         otherwise
	 */
	public static boolean runOnEntityOrOmit(Plugin plugin, Entity entity, Runnable task) {
		return (runOnEntityLaterOrOmit(plugin, entity, task, 0L) != null);
	}

	public static @Nullable ScheduledTask runOnEntityLaterOrOmit(
			Plugin plugin,
			Entity entity,
			Runnable task,
			long delay
	) {
		validatePluginTask(plugin, task);
		Validate.notNull(entity, "entity is null");
		if (!plugin.isEnabled()) return null;
		return entity.getScheduler().runDelayed(
				plugin,
				scheduledTask -> task.run(),
				null, // No action needed if the entity gets removed/retired before running.
				Math.max(1L, delay)
		);
	}

	/**
	 * Schedules the given task to run on the region thread that owns the given location, for
	 * work that mutates block/world state at that location (e.g. spawning/despawning a
	 * shopkeeper's shop object).
	 *
	 * @param plugin
	 *            the plugin to use for scheduling, not <code>null</code>
	 * @param location
	 *            the location whose region the task must run on, not <code>null</code>
	 * @param task
	 *            the task, not <code>null</code>
	 * @return <code>true</code> if the task was successfully scheduled, <code>false</code>
	 *         otherwise
	 */
	public static boolean runOnLocationOrOmit(Plugin plugin, Location location, Runnable task) {
		return (runOnLocationLaterOrOmit(plugin, location, task, 1L) != null);
	}

	public static @Nullable ScheduledTask runOnLocationLaterOrOmit(
			Plugin plugin,
			Location location,
			Runnable task,
			long delay
	) {
		validatePluginTask(plugin, task);
		Validate.notNull(location, "location is null");
		if (!plugin.isEnabled()) return null;
		long effectiveDelay = Math.max(1L, delay);
		return Bukkit.getRegionScheduler().runDelayed(
				plugin,
				location,
				scheduledTask -> task.run(),
				effectiveDelay
		);
	}

	/**
	 * Schedules the given task to run on the region thread that owns the given chunk.
	 *
	 * @param plugin
	 *            the plugin to use for scheduling, not <code>null</code>
	 * @param world
	 *            the chunk's world, not <code>null</code>
	 * @param chunkX
	 *            the chunk's x coordinate
	 * @param chunkZ
	 *            the chunk's z coordinate
	 * @param task
	 *            the task, not <code>null</code>
	 * @param delay
	 *            the delay in ticks
	 * @return the scheduled task, or <code>null</code> if it could not be scheduled
	 */
	public static @Nullable ScheduledTask runOnChunkLaterOrOmit(
			Plugin plugin,
			World world,
			int chunkX,
			int chunkZ,
			Runnable task,
			long delay
	) {
		validatePluginTask(plugin, task);
		Validate.notNull(world, "world is null");
		if (!plugin.isEnabled()) return null;
		long effectiveDelay = Math.max(1L, delay);
		return Bukkit.getRegionScheduler().runDelayed(
				plugin,
				world,
				chunkX,
				chunkZ,
				scheduledTask -> task.run(),
				effectiveDelay
		);
	}

	public static @Nullable ScheduledTask runAsyncTaskOrOmit(Plugin plugin, Runnable task) {
		return runAsyncTaskLaterOrOmit(plugin, task, 0L);
	}

	public static @Nullable ScheduledTask runAsyncTaskLaterOrOmit(
			Plugin plugin,
			Runnable task,
			long delay
	) {
		validatePluginTask(plugin, task);
		// Tasks can only be registered while enabled:
		if (!plugin.isEnabled()) return null;
		if (delay <= 0L) {
			return Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
		} else {
			// AsyncScheduler works in real time, not ticks: 1 tick = 50ms.
			return Bukkit.getAsyncScheduler().runDelayed(
					plugin,
					scheduledTask -> task.run(),
					delay * 50L,
					TimeUnit.MILLISECONDS
			);
		}
	}

	/**
	 * Awaits the completion of async tasks of the specified plugin.
	 * <p>
	 * If a logger is specified, it will be used to print informational messages suited to the
	 * context of this method being called during disabling of the plugin.
	 * <p>
	 * SSG fork note: Paper/Folia's {@link io.papermc.paper.threadedregions.scheduler.AsyncScheduler}
	 * does not expose a way to enumerate a plugin's active async tasks (unlike the legacy
	 * {@link org.bukkit.scheduler.BukkitScheduler#getActiveWorkers()}), so this can no longer
	 * count remaining tasks. It now simply cancels all of this plugin's scheduled-but-not-yet-run
	 * async tasks and returns immediately; already-running tasks are left to finish on their own
	 * (the JVM won't exit before daemon-less threads complete, and the scheduler stops routing
	 * new work to a disabled plugin regardless).
	 *
	 * @param plugin
	 *            the plugin
	 * @param asyncTasksTimeoutSeconds
	 *            unused, kept for source compatibility with callers
	 * @param logger
	 *            the logger used for printing informational messages, can be <code>null</code>
	 * @return always <code>0</code> (kept for source compatibility with callers)
	 */
	public static int awaitAsyncTasksCompletion(
			Plugin plugin,
			int asyncTasksTimeoutSeconds,
			@Nullable Logger logger
	) {
		Validate.notNull(plugin, "plugin is null");
		Validate.isTrue(asyncTasksTimeoutSeconds >= 0, "asyncTasksTimeoutSeconds cannot be negative");
		Bukkit.getAsyncScheduler().cancelTasks(plugin);
		return 0;
	}

	private SchedulerUtils() {
	}
}
