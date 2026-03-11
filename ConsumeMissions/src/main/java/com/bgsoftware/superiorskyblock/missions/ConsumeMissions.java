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
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("unused")
public final class ConsumeMissions extends Mission<ConsumeMissions.ConsumingTracker> implements Listener {

    private static final SuperiorSkyblock superiorSkyblock = SuperiorSkyblockAPI.getSuperiorSkyblock();

    private static final Pattern percentagePattern = Pattern.compile("(.*)\\{percentage_(.+?)}(.*)"),
            valuePattern = Pattern.compile("(.*)\\{value_(.+?)}(.*)"),
            requiredPattern = Pattern.compile("(.*)\\{required_(.+?)}(.*)");

    private final Map<List<ItemStack>, Integer> itemsToConsume = new HashMap<>();
    private final Map<Material, String> itemsBossBar = new HashMap<>();

    private JavaPlugin plugin;

    @Override
    public void load(JavaPlugin plugin, ConfigurationSection section) throws MissionLoadException {
        this.plugin = plugin;

        if (!section.contains("consume-items"))
            throw new MissionLoadException("You must have the \"consume-items\" section in the config.");

        for (String key : section.getConfigurationSection("consume-items").getKeys(false)) {
            List<String> itemTypes = section.getStringList("consume-items." + key + ".types");
            int amount = section.getInt("consume-items." + key + ".amount", 1);

            List<ItemStack> itemsToConsume = new ArrayList<>();
            for (String itemType : itemTypes) {
                byte data = 0;

                if (itemType.contains(":")) {
                    String[] sections = itemType.split(":");
                    itemType = sections[0];
                    try {
                        data = sections.length == 2 ? Byte.parseByte(sections[1]) : 0;
                    } catch (NumberFormatException ex) {
                        throw new MissionLoadException("Invalid consume item data " + sections[1] + ".");
                    }
                }

                Material material;

                try {
                    material = Material.valueOf(itemType);
                } catch (IllegalArgumentException ex) {
                    throw new MissionLoadException("Invalid consume item " + itemType + ".");
                }

                itemsToConsume.add(new ItemStack(material, 1, data));
            }

            this.itemsToConsume.put(itemsToConsume, amount);
            String bossBar = section.getString("consume-items." + key + ".boss-bar", "?");
            for (ItemStack toConsume : itemsToConsume) {
                itemsBossBar.put(toConsume.getType(), bossBar);
            }
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);

        setClearMethod(consumingTracker -> consumingTracker.consumeItems.clear());
    }

    @Override
    public double getProgress(SuperiorPlayer superiorPlayer) {
        ConsumingTracker consumingTracker = get(superiorPlayer);

        if (consumingTracker == null)
            return 0.0;

        double multiplier = getPeakMemberMultiplier(superiorPlayer);
        int requiredItems = 0;
        int interactions = 0;

        for (Map.Entry<List<ItemStack>, Integer> entry : this.itemsToConsume.entrySet()) {
            if (entry.getKey().isEmpty())
                continue;
            int scaledRequired = (int) Math.ceil(entry.getValue() * multiplier);
            requiredItems += scaledRequired;
            interactions += Math.min(consumingTracker.getConsumed(entry.getKey()), scaledRequired);
        }

        return (double) interactions / requiredItems;
    }

    @Override
    public int getProgressValue(SuperiorPlayer superiorPlayer) {
        ConsumingTracker consumingTracker = get(superiorPlayer);

        if (consumingTracker == null)
            return 0;

        int interactions = 0;

        double multiplier = getPeakMemberMultiplier(superiorPlayer);
        for (Map.Entry<List<ItemStack>, Integer> entry : this.itemsToConsume.entrySet()) {
            if (entry.getKey().isEmpty())
                continue;
            interactions += Math.min(consumingTracker.getConsumed(entry.getKey()), (int) Math.ceil(entry.getValue() * multiplier));
        }

        return interactions;
    }

    public int getRequired(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        double multiplier = getPeakMemberMultiplier(superiorPlayer);
        ItemStack keyItem = itemStack.clone();
        keyItem.setAmount(1);

        for (Map.Entry<List<ItemStack>, Integer> entry : this.itemsToConsume.entrySet()) {
            if (entry.getKey().contains(keyItem))
                return (int) Math.ceil(entry.getValue() * multiplier);
        }

        return 0;
    }

    public int getProgress(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        ConsumingTracker consumingTracker = get(superiorPlayer);
        if (consumingTracker == null)
            return 0;

        ItemStack keyItem = itemStack.clone();
        keyItem.setAmount(1);
        int progress = 0;

        for (Map.Entry<List<ItemStack>, Integer> entry : this.itemsToConsume.entrySet()) {
            if (!entry.getKey().contains(keyItem))
                continue;
            int scaledRequired = getRequired(superiorPlayer, itemStack);
            progress += Math.min(consumingTracker.getConsumed(entry.getKey()), scaledRequired);
        }

        return progress;
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
        for (Map.Entry<SuperiorPlayer, ConsumingTracker> entry : entrySet()) {
            String uuid = entry.getKey().getUniqueId().toString();
            int index = 0;
            for (Map.Entry<ItemStack, Integer> consumedEntry : entry.getValue().consumeItems.entrySet()) {
                section.set(uuid + "." + index + ".item", consumedEntry.getKey());
                section.set(uuid + "." + index + ".amount", consumedEntry.getValue());
                index++;
            }
        }
    }

