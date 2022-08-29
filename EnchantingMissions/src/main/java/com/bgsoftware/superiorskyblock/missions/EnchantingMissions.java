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
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
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
public final class EnchantingMissions extends Mission<EnchantingMissions.EnchantsTracker> implements Listener {

    private static final SuperiorSkyblock superiorSkyblock = SuperiorSkyblockAPI.getSuperiorSkyblock();

    private static final Pattern percentagePattern = Pattern.compile("(.*)\\{enchanted_(.+?)}(.*)");

    private final Map<List<String>, RequiredEnchantment> requiredEnchantments = new HashMap<>();
    private final Map<RequiredEnchantment, String> enchBossBar = new HashMap<>();

    private String enchantedPlaceholder, notEnchantedPlaceholder;
    private JavaPlugin plugin;

    @Override
    public void load(JavaPlugin plugin, ConfigurationSection section) throws MissionLoadException {
        this.plugin = plugin;

        if (!section.contains("required-enchants"))
            throw new MissionLoadException("You must have the \"required-enchants\" section in the config.");

        for (String key : section.getConfigurationSection("required-enchants").getKeys(false)) {
            List<String> itemTypes = section.getStringList("required-enchants." + key + ".types");
            Map<Enchantment, Integer> enchantments = new HashMap<>();

            if (section.isConfigurationSection("required-enchants." + key + ".enchants")) {
                for (String enchantment : section.getConfigurationSection("required-enchants." + key + ".enchants").getKeys(false)) {
                    Enchantment _enchantment = Enchantment.getByName(enchantment.toUpperCase());

                    if (_enchantment == null)
                        throw new MissionLoadException("Enchantment " + enchantment + " is not valid.");

                    enchantments.put(_enchantment, section.getInt("required-enchants." + key + ".enchants." + enchantment));
                }
            }

            String bossBar = section.getString("required-enchants." + key + ".boss-bar", "?");
            RequiredEnchantment requiredEnchantment = new RequiredEnchantment(key, enchantments, section.getInt("required-enchants." + key + ".amount", 1), section.getInt("required-enchants." + key + ".min-level", 0));

            requiredEnchantments.put(itemTypes, requiredEnchantment);
            enchBossBar.put(requiredEnchantment, bossBar);

            setClearMethod(enchantsTracker -> enchantsTracker.enchantsTracker.clear());
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);

        enchantedPlaceholder = section.getString("enchanted-placeholder", "Yes");
        notEnchantedPlaceholder = section.getString("not-enchanted-placeholder", "No");
    }

    @Override
    public double getProgress(SuperiorPlayer superiorPlayer) {
        EnchantsTracker enchantsTracker = get(superiorPlayer);

        if (enchantsTracker == null)
            return 0.0;

        int requiredItems = 0;
        int enchants = 0;

        for (RequiredEnchantment requiredEnchantment : this.requiredEnchantments.values()) {
            requiredItems += requiredEnchantment.amount;
            enchants += enchantsTracker.getEnchanted(requiredEnchantment.key);
        }

        return (double) enchants / requiredItems;
    }

    @Override
    public int getProgressValue(SuperiorPlayer superiorPlayer) {
        EnchantsTracker enchantsTracker = get(superiorPlayer);

        if (enchantsTracker == null)
            return 0;

        int enchants = 0;

        for (RequiredEnchantment requiredEnchantment : this.requiredEnchantments.values()) {
            enchants += enchantsTracker.getEnchanted(requiredEnchantment.key);
        }

        return enchants;
    }

    public int getProgress(SuperiorPlayer superiorPlayer, RequiredEnchantment requiredEnchantment) {
        EnchantsTracker enchantsTracker = get(superiorPlayer);
        if (enchantsTracker == null)
            return 0;

        return enchantsTracker.getEnchanted(requiredEnchantment.key);
    }

    @Override
    public void onComplete(SuperiorPlayer superiorPlayer) {
        onCompleteFail(superiorPlayer);
    }

    @Override
    public void onCompleteFail(SuperiorPlayer superiorPlayer) {

    }

