package com.bgsoftware.superiorskyblock.missions;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblock;
import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.missions.Mission;
import com.bgsoftware.superiorskyblock.api.missions.MissionLoadException;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import lv.side.sidecitadel.events.CitadelCrystalBreakEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("unused")
public final class CitadelCrystalMissions extends Mission<CitadelCrystalMissions.BreakTracker> implements Listener {

    private static final SuperiorSkyblock superiorSkyblock = SuperiorSkyblockAPI.getSuperiorSkyblock();

    private static final Pattern percentagePattern = Pattern.compile("(.*)\\{percentage_(.+?)}(.*)"),
            valuePattern = Pattern.compile("(.*)\\{value_(.+?)}(.*)");

    private final Map<Material, Integer> crystalsToBreak = new HashMap<>();
    private final Map<Material, String> itemsBossBar = new HashMap<>();

    private JavaPlugin plugin;

    @Override
    public void load(JavaPlugin plugin, ConfigurationSection section) throws MissionLoadException {
        this.plugin = plugin;

        if (!section.contains("break-crystals"))
            throw new MissionLoadException("You must have the \"break-crystals\" section in the config.");

        for (String key : section.getConfigurationSection("break-crystals").getKeys(false)) {
            String type = section.getString("break-crystals." + key + ".type");
            int amount = section.getInt("break-crystals." + key + ".amount", 1);
            String bossBar = section.getString("break-crystals." + key + ".boss-bar", "?");
            Material material;

            try {
                material = Material.valueOf(type);
            } catch (IllegalArgumentException ex) {
                throw new MissionLoadException("Invalid crystal material " + type + ".");
            }

            crystalsToBreak.put(material, amount);
            itemsBossBar.put(material, bossBar);
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);

        setClearMethod(breakTracker -> breakTracker.brokenCrystals.clear());
    }

    @Override
    public double getProgress(SuperiorPlayer superiorPlayer) {
        BreakTracker breakTracker = get(superiorPlayer);

        if (breakTracker == null)
            return 0.0;

        int requiredItems = 0;
        int interactions = 0;

        for (Map.Entry<Material, Integer> entry : this.crystalsToBreak.entrySet()) {
            requiredItems += entry.getValue();
            interactions += Math.min(breakTracker.getBroken(entry.getKey()), entry.getValue());
        }

        return (double) interactions / requiredItems;
    }

    @Override
    public int getProgressValue(SuperiorPlayer superiorPlayer) {
        BreakTracker breakTracker = get(superiorPlayer);

        if (breakTracker == null)
            return 0;

        int interactions = 0;

        for (Map.Entry<Material, Integer> entry : this.crystalsToBreak.entrySet())
            interactions += Math.min(breakTracker.getBroken(entry.getKey()), entry.getValue());

        return interactions;
    }

    public int getRequired(Material material) {
        return this.crystalsToBreak.getOrDefault(material, 0);
    }

    public int getProgress(SuperiorPlayer superiorPlayer, Material material) {
        BreakTracker breakTracker = get(superiorPlayer);
        if (breakTracker == null)
            return 0;

        return breakTracker.getBroken(material);
    }

    @Override
    public void onComplete(SuperiorPlayer superiorPlayer) {
        onCompleteFail(superiorPlayer);
    }

    @Override
    public void onCompleteFail(SuperiorPlayer superiorPlayer) {
        clearData(superiorPlayer);
    }

    @Override
    public void saveProgress(ConfigurationSection section) {
        for (Map.Entry<SuperiorPlayer, BreakTracker> entry : entrySet()) {
            String uuid = entry.getKey().getUniqueId().toString();
            int index = 0;
            for (Map.Entry<Material, Integer> craftedEntry : entry.getValue().brokenCrystals.entrySet()) {
                section.set(uuid + "." + index + ".type", craftedEntry.getKey().name());
                section.set(uuid + "." + index + ".amount", craftedEntry.getValue());
                index++;
            }
        }
    }

