package com.bgsoftware.superiorskyblock.missions;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblock;
import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.missions.Mission;
import com.bgsoftware.superiorskyblock.api.missions.MissionLoadException;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.bgsoftware.wildstacker.api.events.BarrelUnstackEvent;
import dev.aurelium.auraskills.api.AuraSkillsBukkit;
import dev.aurelium.auraskills.api.region.Regions;
import dev.aurelium.auraskills.api.source.type.BlockXpSource;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.bukkit.source.BlockLeveler;
import lv.side.enchants.Events.CeBlockBreakEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("unused")
public final class BlocksMissions extends Mission<BlocksMissions.BlocksCounter> implements Listener {

    private static final SuperiorSkyblock superiorSkyblock = SuperiorSkyblockAPI.getSuperiorSkyblock();

    private static Regions regionTracker;
    private static BlockLeveler blockLeveler;

    private static final Pattern percentagePattern = Pattern.compile("(.*)\\{percentage_(.+?)}(.*)"),
            valuePattern = Pattern.compile("(.*)\\{value_(.+?)}(.*)");

    private final Map<List<String>, Integer> requiredBlocks = new HashMap<>();
    private final Map<String, String> blocksBossBar = new HashMap<>();

    private boolean onlyNatural, blocksPlacement, replaceBlocks;
    private JavaPlugin plugin;

