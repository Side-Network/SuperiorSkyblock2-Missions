package com.bgsoftware.superiorskyblock.missions;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblock;
import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.missions.Mission;
import com.bgsoftware.superiorskyblock.api.missions.MissionLoadException;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
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
public final class BuyMissions extends Mission<BuyMissions.BuyTracker> implements Listener {

    private static final SuperiorSkyblock superiorSkyblock = SuperiorSkyblockAPI.getSuperiorSkyblock();

    private static final Pattern percentagePattern = Pattern.compile("(.*)\\{percentage_(.+?)}(.*)"),
            valuePattern = Pattern.compile("(.*)\\{value_(.+?)}(.*)"),
            requiredPattern = Pattern.compile("(.*)\\{required_(.+?)}(.*)");

    private final Map<ItemStack, Integer> itemsToBuy = new HashMap<>();
    private final Map<Material, String> itemsBossBar = new HashMap<>();

    private JavaPlugin plugin;

    @Override
    public void load(JavaPlugin plugin, ConfigurationSection section) throws MissionLoadException {
        this.plugin = plugin;

        if (!section.contains("buy-items"))
            throw new MissionLoadException("You must have the \"buy-items\" section in the config.");

        for (String key : section.getConfigurationSection("buy-items").getKeys(false)) {
            String type = section.getString("buy-items." + key + ".type");
            short data = (short) section.getInt("buy-items." + key + ".data", 0);
            int amount = section.getInt("buy-items." + key + ".amount", 1);
            String bossBar = section.getString("buy-items." + key + ".boss-bar", "?");
            Material material;

            try {
                material = Material.valueOf(type);
            } catch (IllegalArgumentException ex) {
                throw new MissionLoadException("Invalid buy item " + type + ".");
            }

            itemsToBuy.put(new ItemStack(material, 1, data), amount);
            itemsBossBar.put(material, bossBar);
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);

        setClearMethod(buyTracker -> buyTracker.boughtItems.clear());
    }

    @Override
    public double getProgress(SuperiorPlayer superiorPlayer) {
        BuyTracker buyTracker = get(superiorPlayer);

        if (buyTracker == null)
            return 0.0;

        double multiplier = getPeakMemberMultiplier(superiorPlayer);
        int requiredItems = 0;
        int interactions = 0;

        for (Map.Entry<ItemStack, Integer> entry : this.itemsToBuy.entrySet()) {
            int scaledRequired = (int) Math.ceil(entry.getValue() * multiplier);
            requiredItems += scaledRequired;
            interactions += Math.min(buyTracker.getBought(entry.getKey()), scaledRequired);
        }

        return (double) interactions / requiredItems;
    }

    @Override
    public int getProgressValue(SuperiorPlayer superiorPlayer) {
        BuyTracker buyTracker = get(superiorPlayer);

        if (buyTracker == null)
            return 0;

        int interactions = 0;

        double multiplier = getPeakMemberMultiplier(superiorPlayer);
        for (Map.Entry<ItemStack, Integer> entry : this.itemsToBuy.entrySet())
            interactions += Math.min(buyTracker.getBought(entry.getKey()), (int) Math.ceil(entry.getValue() * multiplier));

        return interactions;
    }

    public int getRequired(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        double multiplier = getPeakMemberMultiplier(superiorPlayer);
        ItemStack keyItem = itemStack.clone();
        keyItem.setItemMeta(null);
        keyItem.setAmount(1);

        int required = this.itemsToBuy.getOrDefault(keyItem, 0);
        return required > 0 ? (int) Math.ceil(required * multiplier) : 0;
    }

