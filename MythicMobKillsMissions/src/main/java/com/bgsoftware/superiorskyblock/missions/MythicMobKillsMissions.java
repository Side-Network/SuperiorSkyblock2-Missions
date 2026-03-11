package com.bgsoftware.superiorskyblock.missions;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblock;
import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.missions.Mission;
import com.bgsoftware.superiorskyblock.api.missions.MissionLoadException;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import io.lumine.mythic.api.mobs.MythicMob;
import io.lumine.mythic.bukkit.events.MythicMobDeathEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("unused")
public final class MythicMobKillsMissions extends Mission<MythicMobKillsMissions.KillsTracker> implements Listener {

    private static final SuperiorSkyblock superiorSkyblock = SuperiorSkyblockAPI.getSuperiorSkyblock();

    private static final Pattern percentagePattern = Pattern.compile("(.*)\\{percentage_(.+?)}(.*)"),
            valuePattern = Pattern.compile("(.*)\\{value_(.+?)}(.*)"),
            requiredPattern = Pattern.compile("(.*)\\{required_(.+?)}(.*)");

    private JavaPlugin plugin;
    private final Map<List<String>, Integer> requiredEntities = new HashMap<>();
    private final Map<String, String> entityBossBar = new HashMap<>();
    private boolean resetAfterFinish;