    @Override
    public void load(JavaPlugin plugin, ConfigurationSection section) throws MissionLoadException {
        this.plugin = plugin;

        if (!section.contains("required-blocks"))
            throw new MissionLoadException("You must have the \"required-blocks\" section in the config.");

        for (String key : section.getConfigurationSection("required-blocks").getKeys(false)) {
            List<String> blocks = section.getStringList("required-blocks." + key + ".types");
            int requiredAmount = section.getInt("required-blocks." + key + ".amount");
            String bossBar = section.getString("required-blocks." + key + ".boss-bar", "?");

            requiredBlocks.put(blocks, requiredAmount);
            for (String block : blocks) {
                blocksBossBar.put(block, bossBar);
            }
        }

        //resetAfterFinish = section.getBoolean("reset-after-finish", false);
        onlyNatural = section.getBoolean("only-natural-blocks", false);
        blocksPlacement = section.getBoolean("blocks-placement", false);
        replaceBlocks = section.getBoolean("blocks-replace", false);

        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (Bukkit.getPluginManager().isPluginEnabled("WildStacker"))
                Bukkit.getPluginManager().registerEvents(new WildStackerListener(), plugin);
            if (Bukkit.getPluginManager().isPluginEnabled("AuraSkills")) {
                regionTracker = AuraSkillsBukkit.get().getRegions();
                blockLeveler = ((AuraSkills) Bukkit.getPluginManager().getPlugin("AuraSkills")).getLevelManager().getLeveler(BlockLeveler.class);
            }
        }, 1L);

        setClearMethod(blocksCounter -> blocksCounter.trackedBlockCounts.clear());
    }

    @Override
    public double getProgress(SuperiorPlayer superiorPlayer) {
        BlocksCounter blocksCounter = get(superiorPlayer);

        if (blocksCounter == null)
            return 0.0;

        int requiredBlocks = 0;
        int interactions = 0;

        for (Map.Entry<List<String>, Integer> requiredBlock : this.requiredBlocks.entrySet()) {
            requiredBlocks += requiredBlock.getValue();
            interactions += Math.min(blocksCounter.getBlocksCount(requiredBlock.getKey()), requiredBlock.getValue());
        }

        return (double) interactions / requiredBlocks;
    }

    @Override
    public int getProgressValue(SuperiorPlayer superiorPlayer) {
        BlocksCounter blocksCounter = get(superiorPlayer);

        if (blocksCounter == null)
            return 0;

        int interactions = 0;

        for (Map.Entry<List<String>, Integer> requiredBlock : this.requiredBlocks.entrySet())
            interactions += Math.min(blocksCounter.getBlocksCount(requiredBlock.getKey()), requiredBlock.getValue());

        return interactions;
    }

    public int getRequired(String type) {
        for (Map.Entry<List<String>, Integer> entry : requiredBlocks.entrySet()) {
            if (entry.getKey().contains(type))
                return entry.getValue();
        }

        return -1;
    }

    public int getProgress(SuperiorPlayer superiorPlayer, String type) {
        BlocksCounter blocksCounter = get(superiorPlayer);
        if (blocksCounter == null)
            return 0;

        return blocksCounter.getBlocksCount(requiredBlocks, type);
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
            for (Map.Entry<String, Integer> blockCountEntry : entry.getValue().getBlockCounts().entrySet()) {
                section.set(uuid + ".counts." + blockCountEntry.getKey(), blockCountEntry.getValue());
            }
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

            if (section.contains(uuid + ".counts")) {
                ConfigurationSection countsSection = section.getConfigurationSection(uuid + ".counts");
                if (countsSection != null) {
                    for (String key : countsSection.getKeys(false)) {
                        blocksCounter.loadBlockCount(key, countsSection.getInt(key));
                    }
                }
            } else {
                for (String key : section.getConfigurationSection(uuid).getKeys(false)) {
                    blocksCounter.loadBlockCount(key, section.getInt(uuid + "." + key));
                }
            }
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(e.getPlayer());

        BlocksCounter blocksCounter = getOrCreate(superiorPlayer, s -> new BlocksCounter());

        if (blocksCounter == null)
            return;

        BlockInfo blockInfo = new BlockInfo(e.getBlock());

        if (blocksPlacement) {
            if (!replaceBlocks && isMissionBlock(blockInfo)) {
                blocksCounter.countBlock(blockInfo.getBlockKey(), getBlockAmount(e.getPlayer(), e.getBlock()) * -1);
                blocksCounter.countBlock("ALL", getBlockAmount(e.getPlayer(), e.getBlock()) * -1);
            }
            return;
        }

        handleBlockBreak(e.getBlock(), superiorPlayer, blockInfo);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (!e.getBlocks().isEmpty())
            handleBlockPistonMove(new ArrayList<>(e.getBlocks()), e.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (!e.getBlocks().isEmpty())
            handleBlockPistonMove(new ArrayList<>(e.getBlocks()), e.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        if (!onlyNatural && !blocksPlacement)
            return;

        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(e.getPlayer());

        BlockInfo blockInfo = new BlockInfo(e.getBlock());

        if (!isMissionBlock(blockInfo))
            return;

        if (!blocksPlacement) {
            if (!replaceBlocks && blockLeveler.getSource(e.getBlock(), BlockXpSource.BlockTriggers.BREAK) == null) {
                regionTracker.addPlacedBlock(e.getBlock());
            }
            return;
        }

        if (isBarrel(e.getBlock()) || !superiorSkyblock.getMissions().canCompleteNoProgress(superiorPlayer, this))
            return;

        handleBlockTrack(e.getPlayer(), e.getBlock(), getBlockAmount(e.getPlayer(), e.getBlock()));
    }
    @EventHandler
    public void onCEBlockBreak(CeBlockBreakEvent e) {
        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(e.getPlayer());

        BlocksCounter blocksCounter = getOrCreate(superiorPlayer, s -> new BlocksCounter());

        if (blocksCounter == null)
            return;

        BlockInfo blockInfo = new BlockInfo(e.getBlock());

        if (blocksPlacement) {
            if (!replaceBlocks && isMissionBlock(blockInfo)) {
                blocksCounter.countBlock(blockInfo.getBlockKey(), getBlockAmount(e.getPlayer(), e.getBlock()) * -1);
                blocksCounter.countBlock("ALL", getBlockAmount(e.getPlayer(), e.getBlock()) * -1);
            }
            return;
        }

        handleBlockBreak(e.getBlock(), superiorPlayer, blockInfo);
    }

    private class WildStackerListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onBarrelUnstack(BarrelUnstackEvent e) {
            if (onlyNatural || !(e.getUnstackSource() instanceof Player))
                return;

            Block block = e.getBarrel().getBlock();
            ItemStack barrelItem = e.getBarrel().getBarrelItem(1);
            Material blockType = barrelItem.getType();

            BlockInfo blockInfo = new BlockInfo(blockType, barrelItem.getDurability());

            if (!isMissionBlock(blockInfo))
                return;

            handleBlockTrack((Player) e.getUnstackSource(), block, blockInfo, e.getAmount());
        }

    }

    private void handleBlockBreak(Block block, Player player) {
        handleBlockBreak(block, SuperiorSkyblockAPI.getPlayer(player), null);
    }

    private void handleBlockBreak(Block block, SuperiorPlayer superiorPlayer, @Nullable BlockInfo blockInfo) {
        Location location = block.getLocation();

        if (blockInfo == null)
            blockInfo = new BlockInfo(block);

        if (isBarrel(block) || !isMissionBlock(blockInfo) ||
                !superiorSkyblock.getMissions().canCompleteNoProgress(superiorPlayer, this))
            return;

        if (onlyNatural && regionTracker.isPlacedBlock(block))
            return;

        handleBlockTrack(TrackingType.BROKEN_BLOCKS, superiorPlayer, block, blockInfo, getBlockAmount(superiorPlayer.asPlayer(), block));
    }

    private void handleBlockPistonMove(List<Block> blockList, BlockFace direction) {
        blockList.removeIf(block -> !isMissionBlock(new BlockInfo(block)) || !regionTracker.isPlacedBlock(block));

        if (blockList.isEmpty())
            return;

        List<Block> movedBlocks = blockList.stream()
                .map(block -> block.getRelative(direction))
                .toList();

        List<Block> addedBlocks = new ArrayList<>(movedBlocks);
        addedBlocks.removeAll(blockList);

        addedBlocks.forEach(block -> {
            if (blockLeveler.getSource(block, BlockXpSource.BlockTriggers.BREAK) == null)
                regionTracker.addPlacedBlock(block);
        });
    }

    private void handleBlockTrack(Player player, Block block, int amount) {
        handleBlockTrack(TrackingType.PLACED_BLOCKS, SuperiorSkyblockAPI.getPlayer(player), block, new BlockInfo(block), amount);
    }

    private void handleBlockTrack(Player player, Block block, BlockInfo blockInfo, int amount) {
        handleBlockTrack(TrackingType.BROKEN_BLOCKS, SuperiorSkyblockAPI.getPlayer(player), block, blockInfo, amount);
    }

    private void handleBlockTrack(TrackingType trackingType, SuperiorPlayer superiorPlayer, Block block,
                                  BlockInfo blockInfo, int amount) {
        if (!isMissionBlock(blockInfo) || !superiorSkyblock.getMissions().canCompleteNoProgress(superiorPlayer, this))
            return;

        BlocksCounter blocksCounter = getOrCreate(superiorPlayer, s -> new BlocksCounter());
        if (blocksCounter == null)
            return;

        if (trackingType == TrackingType.PLACED_BLOCKS && blockLeveler.getSource(block, BlockXpSource.BlockTriggers.BREAK) == null)
            regionTracker.addPlacedBlock(block);

        blocksCounter.countBlock(blockInfo.getBlockKey(), amount);
        blocksCounter.countBlock("ALL", amount);

        if (blocksBossBar.containsKey(block.getType().name()) && getRequired(block.getType().name()) > -1)
            sendBossBar(superiorPlayer, blocksBossBar.get(block.getType().name()), getProgress(superiorPlayer, block.getType().name()), getRequired(block.getType().name()), getProgress(superiorPlayer));
        else if (blocksBossBar.containsKey("ALL") && getRequired("ALL") > -1)
            sendBossBar(superiorPlayer, blocksBossBar.get("ALL"), getProgress(superiorPlayer, "ALL"), getRequired("ALL"), getProgress(superiorPlayer));


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

    private boolean isBarrel(Block block) {
        return Bukkit.getPluginManager().isPluginEnabled("WildStacker") &&
                com.bgsoftware.wildstacker.api.WildStackerAPI.getWildStacker().getSystemManager().isStackedBarrel(block);
    }

    private boolean isMissionBlock(BlockInfo blockInfo) {
        for (List<String> requiredBlock : requiredBlocks.keySet()) {
            if (requiredBlock.contains(blockInfo.blockType.name()) ||
                    requiredBlock.contains(blockInfo.blockType.name() + ":" + blockInfo.blockData) ||
                    requiredBlock.contains("all") || requiredBlock.contains("ALL"))
                return true;
        }

        return false;
    }

    private String parsePlaceholders(BlocksCounter blocksCounter, String line) {
        Matcher matcher = percentagePattern.matcher(line);

        if (matcher.matches()) {
            String requiredBlock = matcher.group(2).toUpperCase();
            Optional<Map.Entry<List<String>, Integer>> entry = requiredBlocks.entrySet().stream().filter(e -> e.getKey().contains(requiredBlock)).findAny();
            if (entry.isPresent()) {
                line = line.replace("{percentage_" + matcher.group(2) + "}",
                        "" + (blocksCounter.getBlocksCount(requiredBlocks, requiredBlock) * 100) / entry.get().getValue());
            }
        }

        if ((matcher = valuePattern.matcher(line)).matches()) {
            String requiredBlock = matcher.group(2).toUpperCase();
            Optional<Map.Entry<List<String>, Integer>> entry = requiredBlocks.entrySet().stream().filter(e -> e.getKey().contains(requiredBlock)).findFirst();
            if (entry.isPresent()) {
                line = line.replace("{value_" + matcher.group(2) + "}",
                        "" + blocksCounter.getBlocksCount(requiredBlocks, requiredBlock));
            }
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

        private final Map<String, Integer> trackedBlockCounts = new HashMap<>();

        void countBlock(String blockKey, int amount) {
            blockKey = blockKey.toUpperCase();
            int blockCount = getBlocksCount(blockKey);
            if (trackedBlockCounts.getOrDefault(blockKey, 0) + amount < 0)
                return;
            this.trackedBlockCounts.put(blockKey, blockCount + amount);
        }

        void loadBlockCount(String blockKey, int amount) {
            this.trackedBlockCounts.put(blockKey, amount);
        }

        int getBlocksCount(Map<List<String>, Integer> required, String blockKey) {
            int amount = 0;

            for (Map.Entry<List<String>, Integer> entry : required.entrySet()) {
                if (entry.getKey().contains(blockKey)) {
                    for (String key : entry.getKey()) {
                        amount += this.trackedBlockCounts.getOrDefault(key, 0);
                    }
                }
            }

            return amount;
        }

        int getBlocksCount(String blockKey) {
            return this.trackedBlockCounts.getOrDefault(blockKey, 0);
        }

        int getBlocksCount(List<String> blocks) {
            int amount = 0;

            for (String block : blocks) {
                if (block.equalsIgnoreCase("ALL")) {
                    return getBlocksCount(block);
                } else {
                    amount += getBlocksCount(block);
                }
            }

            return amount;
        }

        Map<String, Integer> getBlockCounts() {
            return Collections.unmodifiableMap(this.trackedBlockCounts);
        }

    }

    private class BlockInfo {

        private final Material blockType;
        private final short blockData;

        BlockInfo(Block block) {
            this.blockType = block.getType();
            short blockData = 0;

            try {
                //noinspection deprecation
                blockData = block.getData();
            } catch (Throwable ignored) {
            }

            this.blockData = blockData;
        }

        BlockInfo(Material blockType, short blockData) {
            this.blockType = blockType;
            this.blockData = blockData;
        }

        String getBlockKey() {
            String combinedKey = blockType.name() + ":" + blockData;
            return requiredBlocks.entrySet().stream().anyMatch(entry -> entry.getKey().contains(combinedKey)) ?
                    combinedKey : blockType.name();
        }

    }

    private enum TrackingType {
        BROKEN_BLOCKS,
        PLACED_BLOCKS
    }

}