    @Override
    public void formatItem(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        EnchantsTracker enchantsTracker = getOrCreate(superiorPlayer, s -> new EnchantsTracker());

        if(enchantsTracker == null)
            return;

        ItemMeta itemMeta = itemStack.getItemMeta();

        if (itemMeta.hasDisplayName())
            itemMeta.setDisplayName(parsePlaceholders(enchantsTracker, itemMeta.getDisplayName()));

        if (itemMeta.hasLore()) {
            List<String> lore = new ArrayList<>();
            for (String line : itemMeta.getLore())
                lore.add(parsePlaceholders(enchantsTracker, line));
            itemMeta.setLore(lore);
        }

        itemStack.setItemMeta(itemMeta);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemEnchant(EnchantItemEvent e) {
        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(e.getEnchanter());

        ItemStack simulateEnchanted = e.getItem().clone();
        ItemMeta itemMeta = simulateEnchanted.getItemMeta();

        for (Map.Entry<Enchantment, Integer> entry : e.getEnchantsToAdd().entrySet()) {
            if (simulateEnchanted.getType() == Material.BOOK) {
                simulateEnchanted = new ItemStack(Material.ENCHANTED_BOOK);
                itemMeta = simulateEnchanted.getItemMeta();
            }

            if (simulateEnchanted.getType() == Material.ENCHANTED_BOOK) {
                ((EnchantmentStorageMeta) itemMeta).addStoredEnchant(entry.getKey(), entry.getValue(), true);
            } else if (entry.getKey().canEnchantItem(simulateEnchanted)) {
                itemMeta.addEnchant(entry.getKey(), entry.getValue(), true);
            }
        }

        simulateEnchanted.setItemMeta(itemMeta);

        handleEnchanting(e.getEnchanter(), simulateEnchanted, e.getExpLevelCost());
    }

    @Override
    public void saveProgress(ConfigurationSection section) {
        for (Map.Entry<SuperiorPlayer, EnchantsTracker> entry : entrySet()) {
            String uuid = entry.getKey().getUniqueId().toString();
            List<String> data = new ArrayList<>();
            entry.getValue().enchantsTracker.forEach((enchant, amount) -> data.add(enchant + ";" + amount));
            section.set(uuid, data);
        }
    }

    @Override
    public void loadProgress(ConfigurationSection section) {
        for (String uuid : section.getKeys(false)) {
            EnchantsTracker enchantsTracker = new EnchantsTracker();
            UUID playerUUID = UUID.fromString(uuid);
            SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(playerUUID);

            insertData(superiorPlayer, enchantsTracker);

            section.getStringList(uuid).forEach(line -> {
                String[] sections = line.split(";");
                int amount = sections.length == 2 ? Integer.parseInt(sections[1]) : 1;
                String enchantment = sections[0];
                enchantsTracker.enchantsTracker.put(enchantment, amount);
            });
        }
    }

    private boolean isMissionItem(ItemStack itemStack) {
        for (List<String> requiredItems : requiredEnchantments.keySet()) {
            if (requiredItems.contains("ALL") || requiredItems.contains("all") || requiredItems.contains(itemStack.getType().name()))
                return true;
        }

        return false;
    }

    private void handleEnchanting(Player player, ItemStack itemStack, int enchantLevel) {
        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(player);

        if (!isMissionItem(itemStack) || !superiorSkyblock.getMissions().canCompleteNoProgress(superiorPlayer, this))
            return;

        EnchantsTracker enchantsTracker = getOrCreate(superiorPlayer, s -> new EnchantsTracker());

        if(enchantsTracker == null)
            return;

        enchantsTracker.track(superiorPlayer, itemStack, enchantLevel);

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> superiorPlayer.runIfOnline(_player -> {
            if (canComplete(superiorPlayer))
                SuperiorSkyblockAPI.getSuperiorSkyblock().getMissions().rewardMission(this, superiorPlayer, true);
        }), 2L);
    }

    private String parsePlaceholders(EnchantsTracker enchantsTracker, String line) {
        Matcher matcher = percentagePattern.matcher(line);

        if (matcher.matches()) {
            String requireditem = matcher.group(2).toUpperCase();

            Optional<Map.Entry<List<String>, RequiredEnchantment>> entry = requiredEnchantments.entrySet().stream()
                    .filter(e -> e.getKey().contains(requireditem)).findAny();

            if (entry.isPresent()) {
                line = line.replace("{enchanted_" + matcher.group(2) + "}",
                        enchantsTracker.getEnchanted(entry.get().getValue().key) > 0 ? enchantedPlaceholder : notEnchantedPlaceholder);
            }
        }

        return ChatColor.translateAlternateColorCodes('&', line);
    }

    public class EnchantsTracker {

        private final Map<String, Integer> enchantsTracker = new HashMap<>();

        void track(SuperiorPlayer superiorPlayer, ItemStack itemStack, int enchantLevel) {
            outerLoop:
            for (List<String> requiredItems : requiredEnchantments.keySet()) {
                if (requiredItems.contains(itemStack.getType().name()) || requiredItems.contains("ALL") || requiredItems.contains("all")) {
                    RequiredEnchantment requiredEnchantment = requiredEnchantments.get(requiredItems);
                    if (enchantLevel < requiredEnchantment.minLevel)
                        continue;

                    for (Enchantment enchantment : requiredEnchantment.enchantments.keySet()) {
                        if (itemStack.getType() == Material.ENCHANTED_BOOK) {
                            if (((EnchantmentStorageMeta) itemStack.getItemMeta()).getStoredEnchantLevel(enchantment) <
                                    requiredEnchantment.enchantments.get(enchantment))
                                continue outerLoop;
                        } else if (itemStack.getEnchantmentLevel(enchantment) < requiredEnchantment.enchantments.get(enchantment)) {
                            continue outerLoop;
                        }
                    }

                    enchantsTracker.put(requiredEnchantment.key, getEnchanted(requiredEnchantment.key) + 1);
                    if (enchBossBar.containsKey(requiredEnchantment))
                        sendBossBar(superiorPlayer, enchBossBar.get(requiredEnchantment), getProgress(superiorPlayer, requiredEnchantment), requiredEnchantment.amount, getProgress(superiorPlayer));

                    break;
                }
            }
        }

        int getEnchanted(String key) {
            return enchantsTracker.getOrDefault(key, 0);
        }

    }

    private static class RequiredEnchantment {

        private final String key;
        private final Map<Enchantment, Integer> enchantments;
        private final Integer amount;
        private final Integer minLevel;

        RequiredEnchantment(String key, Map<Enchantment, Integer> enchantments, Integer amount, Integer minLevel) {
            this.key = key;
            this.enchantments = enchantments;
            this.amount = amount;
            this.minLevel = minLevel;
        }

    }
}
