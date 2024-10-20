package com.bgsoftware.superiorskyblock.missions;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblock;
import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.missions.Mission;
import com.bgsoftware.superiorskyblock.api.missions.MissionLoadException;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import lv.side.sidecrops.events.CropHarvesterEvent;
import lv.side.sidecrops.managers.CropManager;
import lv.side.sidecrops.objects.CropType;
import net.brcdev.shopgui.event.ShopPostTransactionEvent;
import net.brcdev.shopgui.shop.ShopManager;
import net.brcdev.shopgui.shop.ShopTransactionResult;
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
public final class SellMissions extends Mission<SellMissions.SellTracker> implements Listener {

    private static final SuperiorSkyblock superiorSkyblock = SuperiorSkyblockAPI.getSuperiorSkyblock();

    private static final Pattern percentagePattern = Pattern.compile("(.*)\\{percentage_(.+?)}(.*)"),
            valuePattern = Pattern.compile("(.*)\\{value_(.+?)}(.*)");

    private final Map<List<ItemStack>, Integer> itemsToSell = new HashMap<>();
    private final Map<List<String>, Integer> customItemsToSell = new HashMap<>();
    private final Map<Material, String> itemsBossBar = new HashMap<>();
    private final Map<String, String> customItemsBossBar = new HashMap<>();

    private JavaPlugin plugin;

    @Override
    public void load(JavaPlugin plugin, ConfigurationSection section) throws MissionLoadException {
        this.plugin = plugin;

        if (!section.contains("sell-items"))
            throw new MissionLoadException("You must have the \"sell-items\" section in the config.");

        for (String key : section.getConfigurationSection("sell-items").getKeys(false)) {
            List<String> itemTypes = section.getStringList("sell-items." + key + ".types");
            int amount = section.getInt("sell-items." + key + ".amount", 1);

            List<ItemStack> itemsToSell = new ArrayList<>();
            List<String> customItemsToSell = new ArrayList<>();

            for(String itemType : itemTypes) {
                byte data = 0;

                if(itemType.contains(":")) {
                    String[] sections = itemType.split(":");
                    itemType = sections[0];
                    try {
                        data = sections.length == 2 ? Byte.parseByte(sections[1]) : 0;
                    } catch (NumberFormatException ex) {
                        throw new MissionLoadException("Invalid sell item data " + sections[1] + ".");
                    }
                }

                if (!itemType.contains("-")) {
                    Material material;

                    try {
                        material = Material.valueOf(itemType);
                    } catch (IllegalArgumentException ex) {
                        throw new MissionLoadException("Invalid sell item " + itemType + ".");
                    }

                    itemsToSell.add(new ItemStack(material, 1, data));
                } else {
                    customItemsToSell.add(itemType);
                }
            }

            this.itemsToSell.put(itemsToSell, amount);
            this.customItemsToSell.put(customItemsToSell, amount);
            String bossBar = section.getString("sell-items." + key + ".boss-bar", "?");
            for (ItemStack toSell : itemsToSell) {
                itemsBossBar.put(toSell.getType(), bossBar);
            }
            for (String toSell : customItemsToSell) {
                customItemsBossBar.put(toSell, bossBar);
            }
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);

        setClearMethod(sellTracker -> sellTracker.soldItems.clear());
    }

    @Override
    public double getProgress(SuperiorPlayer superiorPlayer) {
        SellTracker sellTracker = get(superiorPlayer);

        if (sellTracker == null)
            return 0.0;

        int requiredItems = 0;
        int interactions = 0;

        for (Map.Entry<List<ItemStack>, Integer> entry : this.itemsToSell.entrySet()) {
            if (entry.getKey().isEmpty())
                continue;
            requiredItems += entry.getValue();
            interactions += Math.min(sellTracker.getSold(entry.getKey()), entry.getValue());
        }
        for (Map.Entry<List<String>, Integer> entry : this.customItemsToSell.entrySet()) {
            if (entry.getKey().isEmpty())
                continue;
            requiredItems += entry.getValue();
            interactions += Math.min(sellTracker.getCustomSold(entry.getKey()), entry.getValue());
        }

        return (double) interactions / requiredItems;
    }