    @Override
    public void loadProgress(ConfigurationSection section) {
        for (String uuid : section.getKeys(false)) {
            if (uuid.equals("players"))
                continue;

            BreakTracker breakTracker = new BreakTracker();
            UUID playerUUID = UUID.fromString(uuid);
            SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(playerUUID);

            insertData(superiorPlayer, breakTracker);

            for (String key : section.getConfigurationSection(uuid).getKeys(false)) {
                Material material = Material.valueOf(section.getString(uuid + "." + key + ".type"));
                int amount = section.getInt(uuid + "." + key + ".amount");
                breakTracker.brokenCrystals.put(material, amount);
            }
        }
    }

    @Override
    public void formatItem(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        BreakTracker breakTracker = getOrCreate(superiorPlayer, s -> new BreakTracker());

        if(breakTracker == null)
            return;

        ItemMeta itemMeta = itemStack.getItemMeta();

        if (itemMeta.hasDisplayName())
            itemMeta.setDisplayName(parsePlaceholders(breakTracker, itemMeta.getDisplayName()));

        if (itemMeta.hasLore()) {
            List<String> lore = new ArrayList<>();
            for (String line : itemMeta.getLore())
                lore.add(parsePlaceholders(breakTracker, line));
            itemMeta.setLore(lore);
        }

        itemStack.setItemMeta(itemMeta);
    }

    @EventHandler
    public void onCrystalBreak(CitadelCrystalBreakEvent event) {
        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(event.getPlayer());
        if (!superiorSkyblock.getMissions().canCompleteNoProgress(superiorPlayer, this))
            return;

        trackItem(superiorPlayer, event.getMaterial());
    }

    private void trackItem(SuperiorPlayer superiorPlayer, Material material) {
        BreakTracker breakTracker = getOrCreate(superiorPlayer, s -> new BreakTracker());
        if (breakTracker == null)
            return;

        breakTracker.trackMaterial(material);
        if (itemsBossBar.containsKey(material))
            sendBossBar(superiorPlayer, itemsBossBar.get(material), getProgress(superiorPlayer, material), getRequired(material), getProgress(superiorPlayer));

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> superiorPlayer.runIfOnline(player -> {
            if (canComplete(superiorPlayer))
                SuperiorSkyblockAPI.getSuperiorSkyblock().getMissions().rewardMission(this, superiorPlayer, true);
        }), 2L);
    }

    private String parsePlaceholders(BreakTracker breakTracker, String line) {
        Matcher matcher = percentagePattern.matcher(line);

        if (matcher.matches()) {
            try {
                String requiredMaterial = matcher.group(2).toUpperCase();
                Material material = Material.valueOf(requiredMaterial);
                Optional<Map.Entry<Material, Integer>> entry = crystalsToBreak.entrySet().stream().filter(e -> e.getKey() == material).findAny();
                if (entry.isPresent()) {
                    line = line.replace("{percentage_" + matcher.group(2) + "}",
                            "" + (breakTracker.getBroken(material) * 100) / entry.get().getValue());
                }
            } catch (Exception ignored) {
            }
        }

        if ((matcher = valuePattern.matcher(line)).matches()) {
            try {
                String requiredMaterial = matcher.group(2).toUpperCase();
                Material material = Material.valueOf(requiredMaterial);
                Optional<Map.Entry<Material, Integer>> entry = crystalsToBreak.entrySet().stream().filter(e -> e.getKey() == material).findAny();
                if (entry.isPresent()) {
                    line = line.replace("{value_" + matcher.group(2) + "}",
                            "" + (breakTracker.getBroken(material)));
                }
            } catch (Exception ignored) {
            }
        }

        return ChatColor.translateAlternateColorCodes('&', line);
    }

    public static class BreakTracker {

        private final Map<Material, Integer> brokenCrystals = new HashMap<>();

        void trackMaterial(Material material) {
            brokenCrystals.put(material, brokenCrystals.getOrDefault(material, 0) + 1);
        }

        int getBroken(Material material) {
            return brokenCrystals.getOrDefault(material, 0);
        }

    }

}