    @Override
    public void load(JavaPlugin plugin, ConfigurationSection section) throws MissionLoadException {
        this.plugin = plugin;

        if (!section.contains("required-entities"))
            throw new MissionLoadException("You must have the \"required-entities\" section in the config.");

        for (String key : section.getConfigurationSection("required-entities").getKeys(false)) {
            List<String> entityTypes = section.getStringList("required-entities." + key + ".types");
            int requiredAmount = section.getInt("required-entities." + key + ".amount");
            String bossBar = section.getString("required-entities." + key + ".boss-bar", "?");

            requiredEntities.put(entityTypes, requiredAmount);
            for (String type : entityTypes) {
                try {
                    entityBossBar.put(type, bossBar);
                } catch (Exception ignored) {}
            }
        }

        resetAfterFinish = section.getBoolean("reset-after-finish", false);

        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public double getProgress(SuperiorPlayer superiorPlayer) {
        KillsTracker killsTracker = get(superiorPlayer);

        if (killsTracker == null)
            return 0.0;

        double multiplier = getPeakMemberMultiplier(superiorPlayer);
        int requiredEntities = 0;
        int kills = 0;

        for (Map.Entry<List<String>, Integer> entry : this.requiredEntities.entrySet()) {
            int scaledRequired = (int) Math.ceil(entry.getValue() * multiplier);
            requiredEntities += scaledRequired;
            kills += Math.min(killsTracker.getKills(entry.getKey()), scaledRequired);
        }

        return (double) kills / requiredEntities;
    }

    @Override
    public int getProgressValue(SuperiorPlayer superiorPlayer) {
        KillsTracker killsTracker = get(superiorPlayer);

        if (killsTracker == null)
            return 0;

        int kills = 0;

        double multiplier = getPeakMemberMultiplier(superiorPlayer);
        for (Map.Entry<List<String>, Integer> entry : this.requiredEntities.entrySet())
            kills += Math.min(killsTracker.getKills(entry.getKey()), (int) Math.ceil(entry.getValue() * multiplier));

        return kills;
    }

    public int getRequired(SuperiorPlayer superiorPlayer, String type) {
        double multiplier = getPeakMemberMultiplier(superiorPlayer);
        for (Map.Entry<List<String>, Integer> entry : requiredEntities.entrySet()) {
            if (entry.getKey().contains(type))
                return (int) Math.ceil(entry.getValue() * multiplier);
        }

        return 0;
    }

    public int getProgress(SuperiorPlayer superiorPlayer, MythicMob mob) {
        KillsTracker killsTracker = get(superiorPlayer);
        if (killsTracker == null)
            return 0;

        for (Map.Entry<List<String>, Integer> entry : this.requiredEntities.entrySet()) {
            if (entry.getKey().contains(mob.getInternalName())) {
                int scaledRequired = getRequired(superiorPlayer, mob.getInternalName());
                return Math.min(killsTracker.getKills(entry.getKey()), scaledRequired);
            }
        }

        return 0;
    }

    @Override
    public void onComplete(SuperiorPlayer superiorPlayer) {
        if (resetAfterFinish)
            clearData(superiorPlayer);
    }

    @Override
    public void onCompleteFail(SuperiorPlayer superiorPlayer) {

    }

    @Override
    public void saveProgress(ConfigurationSection section) {
        for (Map.Entry<SuperiorPlayer, KillsTracker> entry : entrySet()) {
            String uuid = entry.getKey().getUniqueId().toString();
            for (Map.Entry<String, Integer> brokenEntry : entry.getValue().killsTracker.entrySet()) {
                section.set(uuid + "." + brokenEntry.getKey(), brokenEntry.getValue());
            }
        }
    }

    @Override
    public void loadProgress(ConfigurationSection section) {
        for (String uuid : section.getKeys(false)) {
            KillsTracker killsTracker = new KillsTracker();
            UUID playerUUID = UUID.fromString(uuid);
            SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(playerUUID);

            insertData(superiorPlayer, killsTracker);

            for (String key : section.getConfigurationSection(uuid).getKeys(false)) {
                killsTracker.killsTracker.put(key, section.getInt(uuid + "." + key));
            }
        }
    }

    @Override
    public void formatItem(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        KillsTracker killsTracker = getOrCreate(superiorPlayer, s -> new KillsTracker());

        if(killsTracker == null)
            return;

        ItemMeta itemMeta = itemStack.getItemMeta();

        if (itemMeta.hasDisplayName())
            itemMeta.setDisplayName(parsePlaceholders(superiorPlayer, killsTracker, itemMeta.getDisplayName()));

        if (itemMeta.hasLore()) {
            List<String> lore = new ArrayList<>();
            for (String line : itemMeta.getLore())
                lore.add(parsePlaceholders(superiorPlayer, killsTracker, line));
            itemMeta.setLore(lore);
        }

        itemStack.setItemMeta(itemMeta);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityKill(MythicMobDeathEvent e) {
        if (!isMissionEntity(e.getMobType().getInternalName()) || !(e.getKiller() instanceof Player damager))
            return;

        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(damager);
        if (!superiorSkyblock.getMissions().canCompleteNoProgress(superiorPlayer, this))
            return;

        KillsTracker killsTracker = getOrCreate(superiorPlayer, s -> new KillsTracker());
        if (killsTracker == null)
            return;

        String mobName = e.getMobType().getInternalName();

        killsTracker.track(mobName, 1);
        if (entityBossBar.containsKey(mobName)) {
            sendBossBar(superiorPlayer, entityBossBar.get(mobName), getProgress(superiorPlayer, e.getMobType()), getRequired(superiorPlayer, mobName), getProgress(superiorPlayer));
        }

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> superiorPlayer.runIfOnline(player -> {
            if (canComplete(superiorPlayer))
                SuperiorSkyblockAPI.getSuperiorSkyblock().getMissions().rewardMission(this, superiorPlayer, true);
        }), 2L);
    }

    private boolean isMissionEntity(String mob) {
        for (List<String> requiredEntity : requiredEntities.keySet()) {
            if (requiredEntity.contains("ALL") || requiredEntity.contains("all") || requiredEntity.contains(mob))
                return true;
        }

        return false;
    }

    private String parsePlaceholders(SuperiorPlayer superiorPlayer, KillsTracker killsTracker, String line) {
        Matcher matcher = percentagePattern.matcher(line);
        double multiplier = getPeakMemberMultiplier(superiorPlayer);

        if (matcher.matches()) {
            String requiredEntity = matcher.group(2);
            Optional<Map.Entry<List<String>, Integer>> entry = requiredEntities.entrySet().stream().filter(e -> e.getKey().contains(requiredEntity)).findAny();
            if (entry.isPresent()) {
                int scaledRequired = (int) Math.ceil(entry.get().getValue() * multiplier);
                line = line.replace("{percentage_" + matcher.group(2) + "}",
                        "" + (killsTracker.getKills(entry.get().getKey()) * 100) / scaledRequired);
            }
        }

        if ((matcher = valuePattern.matcher(line)).matches()) {
            String requiredEntity = matcher.group(2);
            Optional<Map.Entry<List<String>, Integer>> entry = requiredEntities.entrySet().stream().filter(e -> e.getKey().contains(requiredEntity)).findFirst();
            if (entry.isPresent()) {
                line = line.replace("{value_" + matcher.group(2) + "}",
                        "" + killsTracker.getKills(entry.get().getKey()));
            }
        }

        if ((matcher = requiredPattern.matcher(line)).matches()) {
            String requiredEntity = matcher.group(2);
            Optional<Map.Entry<List<String>, Integer>> entry = requiredEntities.entrySet().stream().filter(e -> e.getKey().contains(requiredEntity)).findFirst();
            if (entry.isPresent()) {
                int scaledRequired = (int) Math.ceil(entry.get().getValue() * multiplier);
                line = line.replace("{required_" + matcher.group(2) + "}",
                        "" + scaledRequired);
            }
        }

        return ChatColor.translateAlternateColorCodes('&', line);
    }

    public static class KillsTracker {

        private final Map<String, Integer> killsTracker = new HashMap<>();

        void track(String entity, int amount) {
            int newAmount = amount + killsTracker.getOrDefault(entity, 0);
            killsTracker.put(entity, newAmount);
        }

        int getKills(List<String> entities) {
            int amount = 0;
            boolean all = entities.contains("ALL") || entities.contains("all");

            for (String entity : killsTracker.keySet()) {
                if (all || entities.contains(entity))
                    amount += killsTracker.get(entity);
            }

            return amount;
        }

    }

}