    @Override
    public int getProgressValue(SuperiorPlayer superiorPlayer) {
        SellTracker sellTracker = get(superiorPlayer);

        if (sellTracker == null)
            return 0;

        int interactions = 0;

        for (Map.Entry<List<ItemStack>, Integer> entry : this.itemsToSell.entrySet()) {
            if (entry.getKey().isEmpty())
                continue;
            interactions += Math.min(sellTracker.getSold(entry.getKey()), entry.getValue());
        }

        for (Map.Entry<List<String>, Integer> entry : this.customItemsToSell.entrySet()) {
            if (entry.getKey().isEmpty())
                continue;
            interactions += Math.min(sellTracker.getCustomSold(entry.getKey()), entry.getValue());
        }

        return interactions;
    }

    public int getRequired(ItemStack itemStack) {
        ItemStack keyItem = itemStack.clone();
        keyItem.setAmount(1);

        for (Map.Entry<List<ItemStack>, Integer> entry : this.itemsToSell.entrySet()) {
            if (entry.getKey().contains(keyItem))
                return entry.getValue();
        }

        return 0;
    }

    public int getRequired(String item) {
        for (Map.Entry<List<String>, Integer> entry : this.customItemsToSell.entrySet()) {
            if (entry.getKey().contains(item))
                return entry.getValue();
        }

        return 0;
    }

    public int getProgress(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        SellTracker sellTracker = get(superiorPlayer);
        if (sellTracker == null)
            return 0;

        ItemStack keyItem = itemStack.clone();
        keyItem.setAmount(1);
        int progress = 0;

        for (Map.Entry<List<ItemStack>, Integer> entry : this.itemsToSell.entrySet()) {
            if (!entry.getKey().contains(keyItem))
                continue;
            progress += sellTracker.getSold(entry.getKey());
        }

        return progress;
    }

