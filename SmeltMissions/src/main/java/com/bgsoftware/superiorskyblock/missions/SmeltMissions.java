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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
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
public final class SmeltMissions extends Mission<SmeltMissions.SmeltingTracker> implements Listener {

    private static final SuperiorSkyblock superiorSkyblock = SuperiorSkyblockAPI.getSuperiorSkyblock();

    private static final Pattern percentagePattern = Pattern.compile("(.*)\\{percentage_(.+?)}(.*)"),
            valuePattern = Pattern.compile("(.*)\\{value_(.+?)}(.*)");

    private final Map<List<ItemStack>, Integer> itemsToSmelt = new HashMap<>();
    private final Map<Material, String> itemsBossBar = new HashMap<>();

    private JavaPlugin plugin;

    @Override
    public void load(JavaPlugin plugin, ConfigurationSection section) throws MissionLoadException {
        this.plugin = plugin;

        if (!section.contains("result-items"))
            throw new MissionLoadException("You must have the \"result-items\" section in the config.");

        for (String key : section.getConfigurationSection("result-items").getKeys(false)) {
            List<String> itemTypes = section.getStringList("result-items." + key + ".types");
            int amount = section.getInt("result-items." + key + ".amount", 1);

            List<ItemStack> itemsToSmelt = new ArrayList<>();
            for (String itemType : itemTypes) {
                byte data = 0;

                if (itemType.contains(":")) {
                    String[] sections = itemType.split(":");
                    itemType = sections[0];
                    try {
                        data = sections.length == 2 ? Byte.parseByte(sections[1]) : 0;
                    } catch (NumberFormatException ex) {
                        throw new MissionLoadException("Invalid smelt item data " + sections[1] + ".");
                    }
                }

                Material material;

                try {
                    material = Material.valueOf(itemType);
                } catch (IllegalArgumentException ex) {
                    throw new MissionLoadException("Invalid sell item " + itemType + ".");
                }

                itemsToSmelt.add(new ItemStack(material, 1, data));
            }

            this.itemsToSmelt.put(itemsToSmelt, amount);
            String bossBar = section.getString("result-items." + key + ".boss-bar", "?");
            for (ItemStack toSell : itemsToSmelt) {
                itemsBossBar.put(toSell.getType(), bossBar);
            }
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);

        setClearMethod(smeltingTracker -> smeltingTracker.smeltItems.clear());
    }

    @Override
    public double getProgress(SuperiorPlayer superiorPlayer) {
        SmeltingTracker smeltingTracker = get(superiorPlayer);

        if (smeltingTracker == null)
            return 0.0;

        int requiredItems = 0;
        int interactions = 0;

        for (Map.Entry<List<ItemStack>, Integer> entry : this.itemsToSmelt.entrySet()) {
            if (entry.getKey().isEmpty())
                continue;
            requiredItems += entry.getValue();
            interactions += Math.min(smeltingTracker.getSmelts(entry.getKey()), entry.getValue());
        }

        return (double) interactions / requiredItems;
    }

    @Override
    public int getProgressValue(SuperiorPlayer superiorPlayer) {
        SmeltingTracker smeltingTracker = get(superiorPlayer);

        if (smeltingTracker == null)
            return 0;

        int interactions = 0;

        for (Map.Entry<List<ItemStack>, Integer> entry : this.itemsToSmelt.entrySet()) {
            if (entry.getKey().isEmpty())
                continue;
            interactions += Math.min(smeltingTracker.getSmelts(entry.getKey()), entry.getValue());
        }

        return interactions;
    }

    public int getRequired(ItemStack itemStack) {
        ItemStack keyItem = itemStack.clone();
        keyItem.setAmount(1);

        for (Map.Entry<List<ItemStack>, Integer> entry : this.itemsToSmelt.entrySet()) {
            if (entry.getKey().contains(keyItem))
                return entry.getValue();
        }

        return 0;
    }