    @Override
    public void loadProgress(ConfigurationSection section) {
        for (String uuid : section.getKeys(false)) {
            if (uuid.equals("players"))
                continue;

            ConsumingTracker consumingTracker = new ConsumingTracker();
            UUID playerUUID = UUID.fromString(uuid);
            SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(playerUUID);

            insertData(superiorPlayer, consumingTracker);

            for (String key : section.getConfigurationSection(uuid).getKeys(false)) {
                ItemStack itemStack = section.getItemStack(uuid + "." + key + ".item");
                int amount = section.getInt(uuid + "." + key + ".amount");
                consumingTracker.consumeItems.put(itemStack, amount);
            }
        }
    }

    @Override
    public void formatItem(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        ConsumingTracker consumingTracker = getOrCreate(superiorPlayer, s -> new ConsumingTracker());

        if(consumingTracker == null)
            return;

        ItemMeta itemMeta = itemStack.getItemMeta();

        if (itemMeta.hasDisplayName())
            itemMeta.setDisplayName(parsePlaceholders(superiorPlayer, consumingTracker, itemMeta.getDisplayName()));

        if (itemMeta.hasLore()) {
            List<String> lore = new ArrayList<>();
            for (String line : itemMeta.getLore())
                lore.add(parsePlaceholders(superiorPlayer, consumingTracker, line));
            itemMeta.setLore(lore);
        }

        itemStack.setItemMeta(itemMeta);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemConsume(PlayerItemConsumeEvent e) {
        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(e.getPlayer());
        if (!superiorSkyblock.getMissions().canCompleteNoProgress(superiorPlayer, this))
            return;

        trackItem(superiorPlayer, e.getItem());
    }

    private void trackItem(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        ConsumingTracker blocksTracker = getOrCreate(superiorPlayer, s -> new ConsumingTracker());
        if (blocksTracker == null)
            return;

        blocksTracker.trackItem(itemStack);
        if (itemsBossBar.containsKey(itemStack.getType()))
            sendBossBar(superiorPlayer, itemsBossBar.get(itemStack.getType()), getProgress(superiorPlayer, itemStack), getRequired(superiorPlayer, itemStack), getProgress(superiorPlayer));

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> superiorPlayer.runIfOnline(player -> {
            if (canComplete(superiorPlayer))
                SuperiorSkyblockAPI.getSuperiorSkyblock().getMissions().rewardMission(this, superiorPlayer, true);
        }), 2L);
    }

    private static int countItems(HumanEntity humanEntity, ItemStack itemStack) {
        int amount = 0;

        if (itemStack == null)
            return amount;

        PlayerInventory playerInventory = humanEntity.getInventory();

        for (ItemStack invItem : playerInventory.getContents()) {
            if (invItem != null && itemStack.isSimilar(invItem))
                amount += invItem.getAmount();
        }

        if (humanEntity.getItemOnCursor() != null && itemStack.isSimilar(humanEntity.getItemOnCursor()))
            amount += humanEntity.getItemOnCursor().getAmount();

        return amount;
    }

    private String parsePlaceholders(SuperiorPlayer superiorPlayer, ConsumingTracker consumingTracker, String line) {
        Matcher matcher = percentagePattern.matcher(line);
        double multiplier = getPeakMemberMultiplier(superiorPlayer);

        if (matcher.matches()) {
            try {
                String requiredItem = matcher.group(2).toUpperCase();
                ItemStack itemStack = new ItemStack(Material.valueOf(requiredItem));
                Optional<Map.Entry<List<ItemStack>, Integer>> entry = itemsToConsume.entrySet().stream()
                        .filter(e -> e.getKey().contains(itemStack)).findAny();

                if (entry.isPresent()) {
                    int scaledRequired = (int) Math.ceil(entry.get().getValue() * multiplier);
                    line = line.replace("{percentage_" + matcher.group(2) + "}",
                            "" + (consumingTracker.getConsumed(entry.get().getKey()) * 100) / scaledRequired);
                }
            } catch (Exception ignored) {
            }
        }

        if ((matcher = valuePattern.matcher(line)).matches()) {
            try {
                String requiredBlock = matcher.group(2).toUpperCase();
                ItemStack itemStack = new ItemStack(Material.valueOf(requiredBlock));
                Optional<Map.Entry<List<ItemStack>, Integer>> entry = itemsToConsume.entrySet().stream()
                        .filter(e -> e.getKey().contains(itemStack)).findAny();

                if (entry.isPresent()) {
                    line = line.replace("{value_" + matcher.group(2) + "}",
                            "" + (consumingTracker.getConsumed(entry.get().getKey())));
                }
            } catch (Exception ignored) {
            }
        }

        if ((matcher = requiredPattern.matcher(line)).matches()) {
            try {
                String requiredBlock = matcher.group(2).toUpperCase();
                ItemStack itemStack = new ItemStack(Material.valueOf(requiredBlock));
                Optional<Map.Entry<List<ItemStack>, Integer>> entry = itemsToConsume.entrySet().stream()
                        .filter(e -> e.getKey().contains(itemStack)).findAny();

                if (entry.isPresent()) {
                    int scaledRequired = (int) Math.ceil(entry.get().getValue() * multiplier);
                    line = line.replace("{required_" + matcher.group(2) + "}",
                            "" + scaledRequired);
                }
            } catch (Exception ignored) {
            }
        }

        return ChatColor.translateAlternateColorCodes('&', line);
    }

    public static class ConsumingTracker {

        private final Map<ItemStack, Integer> consumeItems = new HashMap<>();

        void trackItem(ItemStack itemStack) {
            ItemStack keyItem = itemStack.clone();
            keyItem.setAmount(1);
            consumeItems.put(keyItem, consumeItems.getOrDefault(keyItem, 0) + itemStack.getAmount());
        }

        int getConsumed(List<ItemStack> itemStacks) {
            int consumed = 0;

            for (ItemStack itemStack : itemStacks) {
                consumed += consumeItems.getOrDefault(itemStack, 0);
            }

            return consumed;
        }
    }

}
