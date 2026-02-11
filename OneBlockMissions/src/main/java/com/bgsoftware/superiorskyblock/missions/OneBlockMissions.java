package com.bgsoftware.superiorskyblock.missions;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblock;
import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.missions.Mission;
import com.bgsoftware.superiorskyblock.api.missions.MissionLoadException;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("unused")
public final class OneBlockMissions extends Mission<OneBlockMissions.BlocksCounter> implements Listener {

    private static final SuperiorSkyblock superiorSkyblock = SuperiorSkyblockAPI.getSuperiorSkyblock();

    private static final Pattern percentagePattern = Pattern.compile("(.*)\\{percentage_(.+?)}(.*)"),
            valuePattern = Pattern.compile("(.*)\\{value_(.+?)}(.*)");

    private int requiredCount;
    private String blocksBossBar;

    private JavaPlugin plugin;

    @Override
    public void load(JavaPlugin plugin, ConfigurationSection section) throws MissionLoadException {
        this.plugin = plugin;

        if (!section.contains("required-blocks"))
            throw new MissionLoadException("You must have the \"required-blocks\" section in the config.");

        for (String key : section.getConfigurationSection("required-blocks").getKeys(false)) {
            int requiredAmount = section.getInt("required-blocks." + key + ".amount");
            String bossBar = section.getString("required-blocks." + key + ".boss-bar", "?");

            requiredCount = requiredAmount;
            blocksBossBar = bossBar;
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);

        setClearMethod(blocksCounter -> {
            System.out.println("Clearing blocks counter!!");
            blocksCounter.trackedBlockCounts = 0;
        });
    }

    @Override
    public double getProgress(SuperiorPlayer superiorPlayer) {
        BlocksCounter blocksCounter = get(superiorPlayer);

        if (blocksCounter == null)
            return 0.0;

        return (double) blocksCounter.getBlocksCount() / this.requiredCount;
    }

    @Override
    public int getProgressValue(SuperiorPlayer superiorPlayer) {
        BlocksCounter blocksCounter = get(superiorPlayer);

        if (blocksCounter == null)
            return 0;

        return blocksCounter.getBlocksCount();
    }

    public int getMissionProgress(SuperiorPlayer superiorPlayer) {
        BlocksCounter blocksCounter = get(superiorPlayer);
        if (blocksCounter == null)
            return 0;

        return blocksCounter.getBlocksCount();
    }

    @Override
    public void onComplete(SuperiorPlayer superiorPlayer) {
        clearData(superiorPlayer);
    }

    @Override
    public void onCompleteFail(SuperiorPlayer superiorPlayer) {

    }

    @Override
    public void saveProgress(ConfigurationSection section) {
        for (Map.Entry<SuperiorPlayer, BlocksCounter> entry : entrySet()) {
            String uuid = entry.getKey().getUniqueId().toString();
            section.set(uuid + ".counts", entry.getValue().trackedBlockCounts);
        }
    }

    @Override
    public void loadProgress(ConfigurationSection section) {
        for (String uuid : section.getKeys(false)) {
            BlocksCounter blocksCounter = new BlocksCounter();
            UUID playerUUID;

            try {
                playerUUID = UUID.fromString(uuid);
            } catch (Exception error) {
                // tracked section probably, skipping.
                continue;
            }

            SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(playerUUID);

            insertData(superiorPlayer, blocksCounter);
            blocksCounter.loadBlockCount(section.getInt(uuid + ".counts", 0));
        }
    }

    @Override
    public void formatItem(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        BlocksCounter blocksCounter = getOrCreate(superiorPlayer, s -> new BlocksCounter());

        if (blocksCounter == null)
            return;

        ItemMeta itemMeta = itemStack.getItemMeta();

        if (itemMeta.hasDisplayName())
            itemMeta.setDisplayName(parsePlaceholders(blocksCounter, itemMeta.getDisplayName()));

        if (itemMeta.hasLore()) {
            List<String> lore = new ArrayList<>();
            for (String line : itemMeta.getLore())
                lore.add(parsePlaceholders(blocksCounter, line));
            itemMeta.setLore(lore);
        }

        itemStack.setItemMeta(itemMeta);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(e.getPlayer());
        BlocksCounter blocksCounter = getOrCreate(superiorPlayer, s -> new BlocksCounter());
        if (blocksCounter == null)
            return;

        Location blockLoc = e.getBlock().getLocation();
        Island islandAtBlock = superiorSkyblock.getGrid().getIslandAt(blockLoc);
        if (islandAtBlock == null || islandAtBlock != superiorPlayer.getIsland() || !getOneBlock(superiorPlayer.getIsland()).equals(blockLoc))
            return;

        handleBlockTrack(superiorPlayer);
    }

    private Location getOneBlock(Island island) {
        Location islandCenter = island.getCenter(superiorSkyblock.getSettings().getWorlds().getDefaultWorldDimension());
        return islandCenter.subtract(0.5D, 1.D, 0.5D);
    }

    private void handleBlockTrack(SuperiorPlayer superiorPlayer) {
        if (!superiorSkyblock.getMissions().canCompleteNoProgress(superiorPlayer, this))
            return;

        BlocksCounter blocksCounter = getOrCreate(superiorPlayer, s -> new BlocksCounter());
        if (blocksCounter == null)
            return;

        blocksCounter.countBlock();

        sendBossBar(superiorPlayer, blocksBossBar, getMissionProgress(superiorPlayer), this.requiredCount, getProgress(superiorPlayer));

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> superiorPlayer.runIfOnline(_player -> {
            if (canComplete(superiorPlayer))
                superiorSkyblock.getMissions().rewardMission(this, superiorPlayer, true);
        }), 2L);
    }

    private int getBlockAmount(Player player, Block block) {
        int blockAmount = superiorSkyblock.getGrid().getBlockAmount(block);

        // When sneaking, you'll break 64 from the stack. Otherwise, 1.
        int amount = !player.isSneaking() ? 1 : 64;

        // Fix amount so it won't be more than the stack's amount
        amount = Math.min(amount, blockAmount);

        return amount;
    }

    private String parsePlaceholders(BlocksCounter blocksCounter, String line) {
        Matcher matcher = percentagePattern.matcher(line);

        if (matcher.matches()) {
            line = line.replace("{percentage_" + matcher.group(2) + "}",
                    "" + (blocksCounter.getBlocksCount() * 100) / this.requiredCount);
        }

        if ((matcher = valuePattern.matcher(line)).matches()) {
            line = line.replace("{value_" + matcher.group(2) + "}",
                    "" + blocksCounter.getBlocksCount());
        }

        return ChatColor.translateAlternateColorCodes('&', line);
    }

    private static <T> List<T> difference(List<T> l1, List<T> l2) {
        List<T> commonBlocks = new ArrayList<>(l1);
        commonBlocks.retainAll(l2);

        Set<T> allBlocksNoDupes = new HashSet<>(l1);
        allBlocksNoDupes.addAll(l2);

        List<T> differentBlocks = new ArrayList<>(allBlocksNoDupes);
        differentBlocks.removeAll(commonBlocks);

        return differentBlocks;
    }

    public static final class BlocksCounter {

        private int trackedBlockCounts = 0;

        void countBlock() {
            this.trackedBlockCounts += 1;
        }

        void loadBlockCount(int amount) {
            this.trackedBlockCounts = amount;
        }

        int getBlocksCount() {
            return this.trackedBlockCounts;
        }
    }
}