    public int getProgress(SuperiorPlayer superiorPlayer, String item) {
        SellTracker sellTracker = get(superiorPlayer);
        if (sellTracker == null)
            return 0;

        int progress = 0;

        for (Map.Entry<List<String>, Integer> entry : this.customItemsToSell.entrySet()) {
            if (!entry.getKey().contains(item))
                continue;
            progress += sellTracker.getCustomSold(entry.getKey());
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
        for (Map.Entry<SuperiorPlayer, SellTracker> entry : entrySet()) {
            String uuid = entry.getKey().getUniqueId().toString();
            int index = 0;
            for (Map.Entry<ItemStack, Integer> craftedEntry : entry.getValue().soldItems.entrySet()) {
                section.set(uuid + "." + index + ".item", craftedEntry.getKey());
                section.set(uuid + "." + index + ".amount", craftedEntry.getValue());
                index++;
            }
            for (Map.Entry<String, Integer> craftedEntry : entry.getValue().soldCustomItems.entrySet()) {
                section.set(uuid + "." + index + ".custom-item", craftedEntry.getKey());
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

            SellTracker sellTracker = new SellTracker();
            UUID playerUUID = UUID.fromString(uuid);
            SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(playerUUID);

            insertData(superiorPlayer, sellTracker);

            for (String key : section.getConfigurationSection(uuid).getKeys(false)) {
                if (section.contains(uuid + "." + key + ".custom-item")) {
                    String item = section.getString(uuid + "." + key + ".custom-item");
                    int amount = section.getInt(uuid + "." + key + ".amount");
                    sellTracker.soldCustomItems.put(item, amount);
                } else {
                    ItemStack itemStack = section.getItemStack(uuid + "." + key + ".item");
                    int amount = section.getInt(uuid + "." + key + ".amount");
                    sellTracker.soldItems.put(itemStack, amount);
                }
            }
        }
    }

    @Override
    public void formatItem(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        SellTracker sellTracker = getOrCreate(superiorPlayer, s -> new SellTracker());

        if(sellTracker == null)
            return;

        ItemMeta itemMeta = itemStack.getItemMeta();

        if (itemMeta.hasDisplayName())
            itemMeta.setDisplayName(parsePlaceholders(sellTracker, itemMeta.getDisplayName()));

        if (itemMeta.hasLore()) {
            List<String> lore = new ArrayList<>();
            for (String line : itemMeta.getLore())
                lore.add(parsePlaceholders(sellTracker, line));
            itemMeta.setLore(lore);
        }

        itemStack.setItemMeta(itemMeta);
    }

    @EventHandler
    public void onSellItem(ShopPostTransactionEvent event) {
        if (event.getResult().getResult() != ShopTransactionResult.ShopTransactionResultType.SUCCESS)
            return;
        if (event.getResult().getShopAction() == ShopManager.ShopAction.BUY)
            return;

        ItemStack resultItem = event.getResult().getShopItem().getItem();
        resultItem.setAmount(event.getResult().getAmount());

        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(event.getResult().getPlayer());
        if (!superiorSkyblock.getMissions().canCompleteNoProgress(superiorPlayer, this))
            return;

        if (resultItem.getType() == Material.PHANTOM_MEMBRANE) {
            for (CropType cropType : CropManager.get().getCropTypes().values()) {
                for (Map.Entry<Integer, ItemStack> entry : cropType.getProduce().entrySet()) {
                    if (entry.getValue().getItemMeta().getDisplayName().equalsIgnoreCase(resultItem.getItemMeta().getDisplayName())) {
                        String itemName = cropType.getId() + "-" + entry.getKey();

                        boolean contains = false;
                        outer:
                        for (List<String> stacks : customItemsToSell.keySet()) {
                            for (String stack : stacks) {
                                if (stack.equalsIgnoreCase(itemName)) {
                                    contains = true;
                                    break outer;
                                }
                            }
                        }
                        if (!contains)
                            return;

                        trackItem(superiorPlayer, itemName, resultItem.getAmount());
                        return;
                    }
                }
            }
            return;
        } else {
            boolean contains = false;
            outer:
            for (List<ItemStack> stacks : itemsToSell.keySet()) {
                for (ItemStack stack : stacks) {
                    if (stack.getType() == resultItem.getType()) {
                        contains = true;
                        break outer;
                    }
                }
            }
            if (!contains)
                return;
        }

        trackItem(superiorPlayer, resultItem);
    }

    @EventHandler(ignoreCancelled = true)
    public void onScytherAutoSell(CropHarvesterEvent event) {
        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(event.getPlayer());
        if (!superiorSkyblock.getMissions().canCompleteNoProgress(superiorPlayer, this))
            return;

        Material mat = Material.getMaterial(event.getProduce());
        if (mat != null) {
            trackItem(superiorPlayer, new ItemStack(mat, event.getAmount()));
            return;
        }

        trackItem(superiorPlayer, event.getProduce(), event.getAmount());
    }

    private void trackItem(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        SellTracker sellTracker = getOrCreate(superiorPlayer, s -> new SellTracker());
        if (sellTracker == null)
            return;

        sellTracker.trackItem(itemStack);
        if (itemsBossBar.containsKey(itemStack.getType()))
            sendBossBar(superiorPlayer, itemsBossBar.get(itemStack.getType()), getProgress(superiorPlayer, itemStack), getRequired(itemStack), getProgress(superiorPlayer));

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> superiorPlayer.runIfOnline(player -> {
            if (canComplete(superiorPlayer))
                SuperiorSkyblockAPI.getSuperiorSkyblock().getMissions().rewardMission(this, superiorPlayer, true);
        }), 2L);
    }

    private void trackItem(SuperiorPlayer superiorPlayer, String item, int amount) {
        SellTracker sellTracker = getOrCreate(superiorPlayer, s -> new SellTracker());
        if (sellTracker == null)
            return;

        sellTracker.trackItem(item, amount);
        if (customItemsBossBar.containsKey(item))
            sendBossBar(superiorPlayer, customItemsBossBar.get(item), getProgress(superiorPlayer, item), getRequired(item), getProgress(superiorPlayer));

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> superiorPlayer.runIfOnline(player -> {
            if (canComplete(superiorPlayer))
                SuperiorSkyblockAPI.getSuperiorSkyblock().getMissions().rewardMission(this, superiorPlayer, true);
        }), 2L);
    }