    public int getProgress(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        BuyTracker buyTracker = get(superiorPlayer);
        if (buyTracker == null)
            return 0;

        int scaledRequired = getRequired(superiorPlayer, itemStack);
        return Math.min(buyTracker.getBought(itemStack), scaledRequired);
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
        for (Map.Entry<SuperiorPlayer, BuyTracker> entry : entrySet()) {
            String uuid = entry.getKey().getUniqueId().toString();
            int index = 0;
            for (Map.Entry<ItemStack, Integer> craftedEntry : entry.getValue().boughtItems.entrySet()) {
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

            BuyTracker buyTracker = new BuyTracker();
            UUID playerUUID = UUID.fromString(uuid);
            SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(playerUUID);

            insertData(superiorPlayer, buyTracker);

            for (String key : section.getConfigurationSection(uuid).getKeys(false)) {
                ItemStack itemStack = section.getItemStack(uuid + "." + key + ".item");
                int amount = section.getInt(uuid + "." + key + ".amount");
                buyTracker.boughtItems.put(itemStack, amount);
            }
        }
    }

    @Override
    public void formatItem(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        BuyTracker buyTracker = getOrCreate(superiorPlayer, s -> new BuyTracker());

        if(buyTracker == null)
            return;

        ItemMeta itemMeta = itemStack.getItemMeta();

        if (itemMeta.hasDisplayName())
            itemMeta.setDisplayName(parsePlaceholders(superiorPlayer, buyTracker, itemMeta.getDisplayName()));

        if (itemMeta.hasLore()) {
            List<String> lore = new ArrayList<>();
            for (String line : itemMeta.getLore())
                lore.add(parsePlaceholders(superiorPlayer, buyTracker, line));
            itemMeta.setLore(lore);
        }

        itemStack.setItemMeta(itemMeta);
    }

    @EventHandler
    public void onBuyItem(ShopPostTransactionEvent event) {
        if (event.getResult().getResult() != ShopTransactionResult.ShopTransactionResultType.SUCCESS)
            return;
        if (event.getResult().getShopAction() != ShopManager.ShopAction.BUY)
            return;

        ItemStack resultItem = event.getResult().getShopItem().getItem();
        resultItem.setAmount(event.getResult().getAmount());

        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(event.getResult().getPlayer());
        if (!superiorSkyblock.getMissions().canCompleteNoProgress(superiorPlayer, this))
            return;

        trackItem(superiorPlayer, resultItem);
    }

    private void trackItem(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        BuyTracker buyTracker = getOrCreate(superiorPlayer, s -> new BuyTracker());
        if (buyTracker == null)
            return;

        buyTracker.trackItem(itemStack);
        if (itemsBossBar.containsKey(itemStack.getType()))
            sendBossBar(superiorPlayer, itemsBossBar.get(itemStack.getType()), getProgress(superiorPlayer, itemStack), getRequired(superiorPlayer, itemStack), getProgress(superiorPlayer));

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> superiorPlayer.runIfOnline(player -> {
            if (canComplete(superiorPlayer))
                SuperiorSkyblockAPI.getSuperiorSkyblock().getMissions().rewardMission(this, superiorPlayer, true);
        }), 2L);
    }

    private String parsePlaceholders(SuperiorPlayer superiorPlayer, BuyTracker buyTracker, String line) {
        Matcher matcher = percentagePattern.matcher(line);
        double multiplier = getPeakMemberMultiplier(superiorPlayer);

        if (matcher.matches()) {
            try {
                String requiredItem = matcher.group(2).toUpperCase();
                ItemStack itemStack = new ItemStack(Material.valueOf(requiredItem));
                Optional<Map.Entry<ItemStack, Integer>> entry = itemsToBuy.entrySet().stream().filter(e -> e.getKey().isSimilar(itemStack)).findAny();
                if (entry.isPresent()) {
                    int scaledRequired = (int) Math.ceil(entry.get().getValue() * multiplier);
                    line = line.replace("{percentage_" + matcher.group(2) + "}",
                            "" + (buyTracker.getBought(itemStack) * 100) / scaledRequired);
                }
            } catch (Exception ignored) {
            }
        }

        if ((matcher = valuePattern.matcher(line)).matches()) {
            try {
                String requiredBlock = matcher.group(2).toUpperCase();
                ItemStack itemStack = new ItemStack(Material.valueOf(requiredBlock));
                Optional<Map.Entry<ItemStack, Integer>> entry = itemsToBuy.entrySet().stream().filter(e -> e.getKey().isSimilar(itemStack)).findAny();
                if (entry.isPresent()) {
                    line = line.replace("{value_" + matcher.group(2) + "}",
                            "" + (buyTracker.getBought(itemStack)));
                }
            } catch (Exception ignored) {
            }
        }

        if ((matcher = requiredPattern.matcher(line)).matches()) {
            try {
                String requiredBlock = matcher.group(2).toUpperCase();
                ItemStack itemStack = new ItemStack(Material.valueOf(requiredBlock));
                Optional<Map.Entry<ItemStack, Integer>> entry = itemsToBuy.entrySet().stream().filter(e -> e.getKey().isSimilar(itemStack)).findAny();
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

    public static class BuyTracker {

        private final Map<ItemStack, Integer> boughtItems = new HashMap<>();

        void trackItem(ItemStack itemStack) {
            ItemStack keyItem = itemStack.clone();
            keyItem.setItemMeta(null);
            keyItem.setAmount(1);
            boughtItems.put(keyItem, boughtItems.getOrDefault(keyItem, 0) + itemStack.getAmount());
        }

        int getBought(ItemStack itemStack) {
            ItemStack keyItem = itemStack.clone();
            keyItem.setItemMeta(null);
            keyItem.setAmount(1);
            return boughtItems.getOrDefault(keyItem, 0);
        }

    }

}
