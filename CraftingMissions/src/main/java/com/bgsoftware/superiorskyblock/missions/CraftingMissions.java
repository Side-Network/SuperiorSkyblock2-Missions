package com.bgsoftware.superiorskyblock.missions;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblock;
import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.missions.Mission;
import com.bgsoftware.superiorskyblock.api.missions.MissionLoadException;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import haveric.recipeManager.api.events.RecipeManagerCraftEvent;
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
public final class CraftingMissions extends Mission<CraftingMissions.CraftingsTracker> implements Listener {

    private static final SuperiorSkyblock superiorSkyblock = SuperiorSkyblockAPI.getSuperiorSkyblock();

    private static final Pattern percentagePattern = Pattern.compile("(.*)\\{percentage_(.+?)}(.*)"),
            valuePattern = Pattern.compile("(.*)\\{value_(.+?)}(.*)");

    private final Map<ItemStack, Integer> itemsToCraft = new HashMap<>();
    private final Map<Material, String> itemsBossBar = new HashMap<>();

    private JavaPlugin plugin;

    @Override
    public void load(JavaPlugin plugin, ConfigurationSection section) throws MissionLoadException {
        this.plugin = plugin;

        if (!section.contains("craftings"))
            throw new MissionLoadException("You must have the \"craftings\" section in the config.");

        for (String key : section.getConfigurationSection("craftings").getKeys(false)) {
            String type = section.getString("craftings." + key + ".type");
            short data = (short) section.getInt("craftings." + key + ".data", 0);
            int amount = section.getInt("craftings." + key + ".amount", 1);
            String bossBar = section.getString("craftings." + key + ".boss-bar", "?");
            Material material;

            try {
                material = Material.valueOf(type);
            } catch (IllegalArgumentException ex) {
                throw new MissionLoadException("Invalid crafting result " + type + ".");
            }

            itemsToCraft.put(new ItemStack(material, 1, data), amount);
            itemsBossBar.put(material, bossBar);
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);

        setClearMethod(craftingsTracker -> craftingsTracker.craftedItems.clear());
    }

    @Override
    public double getProgress(SuperiorPlayer superiorPlayer) {
        CraftingsTracker craftingsTracker = get(superiorPlayer);

        if (craftingsTracker == null)
            return 0.0;

        int requiredItems = 0;
        int interactions = 0;

        for (Map.Entry<ItemStack, Integer> entry : this.itemsToCraft.entrySet()) {
            requiredItems += entry.getValue();
            interactions += Math.min(craftingsTracker.getCrafts(entry.getKey()), entry.getValue());
        }

        return (double) interactions / requiredItems;
    }

    @Override
    public int getProgressValue(SuperiorPlayer superiorPlayer) {
        CraftingsTracker craftingsTracker = get(superiorPlayer);

        if (craftingsTracker == null)
            return 0;

        int interactions = 0;

        for (Map.Entry<ItemStack, Integer> entry : this.itemsToCraft.entrySet())
            interactions += Math.min(craftingsTracker.getCrafts(entry.getKey()), entry.getValue());

        return interactions;
    }

    public int getRequired(ItemStack itemStack) {
        ItemStack keyItem = itemStack.clone();
        keyItem.setAmount(1);

        return this.itemsToCraft.getOrDefault(keyItem, 0);
    }