    private String parsePlaceholders(SellTracker sellTracker, String line) {
        Matcher matcher = percentagePattern.matcher(line);

        if (matcher.matches()) {
            try {
                if (matcher.group(2).contains("-")) {
                    String requiredItem = matcher.group(2);
                    Optional<Map.Entry<List<String>, Integer>> entry = customItemsToSell.entrySet().stream()
                            .filter(e -> e.getKey().contains(requiredItem)).findAny();

                    if (entry.isPresent()) {
                        line = line.replace("{percentage_" + matcher.group(2) + "}",
                                "" + (sellTracker.getCustomSold(entry.get().getKey()) * 100) / entry.get().getValue());
                    }
                } else {
                    String requiredItem = matcher.group(2).toUpperCase();
                    ItemStack itemStack = new ItemStack(Material.valueOf(requiredItem));
                    Optional<Map.Entry<List<ItemStack>, Integer>> entry = itemsToSell.entrySet().stream()
                            .filter(e -> e.getKey().contains(itemStack)).findAny();

                    if (entry.isPresent()) {
                        line = line.replace("{percentage_" + matcher.group(2) + "}",
                                "" + (sellTracker.getSold(entry.get().getKey()) * 100) / entry.get().getValue());
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if ((matcher = valuePattern.matcher(line)).matches()) {
            try {
                if (matcher.group(2).contains("-")) {
                    String requiredBlock = matcher.group(2);
                    Optional<Map.Entry<List<String>, Integer>> entry = customItemsToSell.entrySet().stream()
                            .filter(e -> e.getKey().contains(requiredBlock)).findAny();

                    if (entry.isPresent()) {
                        line = line.replace("{value_" + matcher.group(2) + "}",
                                "" + (sellTracker.getCustomSold(entry.get().getKey())));
                    }
                } else {
                    String requiredBlock = matcher.group(2).toUpperCase();
                    ItemStack itemStack = new ItemStack(Material.valueOf(requiredBlock));
                    Optional<Map.Entry<List<ItemStack>, Integer>> entry = itemsToSell.entrySet().stream()
                            .filter(e -> e.getKey().contains(itemStack)).findAny();

                    if (entry.isPresent()) {
                        line = line.replace("{value_" + matcher.group(2) + "}",
                                "" + (sellTracker.getSold(entry.get().getKey())));
                    }
                }
            } catch (Exception ignored) {
            }
        }

        return ChatColor.translateAlternateColorCodes('&', line);
    }

    public static class SellTracker {

        private final Map<ItemStack, Integer> soldItems = new HashMap<>();
        private final Map<String, Integer> soldCustomItems = new HashMap<>();

        void trackItem(ItemStack itemStack) {
            ItemStack keyItem = itemStack.clone();
            keyItem.setAmount(1);
            soldItems.put(keyItem, soldItems.getOrDefault(keyItem, 0) + itemStack.getAmount());
        }

        void trackItem(String item, int amount) {
            soldCustomItems.put(item, soldCustomItems.getOrDefault(item, 0) + amount);
        }

        int getSold(List<ItemStack> itemStacks) {
            int sold = 0;

            for (ItemStack itemStack : itemStacks) {
                sold += soldItems.getOrDefault(itemStack, 0);
            }

            return sold;
        }

        int getCustomSold(List<String> itemStacks) {
            int sold = 0;

            for (String itemStack : itemStacks) {
                sold += soldCustomItems.getOrDefault(itemStack, 0);
            }

            return sold;
        }

    }

}
