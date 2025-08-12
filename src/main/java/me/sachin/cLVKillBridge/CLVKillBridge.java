package me.sachin.cLVKillBridge;

import net.zerotoil.dev.cyberlevels.CyberLevels;
import net.zerotoil.dev.cyberlevels.objects.levels.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class CLVKillBridge extends JavaPlugin implements Listener {

    private CyberLevels clv;

    private Range pve;      // EXP range for monsters
    private Range pvp;      // EXP range for players
    private boolean debug;
    private Set<String> enabledWorlds; // empty = all

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadLocalConfig();

        var pl = Bukkit.getPluginManager().getPlugin("CyberLevels");
        if (!(pl instanceof CyberLevels clvPlugin)) {
            getLogger().severe("CyberLevels not found. Disabling...");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        this.clv = clvPlugin;

        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("CLVKillBridge enabled (API mode, ranged EXP).");
    }

    @Override
    public void onDisable() {
        getLogger().info("CLVKillBridge disabled.");
    }

    private void reloadLocalConfig() {
        this.pve = readRange("pve-exp", 5, 5);
        this.pvp = readRange("pvp-exp", 12, 12);
        this.debug = getConfig().getBoolean("debug", false);
        this.enabledWorlds = new HashSet<>(getConfig().getStringList("enabled-worlds"));
    }

    private boolean worldAllowed(World w) {
        return enabledWorlds.isEmpty() || enabledWorlds.contains(w.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (!worldAllowed(dead.getWorld())) return;

        boolean isPlayer = dead instanceof Player;
        boolean isMonster = dead instanceof Monster;
        if (!isPlayer && !isMonster) return;

        Player killer = resolveKiller(dead);
        if (killer == null) return;

        double amount = isPlayer ? roll(pvp) : roll(pve);
        if (amount <= 0) return;

        // Get PlayerData (load if needed)
        PlayerData pd = clv.levelCache().playerLevels().get(killer);
        if (pd == null) {
            clv.levelCache().loadPlayer(killer);
            pd = clv.levelCache().playerLevels().get(killer);
            if (pd == null) return;
        }

        pd.addExp(amount, true); // true = normal CLV side-effects (rewards/messages)

        if (debug) {
            getLogger().info("Gave " + amount + " EXP to " + killer.getName()
                    + " for killing " + (isPlayer ? "PLAYER" : dead.getType().name()));
        }
    }

    private Player resolveKiller(LivingEntity dead) {
        Player killer = dead.getKiller();
        if (killer != null) return killer;

        EntityDamageEvent last = dead.getLastDamageCause();
        if (last instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
            if (damager instanceof Player p) return p;
        }
        return null;
    }

    // ---------- range helpers ----------

    private record Range(double min, double max) { }

    private double roll(Range r) {
        if (r.min >= r.max) return r.min;
        return r.min + (r.max - r.min) * ThreadLocalRandom.current().nextDouble();
    }

    /** Accepts:
     *  - number (single)
     *  - string "a-b" or "a, b"
     *  - list [a, b]
     *  - section {min: a, max: b}
     */
    private Range readRange(String path, double defMin, double defMax) {
        Object v = getConfig().get(path);
        if (v == null) return new Range(defMin, defMax);

        try {
            if (v instanceof Number n) {
                double x = n.doubleValue();
                return new Range(x, x);
            }
            if (v instanceof String s) {
                String t = s.trim().replace(" ", "");
                String sep = t.contains("-") ? "-" : (t.contains(",") ? "," : null);
                if (sep != null) {
                    String[] parts = t.split(sep);
                    if (parts.length == 2) {
                        double a = Double.parseDouble(parts[0]);
                        double b = Double.parseDouble(parts[1]);
                        return ordered(a, b);
                    }
                }
                // single numeric as string
                double x = Double.parseDouble(t);
                return new Range(x, x);
            }
            if (v instanceof List<?> list && list.size() >= 2) {
                double a = toDouble(list.get(0));
                double b = toDouble(list.get(1));
                return ordered(a, b);
            }
            if (v instanceof ConfigurationSection sec) {
                double a = sec.getDouble("min", defMin);
                double b = sec.getDouble("max", defMax);
                return ordered(a, b);
            }
        } catch (Exception ex) {
            getLogger().warning("Invalid range for '" + path + "'. Using defaults.");
        }
        return new Range(defMin, defMax);
    }

    private Range ordered(double a, double b) {
        return (a <= b) ? new Range(a, b) : new Range(b, a);
    }

    private double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return Double.parseDouble(String.valueOf(o));
    }
}
