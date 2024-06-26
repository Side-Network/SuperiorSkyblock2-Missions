package com.bgsoftware.superiorskyblock.missions;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblock;
import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.missions.Mission;
import com.bgsoftware.superiorskyblock.api.missions.MissionLoadException;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
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
public final class FishingMissions extends Mission<FishingMissions.FishingTracker> implements Listener {

    private static final SuperiorSkyblock superiorSkyblock = SuperiorSkyblockAPI.getSuperiorSkyblock();

    private static final Pattern percentagePattern = Pattern.compile("(.*)\\{percentage_(.+?)}(.*)"),
            valuePattern = Pattern.compile("(.*)\\{value_(.+?)}(.*)");

    private final Map<List<Material>, Integer> itemsToCatch = new HashMap<>();
    private final Map<Material, String> itemsBossBar = new HashMap<>();

    private JavaPlugin plugin;

    @Override
    public void load(JavaPlugin plugin, ConfigurationSection section) throws MissionLoadException {
        this.plugin = plugin;

        if (!section.contains("required-caughts"))
            throw new MissionLoadException("You must have the \"required-caughts\" section in the config.");

        for (String key : section.getConfigurationSection("required-caughts").getKeys(false)) {
            List<String> itemTypes = section.getStringList("required-caughts." + key + ".types");
            int amount = section.getInt("required-caughts." + key + ".amount", 1);

            List<Material> itemsToCatch = new ArrayList<>();

            for(String itemType : itemTypes) {
                byte data = 0;

                if(itemType.contains(":")) {
                    String[] sections = itemType.split(":");
                    itemType = sections[0];
                    try {
                        data = sections.length == 2 ? Byte.parseByte(sections[1]) : 0;
                    } catch (NumberFormatException ex) {
                        throw new MissionLoadException("Invalid fishing item data " + sections[1] + ".");
                    }
                }

                Material material;

                try {
                    material = Material.valueOf(itemType);
                } catch (IllegalArgumentException ex) {
                    throw new MissionLoadException("Invalid fishing item " + itemType + ".");
                }

                itemsToCatch.add(material);
            }

            this.itemsToCatch.put(itemsToCatch, amount);
            String bossBar = section.getString("required-caughts." + key + ".boss-bar", "?");
            for (Material toCatch : itemsToCatch) {
                itemsBossBar.put(toCatch, bossBar);
            }
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);

        setClearMethod(fishingTracker -> fishingTracker.caughtItems.clear());
    }

    @Override
    public double getProgress(SuperiorPlayer superiorPlayer) {
        FishingTracker fishingTracker = get(superiorPlayer);

        if (fishingTracker == null)
            return 0.0;

        int requiredItems = 0;
        int interactions = 0;

        for (Map.Entry<List<Material>, Integer> entry : this.itemsToCatch.entrySet()) {
            requiredItems += entry.getValue();
            interactions += Math.min(fishingTracker.getCaughts(entry.getKey()), entry.getValue());
        }

        return (double) interactions / requiredItems;
    }

    @Override
    public int getProgressValue(SuperiorPlayer superiorPlayer) {
        FishingTracker fishingTracker = get(superiorPlayer);

        if (fishingTracker == null)
            return 0;

        int interactions = 0;

        for (Map.Entry<List<Material>, Integer> entry : this.itemsToCatch.entrySet())
            interactions += Math.min(fishingTracker.getCaughts(entry.getKey()), entry.getValue());

        return interactions;
    }

    public int getRequired(ItemStack itemStack) {
        ItemStack keyItem = itemStack.clone();
        keyItem.setAmount(1);

        int req = 0;
        for (Map.Entry<List<Material>, Integer> entry : itemsToCatch.entrySet()) {
            if (entry.getKey().contains(keyItem.getType()))
                req += entry.getValue();
        }

        return req;
    }