    public int getProgress(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        CraftingsTracker craftingsTracker = get(superiorPlayer);
        if (craftingsTracker == null)
            return 0;

        return craftingsTracker.getCrafts(itemStack);
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
        for (Map.Entry<SuperiorPlayer, CraftingsTracker> entry : entrySet()) {
            String uuid = entry.getKey().getUniqueId().toString();
            int index = 0;
            for (Map.Entry<ItemStack, Integer> craftedEntry : entry.getValue().craftedItems.entrySet()) {
                section.set(uuid + "." + index + ".item", craftedEntry.getKey());
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

            CraftingsTracker craftingsTracker = new CraftingsTracker();
            UUID playerUUID = UUID.fromString(uuid);
            SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(playerUUID);

            insertData(superiorPlayer, craftingsTracker);

            for (String key : section.getConfigurationSection(uuid).getKeys(false)) {
                ItemStack itemStack = section.getItemStack(uuid + "." + key + ".item");
                int amount = section.getInt(uuid + "." + key + ".amount");
                craftingsTracker.craftedItems.put(itemStack, amount);
            }
        }
    }

    @Override
    public void formatItem(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        CraftingsTracker craftingsTracker = getOrCreate(superiorPlayer, s -> new CraftingsTracker());

        if(craftingsTracker == null)
            return;

        ItemMeta itemMeta = itemStack.getItemMeta();

        if (itemMeta.hasDisplayName())
            itemMeta.setDisplayName(parsePlaceholders(craftingsTracker, itemMeta.getDisplayName()));

        if (itemMeta.hasLore()) {
            List<String> lore = new ArrayList<>();
            for (String line : itemMeta.getLore())
                lore.add(parsePlaceholders(craftingsTracker, line));
            itemMeta.setLore(lore);
        }

        itemStack.setItemMeta(itemMeta);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getClickedInventory() == null || (e.getClickedInventory().getType() != InventoryType.WORKBENCH &&
                e.getClickedInventory().getType() != InventoryType.CRAFTING && e.getClickedInventory().getType() != InventoryType.FURNACE))
            return;

        int requiredSlot = e.getClickedInventory().getType() == InventoryType.FURNACE ? 2 : 0;

        ItemStack resultItem = e.getCurrentItem().clone();
        resultItem.setAmount(1);

        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(e.getWhoClicked().getUniqueId());

        if (e.getRawSlot() == requiredSlot && itemsToCraft.containsKey(resultItem) &&
                superiorSkyblock.getMissions().canCompleteNoProgress(superiorPlayer, this)) {
            int amountOfResult = countItems(e.getWhoClicked(), resultItem);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                int afterTickAmountOfResult = countItems(e.getWhoClicked(), resultItem);
                resultItem.setAmount(afterTickAmountOfResult - amountOfResult);
                trackItem(superiorPlayer, resultItem);
            }, 1L);
        }

    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCustomCraft(RecipeManagerCraftEvent e) {
        if (e.getResult() == null || e.getResult().getType() == Material.AIR)
            return;

        ItemStack resultItem = new ItemStack(
                e.getRecipe().getResults().stream()
                        .filter(i -> i.getType() == e.getResult().getType())
                        .findFirst()
                        .orElse(e.getRecipe().getFirstResult())
        );

        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(e.getPlayer().getUniqueId());

        if (itemsToCraft.containsKey(resultItem) &&
                superiorSkyblock.getMissions().canCompleteNoProgress(superiorPlayer, this)) {
            int amountOfResult = countSimpleItems(e.getPlayer(), resultItem);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                int afterTickAmountOfResult = countSimpleItems(e.getPlayer(), resultItem);
                resultItem.setAmount(afterTickAmountOfResult - amountOfResult);
                trackItem(superiorPlayer, resultItem);
            }, 1L);
        }
    }

    private void trackItem(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        CraftingsTracker blocksTracker = getOrCreate(superiorPlayer, s -> new CraftingsTracker());
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

        if (itemStack.isSimilar(humanEntity.getItemOnCursor()))
            amount += humanEntity.getItemOnCursor().getAmount();

        return amount;
    }

    private static int countSimpleItems(HumanEntity humanEntity, ItemStack itemStack) {
        int amount = 0;

        if (itemStack == null)
            return amount;

        PlayerInventory playerInventory = humanEntity.getInventory();

        for (ItemStack invItem : playerInventory.getContents()) {
            if (invItem != null && itemStack.getType() == invItem.getType())
                amount += invItem.getAmount();
        }

        if (itemStack.getType() == humanEntity.getItemOnCursor().getType())
            amount += humanEntity.getItemOnCursor().getAmount();

        return amount;
    }

    private String parsePlaceholders(CraftingsTracker entityTracker, String line) {
        Matcher matcher = percentagePattern.matcher(line);

        if (matcher.matches()) {
            try {
                String requiredBlock = matcher.group(2).toUpperCase();
                ItemStack itemStack = new ItemStack(Material.valueOf(requiredBlock));
                Optional<Map.Entry<ItemStack, Integer>> entry = itemsToCraft.entrySet().stream().filter(e -> e.getKey().isSimilar(itemStack)).findAny();
                if (entry.isPresent()) {
                    line = line.replace("{percentage_" + matcher.group(2) + "}",
                            "" + (entityTracker.getCrafts(itemStack) * 100) / entry.get().getValue());
                }
            } catch (Exception ignored) {
            }
        }

        if ((matcher = valuePattern.matcher(line)).matches()) {
            try {
                String requiredBlock = matcher.group(2).toUpperCase();
                ItemStack itemStack = new ItemStack(Material.valueOf(requiredBlock));
                Optional<Map.Entry<ItemStack, Integer>> entry = itemsToCraft.entrySet().stream().filter(e -> e.getKey().isSimilar(itemStack)).findAny();
                if (entry.isPresent()) {
                    line = line.replace("{value_" + matcher.group(2) + "}",
                            "" + (entityTracker.getCrafts(itemStack)));
                }
            } catch (Exception ignored) {
            }
        }

        return ChatColor.translateAlternateColorCodes('&', line);
    }

    public static class CraftingsTracker {

        private final Map<ItemStack, Integer> craftedItems = new HashMap<>();

        void trackItem(ItemStack itemStack) {
            ItemStack keyItem = itemStack.clone();
            keyItem.setAmount(1);
            craftedItems.put(keyItem, craftedItems.getOrDefault(keyItem, 0) + itemStack.getAmount());
        }

        int getCrafts(ItemStack itemStack) {
            ItemStack keyItem = itemStack.clone();
            keyItem.setAmount(1);
            return craftedItems.getOrDefault(keyItem, 0);
        }

    }

}
