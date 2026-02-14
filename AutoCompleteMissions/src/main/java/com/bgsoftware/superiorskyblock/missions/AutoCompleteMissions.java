package com.bgsoftware.superiorskyblock.missions;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblock;
import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.missions.Mission;
import com.bgsoftware.superiorskyblock.api.missions.MissionLoadException;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@SuppressWarnings("unused")
public final class AutoCompleteMissions extends Mission<AutoCompleteMissions.MissionTracker> {

    private static final SuperiorSkyblock superiorSkyblock = SuperiorSkyblockAPI.getSuperiorSkyblock();

    private JavaPlugin plugin;
    private BukkitTask checkTask;
    private final int checkInterval = 100;

    @Override
    public void load(JavaPlugin plugin, ConfigurationSection section) throws MissionLoadException {
        this.plugin = plugin;

        if (getRequiredMissions().isEmpty()) {
            throw new MissionLoadException("AutoCompleteMissions must have 'required-missions' configured!");
        }

        startCheckTask();
    }

    @Override
    public void unload() {
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
        super.unload();
    }

    private void startCheckTask() {
        checkTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            for (Map.Entry<SuperiorPlayer, MissionTracker> entry : entrySet()) {
                if (canComplete(entry.getKey())) {
                    SuperiorSkyblockAPI.getSuperiorSkyblock().getMissions().rewardMission(this, entry.getKey(), true);
                }
            }
        }, checkInterval, checkInterval);
    }

    @Override
    public double getProgress(SuperiorPlayer superiorPlayer) {
        return 1.0d;
    }

    @Override
    public int getProgressValue(SuperiorPlayer superiorPlayer) {
        return 1;
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
    }

    @Override
    public void loadProgress(ConfigurationSection section) {
    }

    public static class MissionTracker {}
}
