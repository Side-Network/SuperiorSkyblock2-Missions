package com.bgsoftware.superiorskyblock.missions;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblock;
import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.missions.Mission;
import com.bgsoftware.superiorskyblock.api.missions.MissionLoadException;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.nisovin.shopkeepers.api.events.ShopkeeperTradeCompletedEvent;
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
public final class ShopkeeperMissions extends Mission<ShopkeeperMissions.TradeTracker> implements Listener {

    private static final SuperiorSkyblock superiorSkyblock = SuperiorSkyblockAPI.getSuperiorSkyblock();

    private static final Pattern percentagePattern = Pattern.compile("(.*)\\{percentage_(.+?)}(.*)"),
            valuePattern = Pattern.compile("(.*)\\{value_(.+?)}(.*)");

    private final Map<Material, Integer> itemsToTrade = new HashMap<>();
    private final Map<Material, String> itemsBossBar = new HashMap<>();

    private JavaPlugin plugin;

    @Override
    public void load(JavaPlugin plugin, ConfigurationSection section) throws MissionLoadException {
        this.plugin = plugin;

        if (!section.contains("trade-items"))
            throw new MissionLoadException("You must have the \"trade-items\" section in the config.");

        for (String key : section.getConfigurationSection("trade-items").getKeys(false)) {
            String type = section.getString("trade-items." + key + ".type");
            int amount = section.getInt("trade-items." + key + ".amount", 1);
            String bossBar = section.getString("trade-items." + key + ".boss-bar", "?");
            Material material;

            try {
                material = Material.valueOf(type);
            } catch (IllegalArgumentException ex) {
                throw new MissionLoadException("Invalid trade item " + type + ".");
            }

            itemsToTrade.put(material, amount);
            itemsBossBar.put(material, bossBar);
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);

        setClearMethod(tradeTracker -> tradeTracker.tradedItems.clear());
    }

    @Override
    public double getProgress(SuperiorPlayer superiorPlayer) {
        TradeTracker tradeTracker = get(superiorPlayer);

        if (tradeTracker == null)
            return 0.0;

        int requiredItems = 0;
        int interactions = 0;

        for (Map.Entry<Material, Integer> entry : this.itemsToTrade.entrySet()) {
            requiredItems += entry.getValue();
            interactions += Math.min(tradeTracker.getBroken(entry.getKey()), entry.getValue());
        }

        return (double) interactions / requiredItems;
    }

    @Override
    public int getProgressValue(SuperiorPlayer superiorPlayer) {
        TradeTracker tradeTracker = get(superiorPlayer);

        if (tradeTracker == null)
            return 0;

        int interactions = 0;

        for (Map.Entry<Material, Integer> entry : this.itemsToTrade.entrySet())
            interactions += Math.min(tradeTracker.getBroken(entry.getKey()), entry.getValue());

        return interactions;
    }

    public int getRequired(Material material) {
        return this.itemsToTrade.getOrDefault(material, 0);
    }

    public int getProgress(SuperiorPlayer superiorPlayer, Material material) {
        TradeTracker tradeTracker = get(superiorPlayer);
        if (tradeTracker == null)
            return 0;

        return tradeTracker.getBroken(material);
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
        for (Map.Entry<SuperiorPlayer, TradeTracker> entry : entrySet()) {
            String uuid = entry.getKey().getUniqueId().toString();
            int index = 0;
            for (Map.Entry<Material, Integer> craftedEntry : entry.getValue().tradedItems.entrySet()) {
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

            TradeTracker tradeTracker = new TradeTracker();
            UUID playerUUID = UUID.fromString(uuid);
            SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(playerUUID);

            insertData(superiorPlayer, tradeTracker);

            for (String key : section.getConfigurationSection(uuid).getKeys(false)) {
                Material material = Material.valueOf(section.getString(uuid + "." + key + ".type"));
                int amount = section.getInt(uuid + "." + key + ".amount");
                tradeTracker.tradedItems.put(material, amount);
            }
        }
    }

    @Override
    public void formatItem(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        TradeTracker tradeTracker = getOrCreate(superiorPlayer, s -> new TradeTracker());

        if(tradeTracker == null)
            return;

        ItemMeta itemMeta = itemStack.getItemMeta();

        if (itemMeta.hasDisplayName())
            itemMeta.setDisplayName(parsePlaceholders(tradeTracker, itemMeta.getDisplayName()));

        if (itemMeta.hasLore()) {
            List<String> lore = new ArrayList<>();
            for (String line : itemMeta.getLore())
                lore.add(parsePlaceholders(tradeTracker, line));
            itemMeta.setLore(lore);
        }

        itemStack.setItemMeta(itemMeta);
    }

    @EventHandler
    public void onShopkeeperTrade(ShopkeeperTradeCompletedEvent event) {
        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(event.getCompletedTrade().getPlayer());
        if (!superiorSkyblock.getMissions().canCompleteNoProgress(superiorPlayer, this))
            return;

        trackItem(superiorPlayer, new ItemStack(event.getCompletedTrade().getTradingRecipe().getResultItem().getType(), event.getCompletedTrade().getTradingRecipe().getResultItem().getAmount()));
    }

    private void trackItem(SuperiorPlayer superiorPlayer, ItemStack item) {
        TradeTracker tradeTracker = getOrCreate(superiorPlayer, s -> new TradeTracker());
        if (tradeTracker == null)
            return;

        tradeTracker.trackMaterial(item.getType(), item.getAmount());
        if (itemsBossBar.containsKey(item.getType()))
            sendBossBar(superiorPlayer, itemsBossBar.get(item.getType()), getProgress(superiorPlayer, item.getType()), getRequired(item.getType()), getProgress(superiorPlayer));

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> superiorPlayer.runIfOnline(player -> {
            if (canComplete(superiorPlayer))
                SuperiorSkyblockAPI.getSuperiorSkyblock().getMissions().rewardMission(this, superiorPlayer, true);
        }), 2L);
    }

    private String parsePlaceholders(TradeTracker tradeTracker, String line) {
        Matcher matcher = percentagePattern.matcher(line);

        if (matcher.matches()) {
            try {
                String requiredMaterial = matcher.group(2).toUpperCase();
                Material material = Material.valueOf(requiredMaterial);
                Optional<Map.Entry<Material, Integer>> entry = itemsToTrade.entrySet().stream().filter(e -> e.getKey() == material).findAny();
                if (entry.isPresent()) {
                    line = line.replace("{percentage_" + matcher.group(2) + "}",
                            "" + (tradeTracker.getBroken(material) * 100) / entry.get().getValue());
                }
            } catch (Exception ignored) {
            }
        }

        if ((matcher = valuePattern.matcher(line)).matches()) {
            try {
                String requiredMaterial = matcher.group(2).toUpperCase();
                Material material = Material.valueOf(requiredMaterial);
                Optional<Map.Entry<Material, Integer>> entry = itemsToTrade.entrySet().stream().filter(e -> e.getKey() == material).findAny();
                if (entry.isPresent()) {
                    line = line.replace("{value_" + matcher.group(2) + "}",
                            "" + (tradeTracker.getBroken(material)));
                }
            } catch (Exception ignored) {
            }
        }

        return ChatColor.translateAlternateColorCodes('&', line);
    }

    public static class TradeTracker {

        private final Map<Material, Integer> tradedItems = new HashMap<>();

        void trackMaterial(Material material, int quantity) {
            tradedItems.put(material, tradedItems.getOrDefault(material, 0) + quantity);
        }

        int getBroken(Material material) {
            return tradedItems.getOrDefault(material, 0);
        }

    }

}
