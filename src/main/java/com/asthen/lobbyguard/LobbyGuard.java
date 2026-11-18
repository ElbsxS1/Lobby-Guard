package com.asthen.lobbyguard;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.ThreadLocalRandom;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Particle;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Snow;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * LobbyGuard: protege o mundo do lobby e leva os jogadores ao mundo de
 * sobrevivência através de um NPC (aldeão). Nenhuma regra daqui vale fora do lobby.
 */
public final class LobbyGuard extends JavaPlugin implements Listener, TabExecutor {

    private static final String BYPASS = "lobbyguard.bypass";
    private static final List<String> NEVE_SUBCOMMANDS = List.of("on", "off", "encher", "limpar", "status");
    private static final List<String> SUBCOMMANDS = List.of("setnpc", "removenpc", "setspawn", "reload");

    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, BukkitTask> returning = new HashMap<>();
    private final Map<UUID, Location> returnStart = new HashMap<>();
    private final java.util.Set<UUID> voidPending = new java.util.HashSet<>();

    private NamespacedKey npcKey;
    private File dataFile;
    private YamlConfiguration data;

    private String lobbyName;
    private String survivalName;
    private String lobbyType;
    private String survivalType;
    private String npcName;
    private String msgTeleporting;
    private String msgUnavailable;
    private int lobbyDelay;
    private File playersFile;
    private YamlConfiguration players;
    private boolean snowEnabled;
    private int snowDensity;
    private int snowRadius;
    private int snowHeight;
    private double snowSpeed;
    private int fillDefaultRadius;
    private int fillMaxRadius;
    private int columnsPerTick;
    private int randomRadius;
    private int poolSize;
    private boolean poolSearching;
    private final ArrayDeque<Location> spotPool = new ArrayDeque<>();
    private BukkitTask snowJob;
    private double lobbyMoveTolerance;
    private boolean createMissing;
    private boolean teleportOnJoin;
    private boolean nightVision;
    private boolean voidTeleport;
    private long fixedTime;
    private long clickCooldown;
    private Double voidY;

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        saveDefaultConfig();
        npcKey = new NamespacedKey(this, "lobby_npc");
        dataFile = new File(getDataFolder(), "data.yml");
        playersFile = new File(getDataFolder(), "players.yml");
        loadAll();

        Bukkit.getPluginManager().registerEvents(this, this);

        if (getCommand("lob") != null) {
            getCommand("lob").setExecutor((sender, cmd, label, args) -> lobCommand(sender));
        }
        if (getCommand("neve") != null) {
            getCommand("neve").setExecutor((snd, cm, lb, ar) -> neveCommand(snd, ar));
            getCommand("neve").setTabCompleter((snd, cm, lb, ar) -> {
                List<String> res = new ArrayList<>();
                if (ar.length == 1) {
                    for (String sc : NEVE_SUBCOMMANDS) {
                        if (sc.startsWith(ar[0].toLowerCase(Locale.ROOT))) {
                            res.add(sc);
                        }
                    }
                }
                return res;
            });
        }
        if (getCommand("lobbyguard") != null) {
            getCommand("lobbyguard").setExecutor(this);
            getCommand("lobbyguard").setTabCompleter(this);
        }

