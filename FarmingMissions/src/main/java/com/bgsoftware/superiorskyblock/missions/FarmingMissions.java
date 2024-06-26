package com.bgsoftware.superiorskyblock.missions;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblock;
import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.missions.Mission;
import com.bgsoftware.superiorskyblock.api.missions.MissionLoadException;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.google.common.collect.ImmutableMap;
import lv.side.sidecrops.events.CropRipeEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("unused")
public final class FarmingMissions extends Mission<FarmingMissions.FarmingTracker> implements Listener {

    private static final SuperiorSkyblock superiorSkyblock = SuperiorSkyblockAPI.getSuperiorSkyblock();

    private static final Pattern percentagePattern = Pattern.compile("(.*)\\{percentage_(.+?)}(.*)"),
            valuePattern = Pattern.compile("(.*)\\{value_(.+?)}(.*)");

    private static final BlockFace[] NEARBY_BLOCKS = new BlockFace[]{
            BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH, BlockFace.SOUTH
    };

    private static final Map<String, Integer> MAXIMUM_AGES = new ImmutableMap.Builder<String, Integer>()
            .put("CARROTS", 7)
            .put("CARROT", 7)
            .put("CROPS", 7)
            .put("WHEAT_SEEDS", 7)
            .put("WHEAT", 7)
            .put("POTATO", 7)
            .put("POTATOES", 7)
            .put("BEETROOT_SEEDS", 3)
            .put("BEETROOTS", 3)
            .put("COCOA", 2)
            .put("COCOA_BEANS", 2)
            .put("NETHER_WART", 3)
            .put("BEETROOT", 3)
            .build();

    private JavaPlugin plugin;
    private final Map<List<String>, Integer> requiredPlants = new HashMap<>();
    private boolean resetAfterFinish;

