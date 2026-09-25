package net.seanomik.tamablefoxes.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Scheduling helpers that behave correctly on both the single threaded server (Spigot/Paper) and
 * Folia's regionised multithreaded server.
 *
 * Everything Folia specific is reached through reflection: this plugin also runs on servers whose
 * API predates {@code io.papermc.paper.threadedregions}.
 */
public final class FoliaCompat {

    private static final Consumer<Object> NO_RETIRE_HANDLER = task -> { };

    private static final boolean FOLIA = detectFolia();

    /** Paper 1.20+ ships the Folia schedulers and backs them with the main thread. */
    private static final boolean REGION_SCHEDULERS =
            classExists("io.papermc.paper.threadedregions.scheduler.EntityScheduler");

    private static final Method GET_ENTITY_SCHEDULER = lookup(Entity.class, "getScheduler");
    private static final Method ENTITY_SCHEDULER_RUN = lookup(schedulerClass("EntityScheduler"),
            "run", Plugin.class, Consumer.class, Runnable.class);
    private static final Method ENTITY_SCHEDULER_RUN_DELAYED = lookup(schedulerClass("EntityScheduler"),
            "runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class);
    private static final Method IS_OWNED_BY_CURRENT_REGION =
            lookup(Bukkit.class, "isOwnedByCurrentRegion", Entity.class);
    private static final Method IS_OWNED_BY_CURRENT_REGION_LOCATION =
            lookup(Bukkit.class, "isOwnedByCurrentRegion", Location.class);
    private static final Method TELEPORT_ASYNC = lookup(Entity.class, "teleportAsync", Location.class);

    private FoliaCompat() {
    }

    /** True when the server is Folia, where a world is ticked by many region threads at once. */
    public static boolean isFolia() {
        return FOLIA;
    }

    /**
     * Runs the task on the thread owned by the entity's region, immediately if the caller already
     * is that thread. On a single threaded server this defers to the next tick.
     */
    public static void runOnEntity(Entity entity, Runnable task) {
        if (!REGION_SCHEDULERS) {
            later(task, 1L);
            return;
        }

        scheduleOnEntity(entity, task, -1L);
    }

    /** Runs the task on the thread owned by the entity's region, after the given delay in ticks. */
    public static void runOnEntityLater(Entity entity, Runnable task, long delayTicks) {
        if (!REGION_SCHEDULERS) {
            later(task, delayTicks);
            return;
        }

        scheduleOnEntity(entity, task, delayTicks);
    }

    /**
     * True when the calling thread owns the region the entity is ticking in. Reading or writing an
     * entity from another region's thread is not allowed on Folia.
     */
    public static boolean isOwnedByCurrentRegion(Entity entity) {
        if (!REGION_SCHEDULERS) {
            return Bukkit.isPrimaryThread();
        }

        try {
            return (Boolean) IS_OWNED_BY_CURRENT_REGION.invoke(null, entity);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not check the region owning an entity", e);
        }
    }

    /**
     * True when the calling thread owns the region a location falls in. Use this to decide whether
     * something may be read from here without resolving the entity that stands there.
     */
    public static boolean isOwnedByCurrentRegion(Location location) {
        if (!REGION_SCHEDULERS) {
            return Bukkit.isPrimaryThread();
        }

        try {
            return (Boolean) IS_OWNED_BY_CURRENT_REGION_LOCATION.invoke(null, location);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not check the region owning a location", e);
        }
    }

    /**
     * Moves an entity to another location or region. Folia coordinates the move with the region
     * that owns the destination; writing the position directly would corrupt it.
     */
    public static void teleportAsync(Entity entity, Location to) {
        if (!REGION_SCHEDULERS) {
            entity.teleport(to);
            return;
        }

        try {
            TELEPORT_ASYNC.invoke(entity, to);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not teleport an entity across regions", e);
        }
    }

    private static void scheduleOnEntity(Entity entity, Runnable task, long delayTicks) {
        try {
            Object scheduler = GET_ENTITY_SCHEDULER.invoke(entity);
            if (delayTicks < 0L) {
                ENTITY_SCHEDULER_RUN.invoke(scheduler, Utils.tamableFoxesPlugin, NO_RETIRE_HANDLER, task);
            } else {
                ENTITY_SCHEDULER_RUN_DELAYED.invoke(scheduler, Utils.tamableFoxesPlugin, NO_RETIRE_HANDLER, task, delayTicks);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not schedule a task on an entity", e);
        }
    }

    private static void later(Runnable task, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(Utils.tamableFoxesPlugin, task, delayTicks);
    }

    private static boolean detectFolia() {
        // A Folia only class from the regioniser; Paper compiles the schedulers but not this.
        if (classExists("io.papermc.paper.threadedregions.RegionizedServer")) {
            return true;
        }

        try {
            Class<?> buildInfo = Class.forName("io.papermc.paper.ServerBuildInfo");
            Object info = buildInfo.getMethod("buildInfo").invoke(null);
            return "Folia".equalsIgnoreCase(String.valueOf(buildInfo.getMethod("brandName").invoke(info)));
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static Class<?> schedulerClass(String simpleName) {
        if (!REGION_SCHEDULERS) {
            return null;
        }

        try {
            return Class.forName("io.papermc.paper.threadedregions.scheduler." + simpleName);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Method lookup(Class<?> owner, String name, Class<?>... parameters) {
        if (owner == null) {
            return null;
        }

        try {
            return owner.getMethod(name, parameters);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
