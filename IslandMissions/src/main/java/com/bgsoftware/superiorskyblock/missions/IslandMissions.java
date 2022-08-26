package com.bgsoftware.superiorskyblock.missions;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblock;
import com.bgsoftware.superiorskyblock.api.events.*;
import com.bgsoftware.superiorskyblock.api.missions.Mission;
import com.bgsoftware.superiorskyblock.api.missions.MissionLoadException;
import com.bgsoftware.superiorskyblock.api.scripts.IScriptEngine;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import javax.script.SimpleBindings;
import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("unused")
public final class IslandMissions extends Mission<Boolean> implements Listener {

    private final Map<String, Boolean> missionEvents = new HashMap<>();

    private Placeholders placeholders = new Placeholders_None();
    private String successCheck;
    private JavaPlugin plugin;
    private SuperiorSkyblock superiorSkyblock;

    @Override
    public void load(JavaPlugin plugin, ConfigurationSection section) throws MissionLoadException {
        this.plugin = plugin;
        this.superiorSkyblock = (SuperiorSkyblock) plugin;

        if (!section.contains("events"))
            throw new MissionLoadException("You must have the \"events\" section in the config.");

        for (String event : section.getStringList("events")) {
            if (event.toLowerCase().endsWith("-target"))
                missionEvents.put(event.split("-")[0], true);
            else
                missionEvents.put(event, false);
        }

        successCheck = section.getString("success-check", "true");

        Bukkit.getPluginManager().registerEvents(this, plugin);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"))
                placeholders = new Placeholders_PAPI();
        }, 1L);

    }

    @Override
    public double getProgress(SuperiorPlayer superiorPlayer) {
        return get(superiorPlayer) == null ? 0.0 : 1.0;
    }

    @Override
    public int getProgressValue(SuperiorPlayer superiorPlayer) {
        return 0;
    }

    @Override
    public void onComplete(SuperiorPlayer superiorPlayer) {
        onCompleteFail(superiorPlayer);
    }

    @Override
    public void onCompleteFail(SuperiorPlayer superiorPlayer) {
        clearData(superiorPlayer);
    }

    /*
     *  Events
     */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandDeposit(IslandBankDepositEvent e) {
        tryComplete(e, e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandWithdraw(IslandBankWithdrawEvent e) {
        tryComplete(e, e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandBiomeChange(IslandBiomeChangeEvent e) {
        tryComplete(e, e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandCoop(IslandCoopPlayerEvent e) {
        tryComplete(e, e.getPlayer(), e.getTarget());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandCreate(IslandCreateEvent e) {
        tryComplete(e, e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandDisband(IslandDisbandEvent e) {
        tryComplete(e, e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandEnter(IslandEnterEvent e) {
        tryComplete(e, e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandEnterProtected(IslandEnterProtectedEvent e) {
        tryComplete(e, e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandInvite(IslandInviteEvent e) {
        tryComplete(e, e.getPlayer(), e.getTarget());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandJoin(IslandJoinEvent e) {
        tryComplete(e, e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandKick(IslandKickEvent e) {
        tryComplete(e, e.getPlayer(), e.getTarget());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandLeave(IslandLeaveEvent e) {
        tryComplete(e, e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandLeaveProtected(IslandLeaveProtectedEvent e) {
        tryComplete(e, e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandQuit(IslandQuitEvent e) {
        tryComplete(e, e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandTransfer(IslandTransferEvent e) {
        tryComplete(e, e.getOldOwner(), e.getNewOwner());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandUncoop(IslandUncoopPlayerEvent e) {
        tryComplete(e, e.getPlayer(), e.getTarget());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandCalculate(IslandWorthCalculatedEvent e) {
        tryComplete(e, e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandWorthUpdate(IslandWorthUpdateEvent e) {
        tryComplete(e, e.getIsland().getOwner());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandSchematicPaste(IslandSchematicPasteEvent e) {
        tryComplete(e, e.getIsland().getOwner());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandMissionComplete(MissionCompleteEvent e) {
        tryComplete(e, e.getPlayer());
    }

    private void tryComplete(Event event, SuperiorPlayer superiorPlayer) {
        tryComplete(event, superiorPlayer, null);
    }

    private void tryComplete(Event event, SuperiorPlayer superiorPlayer, SuperiorPlayer targetPlayer) {
        String eventName = event.getClass().getSimpleName();
        if (missionEvents.containsKey(eventName)) {
            boolean success = false;

            SimpleBindings bindings = new SimpleBindings();
            bindings.put("event", event);

            IScriptEngine scriptEngine = superiorSkyblock.getScriptEngine();

            if (!successCheck.equalsIgnoreCase("false")) {
                try {
                    String result = placeholders.parse(scriptEngine.eval(successCheck, bindings) + "", superiorPlayer.asOfflinePlayer());
                    success = Boolean.parseBoolean(result);
                } catch (Exception ex) {
                    plugin.getLogger().info("&cError occurred while checking for success condition for IslandMission.");
                    plugin.getLogger().info("&cCurrent Script Engine: " + scriptEngine);
                    plugin.getLogger().info("&cPlaceholders: " + placeholders);
                    ex.printStackTrace();
                }
            } else {
                success = true;
            }

            if (success) {
                SuperiorPlayer rewardedPlayer = !missionEvents.get(eventName) ? superiorPlayer : targetPlayer;
                if (rewardedPlayer != null) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        insertData(rewardedPlayer, true);
                        superiorSkyblock.getMissions().rewardMission(this, rewardedPlayer, true);
                    }, 5L);
                }
            }
        }
    }

    private interface Placeholders {

        String parse(String string, OfflinePlayer offlinePlayer);

    }

    private static final class Placeholders_PAPI implements Placeholders {

        @Override
        public String parse(String string, OfflinePlayer offlinePlayer) {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(offlinePlayer, string);
        }

    }

    private static final class Placeholders_None implements Placeholders {

        @Override
        public String parse(String string, OfflinePlayer offlinePlayer) {
            return string;
        }

    }

}
