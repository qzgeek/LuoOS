package heos.folia.utils;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Resource world manager using Worlds plugin API (via reflection for soft-dependency).
 * Creates overworld + nether + end, auto-refreshes on schedule.
 * Refresh state persists across server restarts via config.
 */
public class ResourceWorldManager implements AutoCloseable {
    private final Plugin plugin;
    private final Logger logger;
    private final Random random = new Random();
    private static final String PREFIX = "res_";
    private static final String MAIN_KEY = "res_world";
    private static final String NETHER_KEY = "res_nether";
    private static final String END_KEY = "res_end";
    private static final String NAMESPACE = "luoos_resource";
    private static final String CFG_SEED = "resourceWorld.currentSeed";
    private static final String CFG_NEXT = "resourceWorld.nextRefreshTime";

    private volatile boolean refreshing = false;
    private volatile World resourceWorld;     // Owned by LuoOS — direct reference
    private volatile World resourceNether;
    private volatile World resourceEnd;

    // Cached reflection handles
    private Object worldsPlugin;
    private java.lang.reflect.Method createMethod;
    private java.lang.reflect.Method builderMethod;
    private java.lang.reflect.Method dimMethod;
    private java.lang.reflect.Method seedMethod;
    private java.lang.reflect.Method buildMethod;
    private java.lang.reflect.Method loadMethod;
    private Object owDim, nDim, eDim;

    private volatile boolean loadingExisting = false;