        Bukkit.getScheduler().runTaskTimer(this, this::tickLobby, 40L, 40L);
        Bukkit.getScheduler().runTaskTimer(this, this::ensureNpc, 100L, 100L);
        Bukkit.getScheduler().runTaskTimer(this, this::tickSnow, 20L, 2L);
        Bukkit.getScheduler().runTaskTimer(this, this::refillPool, 100L, 40L);
    }

    private void loadAll() {
        reloadConfig();
        FileConfiguration c = getConfig();
        lobbyName = c.getString("worlds.lobby", "lobby");
        survivalName = c.getString("worlds.survival", "survival");
        createMissing = c.getBoolean("worlds.create-if-missing", true);
        lobbyType = c.getString("worlds.lobby-type", "FLAT");
        survivalType = c.getString("worlds.survival-type", "NORMAL");

        teleportOnJoin = c.getBoolean("lobby.teleport-on-join", true);
        fixedTime = c.getLong("lobby.time", 6000L);
        nightVision = c.getBoolean("lobby.night-vision", true);
        voidTeleport = c.getBoolean("lobby.void-teleport", true);
        Object v = c.get("lobby.void-y");
        voidY = (v instanceof Number n) ? Double.valueOf(n.doubleValue()) : null;

        npcName = c.getString("npc.name", "&a&l▶ Sobrevivência");
        clickCooldown = c.getLong("npc.click-cooldown-ms", 1500L);
        msgTeleporting = c.getString("messages.teleporting", "&aVocê foi enviado ao mundo de sobrevivência!");
        lobbyDelay = Math.max(1, c.getInt("lob-command.delay-seconds", 5));
        lobbyMoveTolerance = Math.max(0.05, c.getDouble("lob-command.move-tolerance", 0.3));
        msgUnavailable = c.getString("messages.survival-unavailable",
                "&cO mundo de sobrevivência não está disponível agora.");

        data = YamlConfiguration.loadConfiguration(dataFile);
        players = YamlConfiguration.loadConfiguration(playersFile);
        snowEnabled = data.getBoolean("snow-enabled", false);
        snowDensity = Math.max(1, c.getInt("snow.density", 30));
        snowRadius = Math.max(4, c.getInt("snow.radius", 20));
        snowHeight = Math.max(4, c.getInt("snow.height", 12));
        snowSpeed = Math.max(0.05, c.getDouble("snow.fall-speed", 0.4));
        fillDefaultRadius = Math.max(1, c.getInt("snow.fill-default-radius", 60));
        fillMaxRadius = Math.max(fillDefaultRadius, c.getInt("snow.fill-max-radius", 150));
        columnsPerTick = Math.max(50, c.getInt("snow.columns-per-tick", 500));
        randomRadius = Math.max(100, c.getInt("survival.random-radius", 3000));
        poolSize = Math.max(0, c.getInt("survival.pool-size", 5));

        if (lobbyName.equalsIgnoreCase(survivalName)) {
            getLogger().warning("O mundo do lobby e o de sobrevivência têm o mesmo nome. Corrija o config.yml.");
        }
        ensureWorld(lobbyName, lobbyType);
        ensureWorld(survivalName, survivalType);
    }

    private void ensureWorld(String name, String type) {
        if (Bukkit.getWorld(name) != null || !createMissing) {
            return;
        }
        WorldCreator creator = new WorldCreator(name);
        try {
            creator.type(WorldType.valueOf(type.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Tipo de mundo inválido '" + type + "' para '" + name + "'. Usando NORMAL.");
        }
        World created = creator.createWorld();
        if (created == null) {
            getLogger().severe("Não foi possível criar/carregar o mundo '" + name + "'.");
        } else {
            getLogger().info("Mundo '" + name + "' carregado.");
        }
    }

    // ------------------------------------------------------------------
    // Utilitários
    // ------------------------------------------------------------------

    private static Component c(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    private boolean isLobby(World w) {
        return w != null && w.getName().equalsIgnoreCase(lobbyName);
    }

    private boolean isNpc(Entity e) {
        return e != null && e.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE);
    }

    /** True quando a ação deve ser bloqueada: está no lobby e o jogador não tem bypass. */
    private boolean blocked(Player p, World w) {
        return isLobby(w) && (p == null || !p.hasPermission(BYPASS));
    }

    private double voidLimit(World w) {
        return voidY != null ? voidY : w.getMinHeight();
    }

    private Location lobbySpawn() {
        World w = Bukkit.getWorld(lobbyName);
        if (w == null) {
            return null;
        }
        if (data.contains("spawn.x") && lobbyName.equalsIgnoreCase(data.getString("spawn.world", ""))) {
            return new Location(w,
                    data.getDouble("spawn.x"), data.getDouble("spawn.y"), data.getDouble("spawn.z"),
                    (float) data.getDouble("spawn.yaw"), (float) data.getDouble("spawn.pitch"));
        }
        return w.getSpawnLocation().add(0.5, 0, 0.5);
    }

    private Location npcLocation() {
        if (!data.contains("npc.world")) {
            return null;
        }
        World w = Bukkit.getWorld(data.getString("npc.world", ""));
        if (w == null) {
            return null;
        }
        return new Location(w,
                data.getDouble("npc.x"), data.getDouble("npc.y"), data.getDouble("npc.z"),
                (float) data.getDouble("npc.yaw"), (float) data.getDouble("npc.pitch"));
    }

    private boolean isSurvival(World w) {
        return w != null && w.getName().equalsIgnoreCase(survivalName);
    }

    // ------------------------------------------------------------------
    // Teleporte aleatório na sobrevivência
    // ------------------------------------------------------------------

    /** Sorteia x/z dentro do raio configurado, respeitando a borda do mundo. */
    private int[] randomXZ(World w) {
        WorldBorder b = w.getWorldBorder();
        Location center = b.getCenter();
        double half = Math.max(16.0, Math.min(randomRadius, b.getSize() / 2.0 - 16.0));
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int x = (int) Math.floor(center.getX() + (rnd.nextDouble() * 2 - 1) * half);
        int z = (int) Math.floor(center.getZ() + (rnd.nextDouble() * 2 - 1) * half);
        return new int[] {x, z};
    }

    /** Devolve um ponto seguro na coluna x/z (chão sólido, sem líquido, com 2 blocos de ar) ou null. */
    private Location validSpot(World w, int x, int z) {
        int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (y <= w.getMinHeight() + 1 || y + 3 >= w.getMaxHeight()) {
            return null;
        }
        Material ground = w.getBlockAt(x, y, z).getType();
        if (!ground.isSolid() || ground == Material.MAGMA_BLOCK || ground == Material.CACTUS
                || ground == Material.CAMPFIRE || ground == Material.SOUL_CAMPFIRE
                || ground == Material.SWEET_BERRY_BUSH) {
            return null;
        }
        if (!w.getBlockAt(x, y + 1, z).getType().isAir() || !w.getBlockAt(x, y + 2, z).getType().isAir()) {
            return null;
        }
        Location loc = new Location(w, x + 0.5, y + 1, z + 0.5,
                (float) ThreadLocalRandom.current().nextDouble(0, 360), 0f);
        return w.getWorldBorder().isInside(loc) ? loc : null;
    }

    /** Procura um ponto aleatório seguro carregando os chunks sem travar o servidor. */
    private void findRandomSpot(World w, int attemptsLeft, Consumer<Location> done) {
        if (attemptsLeft <= 0) {
            done.accept(null);
            return;
        }
        int[] xz = randomXZ(w);
        w.getChunkAtAsync(xz[0] >> 4, xz[1] >> 4, true).whenComplete((chunk, ex) ->
                Bukkit.getScheduler().runTask(this, () -> {
                    Location spot = ex == null ? validSpot(w, xz[0], xz[1]) : null;
                    if (spot != null) {
                        done.accept(spot);
                    } else {
                        findRandomSpot(w, attemptsLeft - 1, done);
                    }
                }));
    }

    /** Mantém alguns pontos seguros já prontos para a entrada e a morte serem instantâneas. */
    private void refillPool() {
        if (poolSize <= 0 || poolSearching || spotPool.size() >= poolSize) {
            return;
        }
        World w = Bukkit.getWorld(survivalName);
        if (w == null) {
            return;
        }
        poolSearching = true;
        findRandomSpot(w, 15, loc -> {
            poolSearching = false;
            if (loc != null) {
                spotPool.add(loc);
            }
        });
    }

    /** Fallback síncrono (usado na morte quando o pool está vazio). */
    private Location syncRandomSpot(World w, int attempts) {
        for (int i = 0; i < attempts; i++) {
            int[] xz = randomXZ(w);
            Location spot = validSpot(w, xz[0], xz[1]);
            if (spot != null) {
                return spot;
            }
        }
        return w.getSpawnLocation().add(0.5, 0, 0.5);
    }

    private void sendToRandomSpot(Player p, World w) {
        Location spot = spotPool.poll();
        if (spot != null) {
            p.teleport(spot);
            p.sendMessage(c(msgTeleporting));
            return;
        }
        p.sendMessage(c(msg("random-searching", "&eProcurando um lugar seguro...")));
        findRandomSpot(w, 20, loc -> {
            if (!p.isOnline()) {
                return;
            }
            p.teleport(loc != null ? loc : w.getSpawnLocation().add(0.5, 0, 0.5));
            p.sendMessage(c(msgTeleporting));
        });
    }

    private void savePlayers() {
        try {
            players.save(playersFile);
        } catch (IOException ex) {
            getLogger().severe("Falha ao salvar players.yml: " + ex.getMessage());
        }
    }

    /** Guarda onde o jogador estava na sobrevivência (base) para ele voltar ali pelo NPC. */
    private void saveSurvivalLocation(Player p, Location l) {
        if (l == null || !isSurvival(l.getWorld())) {
            return;
        }
        String k = p.getUniqueId().toString();
        players.set(k + ".x", l.getX());
        players.set(k + ".y", l.getY());
        players.set(k + ".z", l.getZ());
        players.set(k + ".yaw", (double) l.getYaw());
        players.set(k + ".pitch", (double) l.getPitch());
        savePlayers();
    }

    private Location savedSurvivalLocation(Player p) {
        World w = Bukkit.getWorld(survivalName);
        String k = p.getUniqueId().toString();
        if (w == null || !players.contains(k + ".x")) {
            return null;
        }
        return new Location(w, players.getDouble(k + ".x"), players.getDouble(k + ".y"),
                players.getDouble(k + ".z"), (float) players.getDouble(k + ".yaw"),
                (float) players.getDouble(k + ".pitch"));
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("Falha ao salvar data.yml: " + ex.getMessage());
        }
    }

    private void giveNightVision(Player p) {
        PotionEffect current = p.getPotionEffect(PotionEffectType.NIGHT_VISION);
        if (current != null && current.getDuration() == PotionEffect.INFINITE_DURATION) {
            return;
        }
        p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,
                PotionEffect.INFINITE_DURATION, 0, false, false, false));
    }

    private void removeLobbyNightVision(Player p) {
        PotionEffect current = p.getPotionEffect(PotionEffectType.NIGHT_VISION);
        if (current != null && current.getDuration() == PotionEffect.INFINITE_DURATION) {
            p.removePotionEffect(PotionEffectType.NIGHT_VISION);
        }
    }

    private void refreshNightVision(Player p) {
        if (isLobby(p.getWorld()) && nightVision) {
            giveNightVision(p);
        } else {
            removeLobbyNightVision(p);
        }
    }

    // ------------------------------------------------------------------
    // Tarefas periódicas
    // ------------------------------------------------------------------

    /** Mantém o lobby sempre de dia, sem chuva, e com visão noturna para quem está nele. */
    private void tickLobby() {
        World lobby = Bukkit.getWorld(lobbyName);
        if (lobby == null) {
            return;
        }
        lobby.setTime(fixedTime);
        if (lobby.hasStorm()) {
            lobby.setStorm(false);
        }
        if (lobby.isThundering()) {
            lobby.setThundering(false);
        }
        if (nightVision) {
            for (Player p : lobby.getPlayers()) {
                giveNightVision(p);
            }
        }
    }

    /** Recria o NPC se alguém o removeu (ex.: /kill) e apaga duplicatas. */
    private void ensureNpc() {
        Location loc = npcLocation();
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        World w = loc.getWorld();
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        if (!w.isChunkLoaded(cx, cz)) {
            return;
        }
        Chunk chunk = w.getChunkAt(cx, cz);
        if (!chunk.isEntitiesLoaded()) {
            return;
        }
        List<Entity> found = new ArrayList<>();
        for (Entity ent : chunk.getEntities()) {
            if (isNpc(ent)) {
                found.add(ent);
            }
        }
        if (found.isEmpty()) {
            spawnNpc(loc);
            return;
        }
        for (int i = 1; i < found.size(); i++) {
            found.get(i).remove();
        }
        Entity npc = found.get(0);
        if (npc.getLocation().distanceSquared(loc) > 0.25) {
            npc.teleport(loc);
        }
    }

    private void spawnNpc(Location loc) {
        World w = loc.getWorld();
        if (w == null) {
            return;
        }
        w.spawn(loc, Villager.class, v -> {
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setPersistent(true);
            v.setRemoveWhenFarAway(false);
            v.setCollidable(false);
            v.customName(c(npcName));
            v.setCustomNameVisible(true);
            v.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private void removeAllNpcs() {
        for (World w : Bukkit.getWorlds()) {
            for (Villager v : w.getEntitiesByClass(Villager.class)) {
                if (isNpc(v)) {
                    v.remove();
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Jogadores: entrada, troca de mundo, void, respawn
    // ------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        refreshNightVision(p);
        if (teleportOnJoin) {
            Bukkit.getScheduler().runTask(this, () -> {
                if (!p.isOnline()) {
                    return;
                }
                Location spawn = lobbySpawn();
                if (spawn != null) {
                    p.teleport(spawn);
                }
                refreshNightVision(p);
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cooldowns.remove(e.getPlayer().getUniqueId());
        voidPending.remove(e.getPlayer().getUniqueId());
        saveSurvivalLocation(e.getPlayer(), e.getPlayer().getLocation());
        cancelReturn(e.getPlayer(), null);
    }

    /** Ao sair da sobrevivência (NPC, /lob, portal), lembra o ponto de onde ele saiu. */
    @EventHandler(ignoreCancelled = true)
    public void onTeleportOut(PlayerTeleportEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to != null && isSurvival(from.getWorld()) && to.getWorld() != from.getWorld()) {
            saveSurvivalLocation(e.getPlayer(), from);
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        refreshNightVision(e.getPlayer());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!voidTeleport) {
            return;
        }
        Location to = e.getTo();
        if (to == null || to.getWorld() == null || !isLobby(to.getWorld())) {
            return;
        }
        if (to.getY() >= voidLimit(to.getWorld())) {
            return;
        }
        Location spawn = lobbySpawn();
        if (spawn == null) {
            return;
        }
        Player p = e.getPlayer();
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR
                || p.getAllowFlight() || p.isFlying() || p.hasPermission(BYPASS)) {
            return;
        }
        if (!voidPending.add(p.getUniqueId())) {
            return;
        }
        // Teleporta no próximo tick, fora do evento de movimento.
        Bukkit.getScheduler().runTask(this, () -> {
            voidPending.remove(p.getUniqueId());
            if (!p.isOnline()) {
                return;
            }
            p.setFallDistance(0f);
            p.setVelocity(new Vector(0, 0, 0));
            p.teleport(spawn);
        });
    }

    /** Quem morre na sobrevivência sem cama renasce em um ponto aleatório seguro dela. */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (e.isBedSpawn() || e.isAnchorSpawn()) {
            return; // quem tem cama/base renasce nela
        }
        World survival = Bukkit.getWorld(survivalName);
        if (survival == null || !e.getPlayer().getWorld().equals(survival)) {
            return;
        }
        Location spot = spotPool.poll();
        if (spot == null) {
            spot = syncRandomSpot(survival, 8);
        }
        e.setRespawnLocation(spot);
    }

    // ------------------------------------------------------------------
    // Proteções (somente no lobby)
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (blocked(e.getPlayer(), e.getBlock().getWorld())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (blocked(e.getPlayer(), e.getBlock().getWorld())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (blocked(e.getPlayer(), e.getBlock().getWorld())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (blocked(e.getPlayer(), e.getBlock().getWorld())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (blocked(e.getPlayer(), e.getPlayer().getWorld())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent e) {
        if (!isLobby(e.getEntity().getWorld())) {
            return;
        }
        if (e instanceof HangingBreakByEntityEvent byEntity
                && byEntity.getRemover() instanceof Player p
                && p.hasPermission(BYPASS)) {
            return;
        }
        e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        if (blocked(e.getPlayer(), e.getEntity().getWorld())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTrample(PlayerInteractEvent e) {
        if (e.getAction() != Action.PHYSICAL || e.getClickedBlock() == null) {
            return;
        }
        if (e.getClickedBlock().getType() == Material.FARMLAND
                && blocked(e.getPlayer(), e.getClickedBlock().getWorld())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (!isLobby(e.getBlock().getWorld())) {
            return;
        }
        Player p = e.getPlayer();
        if (p != null && p.hasPermission(BYPASS)) {
            return;
        }
        e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (isLobby(e.getBlock().getWorld())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent e) {
        if (isLobby(e.getBlock().getWorld())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (isLobby(e.getLocation().getWorld())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (isLobby(e.getBlock().getWorld())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (!isLobby(e.getBlock().getWorld())) {
            return;
        }
        if (e.getEntity() instanceof Player p && p.hasPermission(BYPASS)) {
            return;
        }
        e.setCancelled(true);
    }

    /** Sem dano no lobby (inclui o NPC). O void só causa dano se o teleporte do void estiver desligado. */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!isLobby(e.getEntity().getWorld())) {
            return;
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID && !voidTeleport) {
            return;
        }
        e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (isLobby(e.getEntity().getWorld())) {
            e.setCancelled(true);
        }
    }

    /** Nenhum mob nasce no lobby, exceto os criados pelo próprio plugin (motivo CUSTOM, como o NPC). */
    @EventHandler(ignoreCancelled = true)
    public void onMobSpawn(CreatureSpawnEvent e) {
        if (isLobby(e.getLocation().getWorld())
                && e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onWeather(WeatherChangeEvent e) {
        if (isLobby(e.getWorld()) && e.toWeatherState()) {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------
    // NPC
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onNpcClick(PlayerInteractEntityEvent e) {
        if (!isNpc(e.getRightClicked())) {
            return;
        }
        e.setCancelled(true); // impede abrir a troca do aldeão, nomear, etc.
        if (e.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(p.getUniqueId());
        if (last != null && now - last < clickCooldown) {
            return;
        }
        cooldowns.put(p.getUniqueId(), now);

        World survival = Bukkit.getWorld(survivalName);
        if (survival == null) {
            p.sendMessage(c(msgUnavailable));
            return;
        }
        Location saved = savedSurvivalLocation(p);
        if (saved != null) {
            p.teleport(saved);
            p.sendMessage(c(msg("returned-to-last", "&aVocê voltou para onde estava na sobrevivência!")));
            return;
        }
        sendToRandomSpot(p, survival);
    }


    // ------------------------------------------------------------------
    // Comando /lob (voltar ao lobby com contagem)
    // ------------------------------------------------------------------

    private String msg(String path, String def) {
        return getConfig().getString("messages." + path, def);
    }

    private boolean lobCommand(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(c("&cUse este comando dentro do jogo."));
            return true;
        }
        if (isLobby(p.getWorld())) {
            p.sendMessage(c(msg("lob-already", "&eVocê já está no lobby.")));
            return true;
        }
        Location spawn = lobbySpawn();
        if (spawn == null) {
            p.sendMessage(c(msg("lob-unavailable", "&cO lobby não está disponível agora.")));
            return true;
        }
        if (returning.containsKey(p.getUniqueId())) {
            p.sendMessage(c(msg("lob-in-progress", "&eVocê já está voltando ao lobby. Não se mova!")));
            return true;
        }

        returnStart.put(p.getUniqueId(), p.getLocation());
        p.sendMessage(c(msg("lob-start", "&eVoltando ao lobby em &6{seconds}&e segundos. &cNão se mova!")
                .replace("{seconds}", String.valueOf(lobbyDelay))));

        final int[] left = {lobbyDelay};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!p.isOnline()) {
                cancelReturn(p, null);
                return;
            }
            left[0]--;
            if (left[0] <= 0) {
                cancelReturn(p, null);
                Location dest = lobbySpawn();
                if (dest == null) {
                    p.sendMessage(c(msg("lob-unavailable", "&cO lobby não está disponível agora.")));
                    return;
                }
                p.setFallDistance(0f);
                p.teleport(dest);
                p.sendMessage(c(msg("lob-done", "&aVocê voltou ao lobby!")));
            } else {
                p.sendActionBar(c(msg("lob-countdown", "&eVoltando ao lobby em &6{seconds}&e...")
                        .replace("{seconds}", String.valueOf(left[0]))));
            }
        }, 20L, 20L);
        returning.put(p.getUniqueId(), task);
        return true;
    }

    /** Cancela a contagem do /lob. Se reason != null, avisa o jogador. */
    private void cancelReturn(Player p, String reason) {
        BukkitTask task = returning.remove(p.getUniqueId());
        returnStart.remove(p.getUniqueId());
        if (task == null) {
            return;
        }
        task.cancel();
        if (reason != null && p.isOnline()) {
            p.sendMessage(c(reason));
            p.sendActionBar(Component.empty());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLobMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location start = returnStart.get(p.getUniqueId());
        if (start == null || e.getTo() == null) {
            return;
        }
        Location to = e.getTo();
        if (to.getWorld() != start.getWorld()
                || to.distanceSquared(start) > lobbyMoveTolerance * lobbyMoveTolerance) {
            cancelReturn(p, msg("lob-cancelled", "&cVocê se moveu. Retorno ao lobby cancelado."));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLobDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && returning.containsKey(p.getUniqueId())) {
            cancelReturn(p, msg("lob-cancelled-damage", "&cVocê levou dano. Retorno ao lobby cancelado."));
        }
    }

    // ------------------------------------------------------------------
    // Neve do Natal (somente no lobby)
    // ------------------------------------------------------------------

    /** Neve caindo rápido ao redor de cada jogador do lobby, só visual. */
    private void tickSnow() {
        if (!snowEnabled) {
            return;
        }
        World lobby = Bukkit.getWorld(lobbyName);
        if (lobby == null) {
            return;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (Player p : lobby.getPlayers()) {
            Location l = p.getLocation();
            for (int i = 0; i < snowDensity; i++) {
                double x = l.getX() + (rnd.nextDouble() * 2 - 1) * snowRadius;
                double z = l.getZ() + (rnd.nextDouble() * 2 - 1) * snowRadius;
                double y = l.getY() + 2 + rnd.nextDouble() * snowHeight;
                p.spawnParticle(Particle.SNOWFLAKE, x, y, z, 0, 0.0, -1.0, 0.0, snowSpeed);
            }
        }
    }

    private boolean neveCommand(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("on") || sub.equals("off")) {
            snowEnabled = sub.equals("on");
            data.set("snow-enabled", snowEnabled);
            saveData();
            sender.sendMessage(c(snowEnabled ? "&bNeve ligada no lobby." : "&eNeve desligada no lobby."));
            return true;
        }

        if (sub.equals("encher") || sub.equals("limpar")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(c("&cUse este comando dentro do jogo."));
                return true;
            }
            if (!isLobby(p.getWorld())) {
                p.sendMessage(c("&cEntre no mundo do lobby (&e" + lobbyName + "&c) para usar este comando."));
                return true;
            }
            if (snowJob != null) {
                p.sendMessage(c("&eJá existe uma operação de neve em andamento. Aguarde terminar."));
                return true;
            }
            int radius = fillDefaultRadius;
            if (args.length > 1) {
                try {
                    radius = Integer.parseInt(args[1]);
                } catch (NumberFormatException ex) {
                    p.sendMessage(c("&cRaio inválido. Exemplo: &e/neve " + sub + " 60"));
                    return true;
                }
            }
            radius = Math.max(1, Math.min(radius, fillMaxRadius));
            startSnowJob(p, sub.equals("encher"), radius);
            return true;
        }

        sender.sendMessage(c("&eNeve: " + (snowEnabled ? "&bligada" : "&7desligada")));
        sender.sendMessage(c("&e/neve on|off &7- liga/desliga a nevasca no lobby"));
        sender.sendMessage(c("&e/neve encher [raio] &7- cobre o lobby de neve ao redor de você (padrão " + fillDefaultRadius + ")"));
        sender.sendMessage(c("&e/neve limpar [raio] &7- remove a neve ao redor de você"));
        return true;
    }

    private void startSnowJob(Player p, boolean fill, int radius) {
        final World w = p.getWorld();
        final int cx = p.getLocation().getBlockX();
        final int cz = p.getLocation().getBlockZ();
        final int r = radius;
        final UUID id = p.getUniqueId();
        final int[] pos = {-r, -r};
        final int[] changed = {0};

        p.sendMessage(c("&b" + (fill ? "Enchendo" : "Limpando") + " a neve em raio de " + r
                + " blocos... isso pode levar alguns segundos."));

        snowJob = Bukkit.getScheduler().runTaskTimer(this, () -> {
            int n = 0;
            while (n < columnsPerTick && pos[0] <= r) {
                int dx = pos[0];
                int dz = pos[1];
                if (dx * dx + dz * dz <= r * r && processSnowColumn(w, cx + dx, cz + dz, fill)) {
                    changed[0]++;
                }
                n++;
                pos[1]++;
                if (pos[1] > r) {
                    pos[1] = -r;
                    pos[0]++;
                }
            }
            if (pos[0] > r) {
                if (snowJob != null) {
                    snowJob.cancel();
                    snowJob = null;
                }
                Player target = Bukkit.getPlayer(id);
                if (target != null) {
                    target.sendMessage(c("&a" + (fill ? "Neve colocada" : "Neve removida") + " em "
                            + changed[0] + " pontos."));
                }
            }
        }, 1L, 1L);
    }

    /** Coloca ou remove uma camada de neve no ponto mais alto da coluna (só em chunks já carregados). */
    private boolean processSnowColumn(World w, int x, int z, boolean fill) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) {
            return false;
        }
        int y = w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
        if (y < w.getMinHeight() || y + 1 >= w.getMaxHeight()) {
            return false;
        }
        Block top = w.getBlockAt(x, y, z);
        if (fill) {
            if (top.getType() == Material.SNOW || !top.getType().isSolid()) {
                return false;
            }
            Block above = top.getRelative(BlockFace.UP);
            if (!above.getType().isAir()) {
                return false;
            }
            Snow layer = (Snow) Material.SNOW.createBlockData();
            layer.setLayers(1 + ThreadLocalRandom.current().nextInt(2));
            above.setBlockData(layer, false);
            return true;
        }
        if (top.getType() == Material.SNOW) {
            top.setType(Material.AIR, false);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Comando /lobbyguard
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            loadAll();
            sender.sendMessage(c("&aConfiguração recarregada."));
            return true;
        }

        if (sub.equals("removenpc")) {
            removeAllNpcs();
            data.set("npc", null);
            saveData();
            sender.sendMessage(c("&aNPC removido."));
            return true;
        }

        if (sub.equals("setnpc") || sub.equals("setspawn")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(c("&cUse este comando dentro do jogo."));
                return true;
            }
            if (!isLobby(p.getWorld())) {
                p.sendMessage(c("&cEntre no mundo do lobby (&e" + lobbyName + "&c) para usar este comando."));
                return true;
            }
            Location l = p.getLocation();
            String key = sub.equals("setnpc") ? "npc" : "spawn";
            data.set(key + ".world", l.getWorld().getName());
            data.set(key + ".x", l.getX());
            data.set(key + ".y", l.getY());
            data.set(key + ".z", l.getZ());
            data.set(key + ".yaw", (double) l.getYaw());
            data.set(key + ".pitch", (double) l.getPitch());
            saveData();

            if (sub.equals("setnpc")) {
                removeAllNpcs();
                spawnNpc(l);
                p.sendMessage(c("&aNPC de sobrevivência criado aqui. Ele é recriado automaticamente se removido."));
            } else {
                p.sendMessage(c("&aSpawn do lobby definido aqui."));
            }
            return true;
        }

        sender.sendMessage(c("&e/lobbyguard setnpc &7- cria o NPC na sua posição (no lobby)"));
        sender.sendMessage(c("&e/lobbyguard removenpc &7- remove o NPC"));
        sender.sendMessage(c("&e/lobbyguard setspawn &7- define o spawn do lobby na sua posição"));
        sender.sendMessage(c("&e/lobbyguard reload &7- recarrega o config.yml"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String s : SUBCOMMANDS) {
                if (s.startsWith(prefix)) {
                    out.add(s);
                }
            }
        }
        return out;
    }
}
