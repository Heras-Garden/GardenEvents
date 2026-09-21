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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VenueAdmissionListener implements Listener {
    private final EventService events;
    private final Map<UUID, UUID> lastVenue = new ConcurrentHashMap<>();

    public VenueAdmissionListener(EventService events) {
        this.events = events;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null
                || (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ())) {
            return;
        }

        Player player = event.getPlayer();
        VenueRecord fromVenue = events.venueAtCached(event.getFrom()).orElse(null);
        VenueRecord venue = events.venueAtCached(event.getTo()).orElse(null);
            UUID previous = lastVenue.get(player.getUniqueId());

            if (venue == null) {
                lastVenue.remove(player.getUniqueId());
                events.clearPendingAdmission(player);
                return;
            }
            if (venue.id().equals(previous)) {
                if (events.hasPendingAdmission(player)) {
                    event.setTo(event.getFrom());
                }
                return;
            }

            boolean crossingBoundary = fromVenue == null || !fromVenue.id().equals(venue.id());
            if (!crossingBoundary) {
                lastVenue.put(player.getUniqueId(), venue.id());
                return;
            }

            EventRecord active = events.eventAcceptingAdmissionAtCached(venue).orElse(null);
            if (active != null) {
                if (events.canBypassAdmission(player, venue, active)) {
                    lastVenue.put(player.getUniqueId(), venue.id());
                    return;
                }
                if (!events.hasValidPhysicalTicketCached(player, active.id())) {
                    blockEntry(event);
                    lastVenue.remove(player.getUniqueId());
                    events.clearPendingAdmission(player);
                    GardenMessages.send(player,
                            "You need a valid physical ticket for " + active.name()
                                    + " to enter " + venue.name() + ".");
                    return;
                }

                events.rememberAdmissionExit(player, event.getFrom());
                lastVenue.put(player.getUniqueId(), venue.id());
                player.sendMessage(eventPrompt(venue, active));
                return;
            }

            if (venue.ticketRequired()) {
                if (events.canBypassAdmission(player, venue, null)) {
                    lastVenue.put(player.getUniqueId(), venue.id());
                    return;
                }
                if (!events.hasValidPhysicalVenueTicketCached(player, venue.id())) {
                    blockEntry(event);
                    lastVenue.remove(player.getUniqueId());
                    events.clearPendingAdmission(player);
                    GardenMessages.send(player,
                            "You need a valid admission ticket to enter " + venue.name() + ".");
                    return;
                }

                events.rememberAdmissionExit(player, event.getFrom());
                lastVenue.put(player.getUniqueId(), venue.id());
                player.sendMessage(venuePrompt(venue));
                return;
            }

            lastVenue.put(player.getUniqueId(), venue.id());
    }

    private void blockEntry(PlayerMoveEvent event) {
        event.setTo(event.getFrom());
    }

    private Component eventPrompt(VenueRecord venue, EventRecord active) {
        return GardenMessages.prefix()
                .append(Component.text(
                        "You entered " + venue.name() + " for " + active.name()
                                + ". Admit your ticket now? ",
                        NamedTextColor.WHITE
                ))
                .append(Component.text("[Admit]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/event admit")))
                .append(Component.space())
                .append(Component.text("[Not Now]", NamedTextColor.GRAY)
                        .clickEvent(ClickEvent.runCommand("/event later")));
    }

    private Component venuePrompt(VenueRecord venue) {
        return GardenMessages.prefix()
                .append(Component.text(
                        venue.name() + " requires admission. Admit your ticket now? ",
                        NamedTextColor.WHITE
                ))
                .append(Component.text("[Admit]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/venue admit")))
                .append(Component.space())
                .append(Component.text("[Not Now]", NamedTextColor.GRAY)
                        .clickEvent(ClickEvent.runCommand("/venue later")));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastVenue.remove(event.getPlayer().getUniqueId());
        events.clearPendingAdmission(event.getPlayer());
    }
}
