package com.bgsoftware.superiorskyblock.missions;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblock;
import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.missions.Mission;
import com.bgsoftware.superiorskyblock.api.missions.MissionLoadException;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("unused")
public final class BreedMissions extends Mission<BreedMissions.BreedTracker> implements Listener {

    private static final SuperiorSkyblock superiorSkyblock = SuperiorSkyblockAPI.getSuperiorSkyblock();

    private static final Pattern percentagePattern = Pattern.compile("(.*)\\{percentage_(.+?)}(.*)"),
            valuePattern = Pattern.compile("(.*)\\{value_(.+?)}(.*)");

    private JavaPlugin plugin;
    private final Map<List<String>, Integer> requiredEntities = new HashMap<>();
    private boolean resetAfterFinish;

    @Override
    public void load(JavaPlugin plugin, ConfigurationSection section) throws MissionLoadException {
        this.plugin = plugin;

        if (!section.contains("required-entities"))
            throw new MissionLoadException("You must have the \"required-entities\" section in the config.");

        for (String key : section.getConfigurationSection("required-entities").getKeys(false)) {
            List<String> entityTypes = section.getStringList("required-entities." + key + ".types");
            int requiredAmount = section.getInt("required-entities." + key + ".amount");
            requiredEntities.put(entityTypes, requiredAmount);
        }

        resetAfterFinish = section.getBoolean("reset-after-finish", false);

        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public double getProgress(SuperiorPlayer superiorPlayer) {
        BreedTracker breedTracker = get(superiorPlayer);

        if (breedTracker == null)
            return 0.0;

        int requiredEntities = 0;
        int bred = 0;

        for (Map.Entry<List<String>, Integer> requiredEntity : this.requiredEntities.entrySet()) {
            requiredEntities += requiredEntity.getValue();
            bred += Math.min(breedTracker.getBred(requiredEntity.getKey()), requiredEntity.getValue());
        }

        return (double) bred / requiredEntities;
    }

    @Override
    public int getProgressValue(SuperiorPlayer superiorPlayer) {
        BreedTracker breedTracker = get(superiorPlayer);

        if (breedTracker == null)
            return 0;

        int bred = 0;

        for (Map.Entry<List<String>, Integer> requiredEntity : this.requiredEntities.entrySet())
            bred += Math.min(breedTracker.getBred(requiredEntity.getKey()), requiredEntity.getValue());

        return bred;
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
        for (Map.Entry<SuperiorPlayer, BreedTracker> entry : entrySet()) {
            String uuid = entry.getKey().getUniqueId().toString();
            for (Map.Entry<String, Integer> brokenEntry : entry.getValue().breedTracker.entrySet()) {
                section.set(uuid + "." + brokenEntry.getKey(), brokenEntry.getValue());
            }
        }
    }

    @Override
    public void loadProgress(ConfigurationSection section) {
        for (String uuid : section.getKeys(false)) {
            BreedTracker breedTracker = new BreedTracker();
            UUID playerUUID = UUID.fromString(uuid);
            SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(playerUUID);

            insertData(superiorPlayer, breedTracker);

            for (String key : section.getConfigurationSection(uuid).getKeys(false)) {
                breedTracker.breedTracker.put(key, section.getInt(uuid + "." + key));
            }
        }
    }

    @Override
    public void formatItem(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        BreedTracker breedTracker = getOrCreate(superiorPlayer, s -> new BreedTracker());

        if(breedTracker == null)
            return;

        ItemMeta itemMeta = itemStack.getItemMeta();

        if (itemMeta.hasDisplayName())
            itemMeta.setDisplayName(parsePlaceholders(breedTracker, itemMeta.getDisplayName()));

        if (itemMeta.hasLore()) {
            List<String> lore = new ArrayList<>();
            for (String line : itemMeta.getLore())
                lore.add(parsePlaceholders(breedTracker, line));
            itemMeta.setLore(lore);
        }

        itemStack.setItemMeta(itemMeta);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityBreed(EntityBreedEvent e) {
        if (!isMissionEntity(e.getEntity()))
            return;

        if (!(e.getBreeder() instanceof Player))
            return;

        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer((Player) e.getBreeder());
        if (!superiorSkyblock.getMissions().canCompleteNoProgress(superiorPlayer, this))
            return;

        BreedTracker breedTracker = getOrCreate(superiorPlayer, s -> new BreedTracker());
        if (breedTracker == null)
            return;

        breedTracker.track(e.getEntity().getType().name(), 1);

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> superiorPlayer.runIfOnline(player -> {
            if (canComplete(superiorPlayer))
                SuperiorSkyblockAPI.getSuperiorSkyblock().getMissions().rewardMission(this, superiorPlayer, true);
        }), 2L);
    }

    private boolean isMissionEntity(Entity entity) {
        if (entity == null || entity instanceof ArmorStand)
            return false;

        for (List<String> requiredEntity : requiredEntities.keySet()) {
            if (requiredEntity.contains("ALL") || requiredEntity.contains("all") || requiredEntity.contains(entity.getType().name()))
                return true;
        }

        return false;
    }

    private String parsePlaceholders(BreedTracker breedTracker, String line) {
        Matcher matcher = percentagePattern.matcher(line);

        if (matcher.matches()) {
            String requiredBlock = matcher.group(2).toUpperCase();
            Optional<Map.Entry<List<String>, Integer>> entry = requiredEntities.entrySet().stream().filter(e -> e.getKey().contains(requiredBlock)).findAny();
            if (entry.isPresent()) {
                line = line.replace("{percentage_" + matcher.group(2) + "}",
                        "" + (breedTracker.getBred(Collections.singletonList(requiredBlock)) * 100) / entry.get().getValue());
            }
        }

        if ((matcher = valuePattern.matcher(line)).matches()) {
            String requiredBlock = matcher.group(2).toUpperCase();
            Optional<Map.Entry<List<String>, Integer>> entry = requiredEntities.entrySet().stream().filter(e -> e.getKey().contains(requiredBlock)).findFirst();
            if (entry.isPresent()) {
                line = line.replace("{value_" + matcher.group(2) + "}",
                        "" + breedTracker.getBred(Collections.singletonList(requiredBlock)));
            }
        }

        return ChatColor.translateAlternateColorCodes('&', line);
    }

    public static class BreedTracker {

        private final Map<String, Integer> breedTracker = new HashMap<>();

        void track(String entity, int amount) {
            int newAmount = amount + breedTracker.getOrDefault(entity, 0);
            breedTracker.put(entity, newAmount);
        }

        int getBred(List<String> entities) {
            int amount = 0;
            boolean all = entities.contains("ALL") || entities.contains("all");

            for (String entity : breedTracker.keySet()) {
                if (all || entities.contains(entity))
                    amount += breedTracker.get(entity);
            }

            return amount;
        }

    }

}