    public ResourceWorldManager(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    private boolean hasWorlds() {
        return Bukkit.getPluginManager().getPlugin("Worlds") != null;
    }

    public String currentSeed() {
        return plugin.getConfig().getString(CFG_SEED, null);
    }

    /** Millis timestamp of next scheduled refresh (from config). */
    public long getNextRefreshTime() {
        return plugin.getConfig().getLong(CFG_NEXT, 0);
    }
    // ========== Teleport ==========

    /** Apply default gamerules to resource worlds (keepInventory, etc.). */
    private void applyResourceGamerules(World world) {
        if (world == null) return;
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            world.setGameRule(GameRule.KEEP_INVENTORY, true);
            logger.fine("[ResourceWorld] Gamerules applied to " + world.getName());
        });
    }

    // ========== Teleport ==========

    public boolean teleportToResource(Player player) {
        World world = resourceWorld;
        if (world == null) {
            // Attempt fallback resolution (survives race between start() and first teleport)
            for (World w : Bukkit.getWorlds()) {
                if (MAIN_KEY.equals(w.getName())) {
                    resourceWorld = w;
                    world = w;
                    break;
                }
            }
        }
        if (world == null) {
            player.sendMessage(ChatColor.RED + "资源世界尚未就绪，请稍后再试。");
            return false;
        }
        Location spawn = world.getSpawnLocation();
        player.teleportAsync(spawn);
        player.sendMessage(ChatColor.GREEN + "已传送到资源世界！");
        return true;
    }

    // ========== Create (async) ==========

    /** Start async creation of resource worlds. Returns future that completes when done. */
    public void createResourceWorldsAsync(Runnable onDone) {
        if (!hasWorlds()) {
            logger.warning("[ResourceWorld] Worlds plugin not found");
            return;
        }
        if (!initReflection()) return;

        long seed = random.nextLong();
        final String seedStr = String.valueOf(Math.abs(seed));

        boolean nether = FoliaConfig.getBoolean(plugin, "resourceWorld.nether", true);
        boolean end = FoliaConfig.getBoolean(plugin, "resourceWorld.end", true);

        // Delete old worlds first (async)
        deleteOldAsync().thenRun(() -> {
            // Persist the new seed only after deletion has completed. Older
            // code cleared this key inside deleteOldAsync(), so every restart
            // saw a missing seed and generated a fresh resource world.
            plugin.getConfig().set(CFG_SEED, seedStr);
            plugin.saveConfig();
            logger.info("[ResourceWorld] Creating with seed " + seedStr);

            // Create overworld → save reference
            createOneWorld(MAIN_KEY, owDim, seed)
                .thenAccept(w -> {
                    resourceWorld = w;
                    applyResourceGamerules(w);
                    CompletableFuture<World> netherFuture = nether
                        ? createOneWorld(NETHER_KEY, nDim, seed) : CompletableFuture.completedFuture(null);
                    netherFuture.thenAccept(nw -> {
                        resourceNether = nw;
                        if (nw != null) applyResourceGamerules(nw);
                        CompletableFuture<World> endFuture = end
                            ? createOneWorld(END_KEY, eDim, seed) : CompletableFuture.completedFuture(null);
                        endFuture.thenAccept(ew -> {
                            resourceEnd = ew;
                            if (ew != null) applyResourceGamerules(ew);
                            logger.info("[ResourceWorld] Worlds ready (seed: " + seedStr + ")");
                            if (onDone != null) onDone.run();
                        });
                    });
                })
                .exceptionally(e -> {
                    logger.severe("[ResourceWorld] Create failed: " + e.getMessage());
                    return null;
                });
        });
    }

    /**
     * Synchronous create for non-async callers (blocks: use only from async threads).
     * Prefer createResourceWorldsAsync().
     */
    public void createResourceWorlds() {
        if (!hasWorlds()) {
            logger.warning("[ResourceWorld] Worlds plugin not found");
            return;
        }
        if (!initReflection()) return;

        long seed = random.nextLong();
        String seedStr = String.valueOf(Math.abs(seed));

        boolean nether = FoliaConfig.getBoolean(plugin, "resourceWorld.nether", true);
        boolean end = FoliaConfig.getBoolean(plugin, "resourceWorld.end", true);

        deleteOldResourceWorlds();

        plugin.getConfig().set(CFG_SEED, seedStr);
        plugin.saveConfig();

        logger.info("[ResourceWorld] Creating with seed " + seedStr);
        try {
            createOneWorld(MAIN_KEY, owDim, seed).get(60, TimeUnit.SECONDS);
            if (nether) createOneWorld(NETHER_KEY, nDim, seed).get(60, TimeUnit.SECONDS);
            if (end) createOneWorld(END_KEY, eDim, seed).get(60, TimeUnit.SECONDS);
            logger.info("[ResourceWorld] Worlds ready (seed: " + seedStr + ")");
        } catch (Exception e) {
            logger.severe("[ResourceWorld] Failed: " + e.getMessage());
        }
    }

    // ========== Start & Schedule ==========

    public void start() {
        if (!hasWorlds()) {
            logger.warning("[ResourceWorld] Worlds plugin not found");
            return;
        }

        String seed = currentSeed();
        if (!worldExists()) {
            // No existing worlds — create fresh, then schedule
            createResourceWorldsAsync(() -> {
                updateNextRefresh();
                plugin.saveConfig();
                scheduleFixedDate();
            });
            return;
        }

        // Worlds exist on disk. On restart they may not be registered yet,
        // so do not treat a first failed scan as data loss.
        resolveExistingWorlds();
        recoverSeedFromLoadedWorld();
        scheduleFixedDate();
        // Worlds may finish registering after LuoOS.onEnable(). Retry several
        // times, but never recreate merely because the first lookup is early.
        for (long delay : new long[] {5, 15, 30}) {
            Bukkit.getAsyncScheduler().runDelayed(plugin, task -> {
                resolveExistingWorlds();
                recoverSeedFromLoadedWorld();
            }, delay, TimeUnit.SECONDS);
        }
    }

    /**
     * Resolve existing resource worlds from Bukkit after a restart.
     * After onEnable(), resourceWorld/resourceNether/resourceEnd are null
     * even though the worlds may already be loaded. This only rebinds the
     * in-memory references; it never recreates worlds by itself.
     */
    private void resolveExistingWorlds() {
        World main = findLoadedWorld(MAIN_KEY);
        World nether = findLoadedWorld(NETHER_KEY);
        World end = findLoadedWorld(END_KEY);
        // Bukkit lookup is unreliable for custom namespaces on Folia. Never
        // overwrite a valid direct reference with null during a later retry.
        if (main != null) resourceWorld = main;
        if (nether != null) resourceNether = nether;
        if (end != null) resourceEnd = end;

        if (resourceWorld != null) {
            logger.info("[ResourceWorld] Resolved existing worlds (seed: " + currentSeed() + ")");
        } else {
            logger.warning("[ResourceWorld] Resource world not registered yet; requesting Worlds load");
            loadExistingWorlds();
        }
    }

    /** Load the persisted Worlds entries after a restart, without regeneration. */
    @SuppressWarnings("unchecked")
    private void loadExistingWorlds() {
        if (loadingExisting || !initReflection()) return;
        loadingExisting = true;
        try {
            Class<?> keyClass = Class.forName("net.kyori.adventure.key.Key");
            java.lang.reflect.Method keyMethod = keyClass.getMethod("key", String.class, String.class);
            Object registry = worldsPlugin.getClass().getMethod("getWorldRegistry").invoke(worldsPlugin);
            Object modernRegistry = worldsPlugin.getClass().getMethod("modernWorldRegistry").invoke(worldsPlugin);
            java.lang.reflect.Method readMethod = modernRegistry.getClass().getMethod("read", java.nio.file.Path.class);
            java.lang.reflect.Method registerMethod = registry.getClass().getMethod("register",
                    keyClass, Class.forName("net.thenextlvl.worlds.Dimension"), boolean.class,
                    Class.forName("net.thenextlvl.worlds.generator.Generator"));
            java.lang.reflect.Method dataKeyMethod = Class.forName(
                    "net.thenextlvl.worlds.ModernWorldRegistry$ModernWorldData").getMethod("key");
            List<String> keys = new ArrayList<>();
            if (resourceWorld == null && diskWorldExists(MAIN_KEY)) keys.add(MAIN_KEY);
            if (resourceNether == null && diskWorldExists(NETHER_KEY)) keys.add(NETHER_KEY);
            if (resourceEnd == null && diskWorldExists(END_KEY)) keys.add(END_KEY);

            List<CompletableFuture<World>> futures = new ArrayList<>();
            for (String keyName : keys) {
                java.nio.file.Path worldPath = new java.io.File(Bukkit.getWorldContainer(),
                        "world/dimensions/" + NAMESPACE + "/" + keyName).toPath();
                Object importedResult = readMethod.invoke(modernRegistry, worldPath);
                Object importedData = importedResult instanceof java.util.Optional<?> optional
                        ? optional.orElse(null) : importedResult;
                Object key = importedData != null
                        ? dataKeyMethod.invoke(importedData)
                        : keyMethod.invoke(null, NAMESPACE, keyName);
                Object dimension = MAIN_KEY.equals(keyName) ? owDim
                        : (NETHER_KEY.equals(keyName) ? nDim : eDim);
                if (importedData != null) {
                    try {
                        registerMethod.invoke(registry, key, dimension, true, null);
                        logger.info("[ResourceWorld] Imported existing world into Worlds: " + key);
                    } catch (Exception e) {
                        // It may already be registered; load() below is still valid.
                        logger.fine("[ResourceWorld] World already registered: " + key);
                    }
                }
                futures.add((CompletableFuture<World>) loadMethod.invoke(worldsPlugin, key));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenRun(() -> {
                        for (CompletableFuture<World> future : futures) {
                            try {
                                World world = future.join();
                                if (world == null) continue;
                                if (MAIN_KEY.equals(world.getName())) resourceWorld = world;
                                else if (NETHER_KEY.equals(world.getName())) resourceNether = world;
                                else if (END_KEY.equals(world.getName())) resourceEnd = world;
                                applyResourceGamerules(world);
                            } catch (Exception e) {
                                logger.warning("[ResourceWorld] Existing world load failed: " + e.getMessage());
                            }
                        }
                        recoverSeedFromLoadedWorld();
                        logger.info("[ResourceWorld] Existing worlds loaded without regeneration");
                        loadingExisting = false;
                    })
                    .exceptionally(e -> {
                        logger.warning("[ResourceWorld] Existing world load failed: " + e.getMessage());
                        loadingExisting = false;
                        return null;
                    });
        } catch (Exception e) {
            loadingExisting = false;
            logger.warning("[ResourceWorld] Existing world load request failed: " + e.getMessage());
        }
    }

    private boolean diskWorldExists(String key) {
        java.io.File dir = new java.io.File(Bukkit.getWorldContainer(),
                "world/dimensions/" + NAMESPACE + "/" + key);
        return dir.isDirectory();
    }

    /** Recover the seed lost by older versions from the loaded world itself. */
    private void recoverSeedFromLoadedWorld() {
        World world = resourceWorld;
        if (world == null || currentSeed() != null) return;
        String seed = String.valueOf(Math.abs(world.getSeed()));
        plugin.getConfig().set(CFG_SEED, seed);
        plugin.saveConfig();
        logger.info("[ResourceWorld] Recovered missing seed from existing world: " + seed);
    }

    private World findLoadedWorld(String name) {
        World world = Bukkit.getWorld(name);
        if (world != null) return world;
        String namespaced = NAMESPACE + ":" + name;
        for (World w : Bukkit.getWorlds()) {
            if (name.equals(w.getName()) || namespaced.equals(String.valueOf(w.getKey()))
                    || name.equals(w.getKey().getKey())) return w;
        }
        return null;
    }

    /**
     * Calculate the next monthly scheduled refresh time from config.
     * Config keys:
     *   resourceWorld.refreshDayOfMonth — day of month (default 25)
     *   resourceWorld.refreshHour       — hour of day (default 8, local timezone)
     */
    private long computeNextRefresh() {
        int dayOfMonth = FoliaConfig.getInt(plugin, "resourceWorld.refreshDayOfMonth", 25);
        int hour = FoliaConfig.getInt(plugin, "resourceWorld.refreshHour", 8);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        cal.set(java.util.Calendar.DAY_OF_MONTH, Math.min(dayOfMonth, cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)));
        long candidate = cal.getTimeInMillis();
        if (candidate <= System.currentTimeMillis()) {
            // Already passed this month — advance to next month
            cal.add(java.util.Calendar.MONTH, 1);
            cal.set(java.util.Calendar.DAY_OF_MONTH, Math.min(dayOfMonth, cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)));
            candidate = cal.getTimeInMillis();
        }
        return candidate;
    }

    private void updateNextRefresh() {
        long next = computeNextRefresh();
        plugin.getConfig().set(CFG_NEXT, next);
        plugin.saveConfig();
        logger.info("[ResourceWorld] Next refresh set to " + new java.util.Date(next));
    }

    private void scheduleFixedDate() {
        long stored = plugin.getConfig().getLong(CFG_NEXT, 0);
        long now = System.currentTimeMillis();
        // If stored is missing or stale (more than 35 days ago), recompute
        if (stored <= 0 || (now - stored) > 35L * 24 * 60 * 60 * 1000) {
            stored = computeNextRefresh();
            plugin.getConfig().set(CFG_NEXT, stored);
            plugin.saveConfig();
        }
        // If we missed the refresh time, execute now
        if (now >= stored) {
            logger.info("[ResourceWorld] Missed scheduled refresh — executing now");
            refreshResourceWorlds();
            updateNextRefresh();
            stored = plugin.getConfig().getLong(CFG_NEXT, 0);
        }
        // Poll every 60 seconds
        final long finalStored = stored;
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> {
            long cur = System.currentTimeMillis();
            long nxt = plugin.getConfig().getLong(CFG_NEXT, 0);
            if (nxt > 0 && cur >= nxt) {
                Bukkit.getGlobalRegionScheduler().run(plugin, t2 -> refreshResourceWorlds());
                updateNextRefresh();
            }
        }, 60, 60, TimeUnit.SECONDS);
        logger.info("[ResourceWorld] Schedule active: next refresh at " + new java.util.Date(stored));
    }

    private boolean worldExists() {
        if (resourceWorld != null) return true;
        // Check if world files exist on disk (survives restarts).
        // Do not require currentSeed: older versions could erase that key
        // while leaving a perfectly valid resource world on disk.
        java.io.File dimsDir = new java.io.File(Bukkit.getWorldContainer(), "world/dimensions/" + NAMESPACE);
        if (!dimsDir.isDirectory()) return false;
        java.io.File mainDir = new java.io.File(dimsDir, MAIN_KEY);
        return new java.io.File(mainDir, "level.dat").isFile()
                || new java.io.File(mainDir, "region").isDirectory();
    }

    private void scheduleRefresh(int intervalMinutes) {
        long now = System.currentTimeMillis();
        long intervalMs = (long) intervalMinutes * 60 * 1000;
        long nextRefresh = plugin.getConfig().getLong(CFG_NEXT, 0);
        if (nextRefresh <= 0) {
            nextRefresh = now + intervalMs;
            plugin.getConfig().set(CFG_NEXT, nextRefresh);
            plugin.saveConfig();
        }
        // Only fire missed refresh if the gap is < 2x interval (not a stale timestamp from before config reset)
        if (nextRefresh <= now && (now - nextRefresh) < intervalMs * 2) {
            logger.info("[ResourceWorld] Missed refresh — executing now");
            refreshResourceWorlds();
        } else if (nextRefresh <= now) {
            // Very stale — just reset the timer, don't blast
            nextRefresh = now + intervalMs;
            plugin.getConfig().set(CFG_NEXT, nextRefresh);
            plugin.saveConfig();
            logger.info("[ResourceWorld] Timer reset (config was changed).");
        }
        long checkInterval = Math.min(60, intervalMinutes) * 60L;
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> {
            long cur = System.currentTimeMillis();
            long nxt = plugin.getConfig().getLong(CFG_NEXT, 0);
            if (nxt > 0 && cur >= nxt) {
                Bukkit.getGlobalRegionScheduler().run(plugin, t2 -> refreshResourceWorlds());
            }
        }, checkInterval, checkInterval, TimeUnit.SECONDS);
        logger.info("[ResourceWorld] Schedule active: next refresh at " + new java.util.Date(nextRefresh));
    }

    // ========== Refresh ==========

    public void refreshResourceWorlds() {
        if (refreshing) { logger.warning("[ResourceWorld] Already refreshing"); return; }
        refreshing = true;

        broadcastRefresh("§e[资源世界] 资源世界将在 §c30秒 §e后刷新，请尽快离开！");

        Bukkit.getAsyncScheduler().runDelayed(plugin, task -> {
            Bukkit.getGlobalRegionScheduler().run(plugin, t2 -> {
                broadcastRefresh("§c[资源世界] 正在刷新资源世界...");
                // Delete old + create new (async chain)
                deleteOldAsync().thenRun(() -> {
                    long seed = random.nextLong();
                    final String seedStr = String.valueOf(Math.abs(seed));
                    plugin.getConfig().set(CFG_SEED, seedStr);
                    plugin.saveConfig();

                    boolean nether = FoliaConfig.getBoolean(plugin, "resourceWorld.nether", true);
                    boolean end = FoliaConfig.getBoolean(plugin, "resourceWorld.end", true);
                    logger.info("[ResourceWorld] Refreshing (seed: " + seedStr + ")");

                    createOneWorld(MAIN_KEY, owDim, seed)
                        .thenAccept(ow -> {
                            resourceWorld = ow;
                            applyResourceGamerules(ow);
                            (nether ? createOneWorld(NETHER_KEY, nDim, seed) : CompletableFuture.<World>completedFuture(null))
                                .thenAccept(nw -> {
                                    resourceNether = nw;
                                    if (nw != null) applyResourceGamerules(nw);
                                    (end ? createOneWorld(END_KEY, eDim, seed) : CompletableFuture.<World>completedFuture(null))
                                        .thenAccept(ew -> {
                                            resourceEnd = ew;
                                            if (ew != null) applyResourceGamerules(ew);
                                            Bukkit.getGlobalRegionScheduler().run(plugin, t3 -> {
                                                broadcastRefresh("§a[资源世界] 资源世界刷新完毕！使用 /los resource 进入。");
                                                updateNextRefresh();
                                                logger.info("[ResourceWorld] Next refresh: " + new java.util.Date(plugin.getConfig().getLong(CFG_NEXT, 0)));
                                                refreshing = false;
                                            });
                                        });
                                });
                        })
                        .exceptionally(e -> {
                            logger.severe("[ResourceWorld] Refresh create failed: " + e.getMessage());
                            refreshing = false;
                            return null;
                        });
                });
            });
        }, 30, TimeUnit.SECONDS);
    }

    // ========== Reflection init ==========

    private boolean initReflection() {
        if (createMethod != null) return true;
        try {
            worldsPlugin = Bukkit.getPluginManager().getPlugin("Worlds");
            if (worldsPlugin == null) return false;

            Class<?> levelClass = Class.forName("net.thenextlvl.worlds.Level");
            Class<?> dimensionClass = Class.forName("net.thenextlvl.worlds.Dimension");
            Class<?> worldsAccessClass = Class.forName("net.thenextlvl.worlds.WorldsAccess");

            owDim = dimensionClass.getField("OVERWORLD").get(null);
            nDim = dimensionClass.getField("THE_NETHER").get(null);
            eDim = dimensionClass.getField("THE_END").get(null);

            builderMethod = levelClass.getMethod("builder", Class.forName("net.kyori.adventure.key.Key"));
            dimMethod = builderMethod.getReturnType().getMethod("dimension", dimensionClass);
            seedMethod = builderMethod.getReturnType().getMethod("seed", Long.class);
            buildMethod = builderMethod.getReturnType().getMethod("build");
            createMethod = worldsAccessClass.getMethod("create", levelClass);
            loadMethod = worldsAccessClass.getMethod("load", Class.forName("net.kyori.adventure.key.Key"));

            return true;
        } catch (Exception e) {
            logger.severe("[ResourceWorld] Reflection init failed: " + e.getMessage());
            return false;
        }
    }

    /** Create a single world and return a future. */
    @SuppressWarnings("unchecked")
    private CompletableFuture<World> createOneWorld(String keyStr, Object dimension, long seed) {
        try {
            Object key = Class.forName("net.kyori.adventure.key.Key")
                    .getMethod("key", String.class, String.class)
                    .invoke(null, NAMESPACE, keyStr);
            Object builder = builderMethod.invoke(null, key);
            dimMethod.invoke(builder, dimension);
            seedMethod.invoke(builder, seed);
            Object level = buildMethod.invoke(builder);
            return (CompletableFuture<World>) createMethod.invoke(worldsPlugin, level);
        } catch (Exception e) {
            CompletableFuture<World> f = new CompletableFuture<>();
            f.completeExceptionally(e);
            return f;
        }
    }

    // ========== Delete ==========

    private void deleteOldResourceWorlds() {
        try {
            initReflection();
            Class<?> accessClass = Class.forName("net.thenextlvl.worlds.WorldsAccess");
            java.lang.reflect.Method unloadM = accessClass.getMethod("unload", World.class, boolean.class);
            java.lang.reflect.Method deleteM = accessClass.getMethod("delete", World.class);

            World mainWorld = Bukkit.getWorlds().get(0);
            for (World world : new ArrayList<>(Bukkit.getWorlds())) {
                if (world.getName().contains(PREFIX)) {
                    for (Player p : world.getPlayers()) p.teleportAsync(mainWorld.getSpawnLocation());
                    try { unloadM.invoke(worldsPlugin, world, false); deleteM.invoke(worldsPlugin, world); }
                    catch (Exception ignored) {}
                }
            }
            // Clean disk
            java.io.File dimsDir = new java.io.File(Bukkit.getWorldContainer(), "world/dimensions/" + NAMESPACE);
            if (dimsDir.isDirectory()) {
                java.io.File[] files = dimsDir.listFiles();
                if (files != null) for (java.io.File f : files) if (f.getName().contains(PREFIX)) deleteFolder(f);
            }
            resourceWorld = null;
            resourceNether = null;
            resourceEnd = null;
        } catch (Exception ignored) {}
    }

    private CompletableFuture<Void> deleteOldAsync() {
        CompletableFuture<Void> f = new CompletableFuture<>();
        try {
            initReflection();
            Class<?> accessClass = Class.forName("net.thenextlvl.worlds.WorldsAccess");
            java.lang.reflect.Method unloadM = accessClass.getMethod("unload", World.class, boolean.class);
            java.lang.reflect.Method deleteM = accessClass.getMethod("delete", World.class);

            World mainWorld = Bukkit.getWorlds().get(0);
            List<CompletableFuture<?>> ops = new ArrayList<>();
            for (World world : new ArrayList<>(Bukkit.getWorlds())) {
                if (world.getName().contains(PREFIX)) {
                    for (Player p : world.getPlayers()) p.teleportAsync(mainWorld.getSpawnLocation());
                    try {
                        ((CompletableFuture<?>) unloadM.invoke(worldsPlugin, world, false))
                            .thenCompose(ok -> {
                                try { return (CompletableFuture<?>) deleteM.invoke(worldsPlugin, world); }
                                catch (Exception e) { return CompletableFuture.completedFuture(null); }
                            });
                    } catch (Exception ignored) {}
                }
            }
            // Clean disk
            java.io.File dimsDir = new java.io.File(Bukkit.getWorldContainer(), "world/dimensions/" + NAMESPACE);
            if (dimsDir.isDirectory()) {
                java.io.File[] files = dimsDir.listFiles();
                if (files != null) for (java.io.File file : files) if (file.getName().contains(PREFIX)) deleteFolder(file);
            }
            resourceWorld = null;
            resourceNether = null;
            resourceEnd = null;
        } catch (Exception ignored) {}
        f.complete(null);
        return f;
    }

    private void deleteFolder(java.io.File folder) {
        if (!folder.exists()) return;
        java.io.File[] files = folder.listFiles();
        if (files != null) for (java.io.File f : files) {
            if (f.isDirectory()) deleteFolder(f); else f.delete();
        }
        folder.delete();
    }

    private void broadcastRefresh(String msg) {
        for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(msg);
    }

    @Override
    public void close() {}
}
