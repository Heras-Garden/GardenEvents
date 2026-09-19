package com.herasgarden.gardenevents;

import com.herasgarden.gardencore.api.GardenPlatform;
import com.herasgarden.gardencore.api.land.LandAccessService;
import com.herasgarden.gardencore.api.ui.GardenMessages;
import com.herasgarden.gardenevents.command.EventCommand;
import com.herasgarden.gardenevents.command.VenueCommand;
import com.herasgarden.gardenevents.model.EventRecord;
import com.herasgarden.gardenevents.model.VenueRecord;
import com.herasgarden.gardenevents.storage.EventsSchema;
import org.bukkit.Sound;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.List;

public final class GardenEvents extends JavaPlugin {
    private static final long TEN_MINUTES = 10L * 60L * 1000L;

    private EventService events;
    private long lastStartCheck;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        GardenPlatform platform = service(GardenPlatform.class);
        LandAccessService land = service(LandAccessService.class);
        if (platform == null || land == null) {
            getLogger().severe("GardenCore/GardenLands event platform services are unavailable.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            EventsSchema.ensure(platform.storage());
        } catch (SQLException exception) {
            getLogger().severe("GardenEvents could not prepare storage: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        events = new EventService(
                this,
                platform,
                land,
                getConfig().getInt("events.max-capacity", 1000),
                getConfig().getInt("events.max-start-days", 30),
                getConfig().getInt("events.max-duration-hours", 24),
                getConfig().getInt("admission.opens-before-minutes", 30),
                getConfig().getInt("admission.closes-after-end-minutes", 15),
                getConfig().getDouble("admission.radius-blocks", 8.0)
        );

        VenueCommand venueCommand = new VenueCommand(events);
        PluginCommand venue = getCommand("venue");
        if (venue != null) {
            venue.setExecutor(venueCommand);
            venue.setTabCompleter(venueCommand);
        }

        EventCommand eventCommand = new EventCommand(events);
        PluginCommand event = getCommand("event");
        if (event != null) {
            event.setExecutor(eventCommand);
            event.setTabCompleter(eventCommand);
        }

        getServer().getPluginManager().registerEvents(new VenueAdmissionListener(events), this);
        getServer().getPluginManager().registerEvents(new TicketShopSignListener(this, events), this);

        lastStartCheck = System.currentTimeMillis();
        getServer().getScheduler().runTaskTimer(this, this::announceEventStarts, 20L, 20L);

        getLogger().info("GardenEvents enabled. Venues, scheduled events, ticket sales, and admission are active.");
    }

    private void announceEventStarts() {
        long now = System.currentTimeMillis();
        long previous = lastStartCheck;
        lastStartCheck = now;

        try {
            List<EventRecord> tenMinuteWarnings = events.startingEvents(
                    previous + TEN_MINUTES,
                    now + TEN_MINUTES
            );
            for (EventRecord event : tenMinuteWarnings) {
                VenueRecord venue = events.venue(event);
                for (Player player : getServer().getOnlinePlayers()) {
                    GardenMessages.send(
                            player,
                            event.name() + " at " + venue.name() + " starts in 10 minutes."
                    );
                }
            }

            List<EventRecord> starting = events.startingEvents(previous, now);
            for (EventRecord event : starting) {
                VenueRecord venue = events.venue(event);
                for (Player player : getServer().getOnlinePlayers()) {
                    if (!events.hasValidPhysicalTicket(player, event.id())
                            && !events.wasAdmitted(player, event.id())) {
                        continue;
                    }
                    player.playSound(
                            player.getLocation(),
                            Sound.BLOCK_NOTE_BLOCK_CHIME,
                            1.0f,
                            1.0f
                    );
                    GardenMessages.send(
                            player,
                            "Event started: " + event.name() + " at " + venue.name() + "."
                    );
                }
            }
        } catch (SQLException exception) {
            getLogger().warning("Could not check event announcements: " + exception.getMessage());
        }
    }

    public EventService events() {
        return events;
    }

    private <T> T service(Class<T> type) {
        RegisteredServiceProvider<T> registration = getServer().getServicesManager().getRegistration(type);
        return registration == null ? null : registration.getProvider();
    }
}