    public int getProgress(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        FishingTracker fishingTracker = get(superiorPlayer);
        if (fishingTracker == null)
            return 0;

        for (Map.Entry<List<Material>, Integer> entry : itemsToCatch.entrySet()) {
            if (entry.getKey().contains(itemStack.getType()))
                return fishingTracker.getCaughts(entry.getKey());
        }

        return 0;
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
        for (Map.Entry<SuperiorPlayer, FishingTracker> entry : entrySet()) {
            String uuid = entry.getKey().getUniqueId().toString();
            int index = 0;
            for (Map.Entry<Material, Integer> craftedEntry : entry.getValue().caughtItems.entrySet()) {
                section.set(uuid + "." + index + ".item", craftedEntry.getKey().name());
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

            FishingTracker fishingTracker = new FishingTracker();
            UUID playerUUID = UUID.fromString(uuid);
            SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(playerUUID);

            insertData(superiorPlayer, fishingTracker);

            for (String key : section.getConfigurationSection(uuid).getKeys(false)) {
                Material material;
                try {
                    String mat = section.getString(uuid + "." + key + ".item");
                    material = Material.valueOf(mat);
                } catch (IllegalArgumentException ex) {
                    ItemStack oldItem = section.getItemStack(uuid + "." + key + ".item");
                    if (oldItem == null)
                        continue;

                    material = oldItem.getType();
                }
                int amount = section.getInt(uuid + "." + key + ".amount");
                fishingTracker.caughtItems.put(material, amount);
            }
        }
    }

    @Override
    public void formatItem(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        FishingTracker fishingTracker = getOrCreate(superiorPlayer, s -> new FishingTracker());

        if(fishingTracker == null)
            return;

        ItemMeta itemMeta = itemStack.getItemMeta();

        if (itemMeta.hasDisplayName())
            itemMeta.setDisplayName(parsePlaceholders(fishingTracker, itemMeta.getDisplayName()));

        if (itemMeta.hasLore()) {
            List<String> lore = new ArrayList<>();
            for (String line : itemMeta.getLore())
                lore.add(parsePlaceholders(fishingTracker, line));
            itemMeta.setLore(lore);
        }

        itemStack.setItemMeta(itemMeta);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerFish(PlayerFishEvent e){
        if(!(e.getCaught() instanceof Item) || e.getState() != PlayerFishEvent.State.CAUGHT_FISH)
            return;

        Item caughtItem = (Item) e.getCaught();
        ItemStack caughtItemStack = caughtItem.getItemStack().clone();
        caughtItemStack.setAmount(1);

        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(e.getPlayer());

        if(!isMissionItem(caughtItemStack))
            return;

        if(!superiorSkyblock.getMissions().canCompleteNoProgress(superiorPlayer, this))
            return;

        trackItem(superiorPlayer, caughtItem.getItemStack());
    }

    private void trackItem(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        FishingTracker blocksTracker = getOrCreate(superiorPlayer, s -> new FishingTracker());

        if(blocksTracker == null)
            return;

        blocksTracker.trackItem(itemStack);
        if (itemsBossBar.containsKey(itemStack.getType()))
            sendBossBar(superiorPlayer, itemsBossBar.get(itemStack.getType()), getProgress(superiorPlayer, itemStack), getRequired(itemStack), getProgress(superiorPlayer));

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> superiorPlayer.runIfOnline(player -> {
            if (canComplete(superiorPlayer))
                SuperiorSkyblockAPI.getSuperiorSkyblock().getMissions().rewardMission(this, superiorPlayer, true);
        }), 2L);
    }

    private boolean isMissionItem(ItemStack itemStack) {
        if (itemStack == null)
            return false;

        for (List<Material> requiredItem : this.itemsToCatch.keySet()) {
            if (requiredItem.contains(itemStack.getType()))
                return true;
        }

        return false;
    }

    private String parsePlaceholders(FishingTracker entityTracker, String line) {
        Matcher matcher = percentagePattern.matcher(line);

        if (matcher.matches()) {
            try {
                String requiredBlock = matcher.group(2).toUpperCase();
                Material mat = Material.valueOf(requiredBlock);

                Optional<Map.Entry<List<Material>, Integer>> entry = itemsToCatch.entrySet().stream()
                        .filter(e -> e.getKey().contains(mat)).findAny();

                if (entry.isPresent()) {
                    line = line.replace("{percentage_" + matcher.group(2) + "}",
                            "" + (entityTracker.getCaughts(Collections.singletonList(mat)) * 100) / entry.get().getValue());
                }
            } catch (Exception ignored) {
            }
        }

        if ((matcher = valuePattern.matcher(line)).matches()) {
            try {
                String requiredBlock = matcher.group(2).toUpperCase();
                Material mat = Material.valueOf(requiredBlock);

                Optional<Map.Entry<List<Material>, Integer>> entry = itemsToCatch.entrySet().stream()
                        .filter(e -> e.getKey().contains(mat)).findAny();

                if (entry.isPresent()) {
                    line = line.replace("{value_" + matcher.group(2) + "}",
                            "" + (entityTracker.getCaughts(Collections.singletonList(mat))));
                }
            } catch (Exception ignored) {
            }
        }

        return ChatColor.translateAlternateColorCodes('&', line);
    }

    public static class FishingTracker {

        private final Map<Material, Integer> caughtItems = new HashMap<>();

        void trackItem(ItemStack itemStack) {
            caughtItems.put(itemStack.getType(), caughtItems.getOrDefault(itemStack.getType(), 0) + itemStack.getAmount());
        }

        int getCaughts(Material material) {
            return caughtItems.getOrDefault(material, 0);
        }

        int getCaughts(List<Material> itemStacks) {
            int caughts = 0;

            for(Material material : itemStacks) {
                caughts += caughtItems.getOrDefault(material, 0);
            }

            return caughts;
        }

    }

}