    public int getProgress(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        SmeltingTracker smeltingTracker = get(superiorPlayer);
        if (smeltingTracker == null)
            return 0;

        ItemStack keyItem = itemStack.clone();
        keyItem.setAmount(1);
        int progress = 0;

        for (Map.Entry<List<ItemStack>, Integer> entry : this.itemsToSmelt.entrySet()) {
            if (!entry.getKey().contains(keyItem))
                continue;
            progress += smeltingTracker.getSmelts(entry.getKey());
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
        for (Map.Entry<SuperiorPlayer, SmeltingTracker> entry : entrySet()) {
            String uuid = entry.getKey().getUniqueId().toString();
            int index = 0;
            for (Map.Entry<ItemStack, Integer> smeltedEntry : entry.getValue().smeltItems.entrySet()) {
                section.set(uuid + "." + index + ".item", smeltedEntry.getKey());
                section.set(uuid + "." + index + ".amount", smeltedEntry.getValue());
                index++;
            }
        }
    }

    @Override
    public void loadProgress(ConfigurationSection section) {
        for (String uuid : section.getKeys(false)) {
            if (uuid.equals("players"))
                continue;

            SmeltingTracker smeltingTracker = new SmeltingTracker();
            UUID playerUUID = UUID.fromString(uuid);
            SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(playerUUID);

            insertData(superiorPlayer, smeltingTracker);

            for (String key : section.getConfigurationSection(uuid).getKeys(false)) {
                ItemStack itemStack = section.getItemStack(uuid + "." + key + ".item");
                int amount = section.getInt(uuid + "." + key + ".amount");
                smeltingTracker.smeltItems.put(itemStack, amount);
            }
        }
    }

    @Override
    public void formatItem(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        SmeltingTracker smeltingTracker = getOrCreate(superiorPlayer, s -> new SmeltingTracker());

        if(smeltingTracker == null)
            return;

        ItemMeta itemMeta = itemStack.getItemMeta();

        if (itemMeta.hasDisplayName())
            itemMeta.setDisplayName(parsePlaceholders(smeltingTracker, itemMeta.getDisplayName()));

        if (itemMeta.hasLore()) {
            List<String> lore = new ArrayList<>();
            for (String line : itemMeta.getLore())
                lore.add(parsePlaceholders(smeltingTracker, line));
            itemMeta.setLore(lore);
        }

        itemStack.setItemMeta(itemMeta);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getClickedInventory() == null || (e.getClickedInventory().getType() != InventoryType.FURNACE))
            return;

        int requiredSlot = 2;

        ItemStack resultItem = e.getCurrentItem().clone();
        resultItem.setAmount(1);

        boolean contains = false;
        outer:
        for (List<ItemStack> stacks : itemsToSmelt.keySet()) {
            for (ItemStack stack : stacks) {
                if (stack.getType() == resultItem.getType()) {
                    contains = true;
                    break outer;
                }
            }
        }
        if (!contains)
            return;

        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(e.getWhoClicked().getUniqueId());

        if (e.getRawSlot() == requiredSlot && superiorSkyblock.getMissions().canCompleteNoProgress(superiorPlayer, this)) {
            int amountOfResult = countItems(e.getWhoClicked(), resultItem);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                int afterTickAmountOfResult = countItems(e.getWhoClicked(), resultItem);
                resultItem.setAmount(afterTickAmountOfResult - amountOfResult);
                trackItem(superiorPlayer, resultItem);
            }, 1L);
        }

    }

    private void trackItem(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        SmeltingTracker blocksTracker = getOrCreate(superiorPlayer, s -> new SmeltingTracker());
        if (blocksTracker == null)
            return;

        blocksTracker.trackItem(itemStack);
        if (itemsBossBar.containsKey(itemStack.getType()))
            sendBossBar(superiorPlayer, itemsBossBar.get(itemStack.getType()), getProgress(superiorPlayer, itemStack), getRequired(itemStack), getProgress(superiorPlayer));

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

    private String parsePlaceholders(SmeltingTracker smeltingTracker, String line) {
        Matcher matcher = percentagePattern.matcher(line);

        if (matcher.matches()) {
            try {
                String requiredItem = matcher.group(2).toUpperCase();
                ItemStack itemStack = new ItemStack(Material.valueOf(requiredItem));
                Optional<Map.Entry<List<ItemStack>, Integer>> entry = itemsToSmelt.entrySet().stream()
                        .filter(e -> e.getKey().contains(itemStack)).findAny();

                if (entry.isPresent()) {
                    line = line.replace("{percentage_" + matcher.group(2) + "}",
                            "" + (smeltingTracker.getSmelts(entry.get().getKey()) * 100) / entry.get().getValue());
                }
            } catch (Exception ignored) {
            }
        }

        if ((matcher = valuePattern.matcher(line)).matches()) {
            try {
                String requiredBlock = matcher.group(2).toUpperCase();
                ItemStack itemStack = new ItemStack(Material.valueOf(requiredBlock));
                Optional<Map.Entry<List<ItemStack>, Integer>> entry = itemsToSmelt.entrySet().stream()
                        .filter(e -> e.getKey().contains(itemStack)).findAny();

                if (entry.isPresent()) {
                    line = line.replace("{value_" + matcher.group(2) + "}",
                            "" + (smeltingTracker.getSmelts(entry.get().getKey())));
                }
            } catch (Exception ignored) {
            }
        }

        return ChatColor.translateAlternateColorCodes('&', line);
    }

    public static class SmeltingTracker {

        private final Map<ItemStack, Integer> smeltItems = new HashMap<>();

        void trackItem(ItemStack itemStack) {
            ItemStack keyItem = itemStack.clone();
            keyItem.setAmount(1);
            smeltItems.put(keyItem, smeltItems.getOrDefault(keyItem, 0) + itemStack.getAmount());
        }

        int getSmelts(List<ItemStack> itemStacks) {
            int sold = 0;

            for (ItemStack itemStack : itemStacks) {
                sold += smeltItems.getOrDefault(itemStack, 0);
            }

            return sold;
        }
    }

}