    @Override
    public void load(JavaPlugin plugin, ConfigurationSection section) throws MissionLoadException {
        this.plugin = plugin;

        if (!section.contains("required-plants"))
            throw new MissionLoadException("You must have the \"required-plants\" section in the config.");

        for (String key : section.getConfigurationSection("required-plants").getKeys(false)) {
            List<String> requiredPlants = section.getStringList("required-plants." + key + ".types");
            int requiredAmount = section.getInt("required-plants." + key + ".amount");
            this.requiredPlants.put(requiredPlants, requiredAmount);
        }

        resetAfterFinish = section.getBoolean("reset-after-finish", false);

        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public double getProgress(SuperiorPlayer superiorPlayer) {
        FarmingTracker farmingTracker = get(superiorPlayer);

        if (farmingTracker == null)
            return 0.0;

        int requiredPlants = 0;
        int progress = 0;

        for (Map.Entry<List<String>, Integer> requiredPlant : this.requiredPlants.entrySet()) {
            requiredPlants += requiredPlant.getValue();
            progress += Math.min(farmingTracker.getPlants(requiredPlant.getKey()), requiredPlant.getValue());
        }

        return (double) progress / requiredPlants;
    }

    @Override
    public int getProgressValue(SuperiorPlayer superiorPlayer) {
        FarmingTracker farmingTracker = get(superiorPlayer);

        if (farmingTracker == null)
            return 0;

        int progress = 0;

        for (Map.Entry<List<String>, Integer> requiredPlant : this.requiredPlants.entrySet())
            progress += Math.min(farmingTracker.getPlants(requiredPlant.getKey()), requiredPlant.getValue());

        return progress;
    }

    @Override
    public void onComplete(SuperiorPlayer superiorPlayer) {
        if (resetAfterFinish)
            clearData(superiorPlayer);
    }

    @Override
    public void onCompleteFail(SuperiorPlayer superiorPlayer) {

    }

    @Override
    public void saveProgress(ConfigurationSection section) {
        for (Map.Entry<SuperiorPlayer, FarmingTracker> entry : entrySet()) {
            String uuid = entry.getKey().getUniqueId().toString();
            for (Map.Entry<String, Integer> brokenEntry : entry.getValue().farmingTracker.entrySet()) {
                section.set("grown-plants." + uuid + "." + brokenEntry.getKey(), brokenEntry.getValue());
            }
        }
    }

    @Override
    public void loadProgress(ConfigurationSection section) {
        ConfigurationSection grownPlants = section.getConfigurationSection("grown-plants");
        if (grownPlants != null) {
            for (String uuid : grownPlants.getKeys(false)) {
                FarmingTracker farmingTracker = new FarmingTracker();
                UUID playerUUID = UUID.fromString(uuid);
                SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(playerUUID);

                insertData(superiorPlayer, farmingTracker);

                for (String key : grownPlants.getConfigurationSection(uuid).getKeys(false)) {
                    farmingTracker.farmingTracker.put(key, grownPlants.getInt(uuid + "." + key));
                }
            }
        }
    }

    @Override
    public void formatItem(SuperiorPlayer superiorPlayer, ItemStack itemStack) {
        FarmingTracker farmingTracker = getOrCreate(superiorPlayer, s -> new FarmingTracker());

        if (farmingTracker == null)
            return;

        ItemMeta itemMeta = itemStack.getItemMeta();

        if (itemMeta.hasDisplayName())
            itemMeta.setDisplayName(parsePlaceholders(farmingTracker, itemMeta.getDisplayName()));

        if (itemMeta.hasLore()) {
            List<String> lore = new ArrayList<>();
            for (String line : itemMeta.getLore())
                lore.add(parsePlaceholders(farmingTracker, line));
            itemMeta.setLore(lore);
        }

        itemStack.setItemMeta(itemMeta);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        String blockTypeName = e.getBlock().getType().name();

        switch (blockTypeName) {
            case "PUMPKIN_STEM":
                blockTypeName = "PUMPKIN";
                break;
            case "MELON_STEM":
                blockTypeName = "MELON";
                break;
            case "BAMBOO_SAPLING":
                blockTypeName = "BAMBOO";
                break;
        }

        if (!isMissionPlant(blockTypeName))
            return;

        UUID placerUUID = getPlacerUUID(e.getPlayer());

        if (placerUUID == null)
            return;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBambooGrow(BlockSpreadEvent e) {
        handlePlantGrow(e.getBlock(), e.getNewState());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlantGrow(StructureGrowEvent e) {
        Block block = e.getLocation().getBlock();
        handlePlantGrow(block, block.getState());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlantGrow(BlockGrowEvent e) {
        handlePlantGrow(e.getBlock(), e.getNewState());
    }

    @EventHandler
    public void onCustomCropRipe(CropRipeEvent e) {
        String blockTypeName = "CUSTOM;" + e.getCropType().getId();

        if (!isMissionPlant(blockTypeName))
            return;

        SuperiorPlayer superiorPlayer;
        Island island = e.getIsland();

        if (getIslandMission()) {
            if (island == null)
                return;

            superiorPlayer = island.getOwner();
        } else {
            return;
        }

        if (!superiorSkyblock.getMissions().canCompleteNoProgress(superiorPlayer, this))
            return;

        FarmingTracker farmingTracker = getOrCreate(superiorPlayer, s -> new FarmingTracker());

        if (farmingTracker == null)
            return;

        farmingTracker.track(blockTypeName);

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> superiorPlayer.runIfOnline(player -> {
            if (canComplete(superiorPlayer))
                SuperiorSkyblockAPI.getSuperiorSkyblock().getMissions().rewardMission(this, superiorPlayer, true);
        }), 2L);
    }

    private void handlePlantGrow(Block plantBlock, BlockState newState) {
        String blockTypeName = newState.getType().name();
        int age = newState.getRawData();
        if (newState.getBlockData() instanceof Ageable)
            age = ((Ageable) newState.getBlockData()).getAge();

        if (!isMissionPlant(blockTypeName))
            return;

        if (age < MAXIMUM_AGES.getOrDefault(blockTypeName, 0))
            return;

        Location placedBlockLocation = plantBlock.getLocation();

        switch (blockTypeName) {
            case "CACTUS":
            case "SUGAR_CANE":
            case "BAMBOO":
                placedBlockLocation = getLowestBlock(plantBlock);
                break;
            case "MELON":
            case "PUMPKIN":
                Material stemType = blockTypeName.equals("PUMPKIN") ? Material.PUMPKIN_STEM : Material.MELON_STEM;

                for (BlockFace blockFace : NEARBY_BLOCKS) {
                    Block nearbyBlock = plantBlock.getRelative(blockFace);
                    if (nearbyBlock.getType() == stemType) {
                        placedBlockLocation = nearbyBlock.getLocation();
                        break;
                    }
                }

                break;
        }

        SuperiorPlayer superiorPlayer;
        Island island = SuperiorSkyblockAPI.getIslandAt(placedBlockLocation);

        if (getIslandMission()) {
            if (island == null)
                return;

            superiorPlayer = island.getOwner();
        } else {
            return;
        }

        if (!superiorSkyblock.getMissions().canCompleteNoProgress(superiorPlayer, this))
            return;

        FarmingTracker farmingTracker = getOrCreate(superiorPlayer, s -> new FarmingTracker());

        if (farmingTracker == null)
            return;

        farmingTracker.track(blockTypeName);

        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> superiorPlayer.runIfOnline(player -> {
            if (canComplete(superiorPlayer))
                SuperiorSkyblockAPI.getSuperiorSkyblock().getMissions().rewardMission(this, superiorPlayer, true);
        }), 2L);
    }

    private static Location getLowestBlock(Block original) {
        Block lastSimilarBlock = original.getRelative(BlockFace.DOWN);

        Material originalType = lastSimilarBlock.getType();

        while (lastSimilarBlock.getType() == originalType) {
            lastSimilarBlock = lastSimilarBlock.getRelative(BlockFace.DOWN);
        }

        return lastSimilarBlock.getLocation().add(0, 1, 0);
    }

    private UUID getPlacerUUID(Player player) {
        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(player);

        if (getIslandMission()) {
            Island island = superiorPlayer.getIsland();

            if (island == null)
                return null;

            return island.getUniqueId();
        } else {
            return superiorPlayer.getUniqueId();
        }
    }

    private boolean isMissionPlant(String blockTypeName) {
        if (blockTypeName == null)
            return false;

        for (List<String> requiredPlant : requiredPlants.keySet()) {
            if (requiredPlant.contains(blockTypeName) || requiredPlant.contains("all") || requiredPlant.contains("ALL"))
                return true;
        }

        return false;
    }

    private String parsePlaceholders(FarmingTracker farmingTracker, String line) {
        Matcher matcher = percentagePattern.matcher(line);

        if (matcher.matches()) {
            String requiredBlock = matcher.group(2).toUpperCase();
            String requiredCustomBlock = matcher.group(2);
            Optional<Map.Entry<List<String>, Integer>> entry = requiredPlants.entrySet().stream().filter(e ->
                    e.getKey().contains(requiredBlock) || e.getKey().contains(requiredCustomBlock)
            ).findAny();
            if (entry.isPresent()) {
                line = line.replace("{percentage_" + matcher.group(2) + "}",
                        "" + (farmingTracker.getPlants(entry.get().getKey()) * 100) / entry.get().getValue());
            }
        }

        if ((matcher = valuePattern.matcher(line)).matches()) {
            String requiredBlock = matcher.group(2).toUpperCase();
            String requiredCustomBlock = matcher.group(2);
            Optional<Map.Entry<List<String>, Integer>> entry = requiredPlants.entrySet().stream().filter(e ->
                    e.getKey().contains(requiredBlock) || e.getKey().contains(requiredCustomBlock)
            ).findFirst();
            if (entry.isPresent()) {
                line = line.replace("{value_" + matcher.group(2) + "}",
                        "" + farmingTracker.getPlants(entry.get().getKey()));
            }
        }

        return ChatColor.translateAlternateColorCodes('&', line);
    }

    public static class FarmingTracker {

        private final Map<String, Integer> farmingTracker = new HashMap<>();

        void track(String blockType) {
            int newAmount = 1 + farmingTracker.getOrDefault(blockType, 0);
            farmingTracker.put(blockType, newAmount);
        }

        int getPlants(List<String> plants) {
            int amount = 0;
            boolean all = plants.contains("ALL") || plants.contains("all");

            for (String plant : farmingTracker.keySet()) {
                if (all || plants.contains(plant))
                    amount += farmingTracker.get(plant);
            }

            return amount;
        }

    }

    private static final class BlockPosition {

        private final String worldName;
        private final int x;
        private final int y;
        private final int z;

        static BlockPosition fromLocation(Location location) {
            return new BlockPosition(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }

        static BlockPosition fromBlock(Block block) {
            return new BlockPosition(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        }

        @Nullable
        static BlockPosition deserialize(String serialized) {
            String[] sections = serialized.split(";");
            if (sections.length != 4)
                return null;

            try {
                return new BlockPosition(sections[0], Integer.parseInt(sections[1]),
                        Integer.parseInt(sections[2]), Integer.parseInt(sections[3]));
            } catch (Exception ex) {
                return null;
            }
        }

        BlockPosition(String worldName, int x, int y, int z) {
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        String serialize() {
            return this.worldName + ";" + this.x + ";" + this.y + ";" + this.z;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BlockPosition that = (BlockPosition) o;
            return x == that.x && y == that.y && z == that.z && worldName.equals(that.worldName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(worldName, x, y, z);
        }

    }

}
