package com.herasgarden.gardenevents;

import com.herasgarden.gardencore.api.ui.GardenMessages;
import com.herasgarden.gardenevents.model.EventRecord;
import com.herasgarden.gardenevents.model.VenueRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VenueAdmissionListener implements Listener {
    private final EventService events;
    private final Map<UUID, UUID> lastVenue = new ConcurrentHashMap<>();

    public VenueAdmissionListener(EventService events) {
        this.events = events;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null
                || (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ())) {
            return;
        }

        Player player = event.getPlayer();
        try {
            VenueRecord venue = events.venueAt(player).orElse(null);
            UUID previous = lastVenue.get(player.getUniqueId());

            if (venue == null) {
                lastVenue.remove(player.getUniqueId());
                return;
            }
            if (venue.id().equals(previous)) {
                return;
            }

            lastVenue.put(player.getUniqueId(), venue.id());
            EventRecord active = events.admittableEventAt(player).orElse(null);
            if (active == null) {
                return;
            }

            Component prompt = GardenMessages.prefix()
                    .append(Component.text(
                            "You entered " + venue.name() + " for " + active.name()
                                    + ". Would you like to admit now? ",
                            NamedTextColor.WHITE
                    ))
                    .append(Component.text("[Admit]", NamedTextColor.GREEN)
                            .clickEvent(ClickEvent.runCommand("/event admit")))
                    .append(Component.space())
                    .append(Component.text("[Not Now]", NamedTextColor.GRAY)
                            .clickEvent(ClickEvent.runCommand("/event later")));
            player.sendMessage(prompt);
        } catch (SQLException ignored) {
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastVenue.remove(event.getPlayer().getUniqueId());
    }
}
