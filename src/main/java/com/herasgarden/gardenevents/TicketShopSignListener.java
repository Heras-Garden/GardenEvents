package com.herasgarden.gardenevents;

import com.herasgarden.gardencore.api.ui.GardenMessages;
import com.herasgarden.gardenevents.model.EventRecord;
import com.herasgarden.gardenevents.model.TicketRecord;
import com.herasgarden.gardenevents.model.VenueRecord;
import com.herasgarden.gardenevents.model.VenueTicketRecord;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public final class TicketShopSignListener implements Listener {
    private final JavaPlugin plugin;
    private final EventService events;
    private final NamespacedKey eventShopKey;
    private final NamespacedKey venueShopKey;

    public TicketShopSignListener(JavaPlugin plugin, EventService events) {
        this.plugin = plugin;
        this.events = events;
        this.eventShopKey = new NamespacedKey(plugin, "ticket-shop-event");
        this.venueShopKey = new NamespacedKey(plugin, "ticket-shop-venue");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreate(SignChangeEvent event) {
        String marker = safe(event.getLine(0));
        if (!marker.equalsIgnoreCase("[EventTicket]") && !marker.equalsIgnoreCase("[VenueTicket]")) {
            return;
        }

        Player player = event.getPlayer();
        String reference = safe(event.getLine(1));
        if (reference.isBlank()) {
            GardenMessages.send(player,
                    marker.equalsIgnoreCase("[EventTicket]")
                            ? "Put the event name or event code on line 2."
                            : "Put the venue name or venue key on line 2.");
            return;
        }

        try {
            if (marker.equalsIgnoreCase("[EventTicket]")) {
                EventRecord record = events.findEvent(reference)
                        .orElseThrow(() -> new IllegalArgumentException("That scheduled event does not exist."));
                if (!events.canManageEvent(player, record)) {
                    throw new IllegalArgumentException("You do not manage that event.");
                }
                VenueRecord venue = events.venue(record);
                event.setLine(0, "[EventTicket]");
                event.setLine(1, record.id().toString().substring(0, 8));
                event.setLine(2, record.ticketPrice() == 0 ? "FREE" : "B " + record.ticketPrice());
                event.setLine(3, record.name());
                saveBinding(event, eventShopKey, record.id());
                GardenMessages.send(player,
                        "Event ticket shop created for " + record.name() + " at " + venue.name() + ".");
                return;
            }

            VenueRecord venue = events.findVenue(reference)
                    .orElseThrow(() -> new IllegalArgumentException("That registered venue does not exist."));
            if (!events.canManageVenue(player, venue)) {
                throw new IllegalArgumentException("You do not manage that venue.");
            }
            if (!venue.ticketRequired()) {
                throw new IllegalArgumentException(
                        "Enable permanent admission tickets with /venue setprice <obols> before creating this sign.");
            }
            event.setLine(0, "[VenueTicket]");
            event.setLine(1, venue.nameKey());
            event.setLine(2, venue.ticketPrice() == 0 ? "FREE" : "B " + venue.ticketPrice());
            event.setLine(3, venue.name());
            saveBinding(event, venueShopKey, venue.id());
            GardenMessages.send(player, "Permanent venue ticket shop created for " + venue.name() + ".");
        } catch (IllegalArgumentException exception) {
            GardenMessages.send(player, exception.getMessage());
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not create ticket shop sign: " + exception.getMessage());
            GardenMessages.send(player, "That ticket shop could not be created right now.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        if ((event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.LEFT_CLICK_BLOCK)
                || event.getClickedBlock() == null
                || !(event.getClickedBlock().getState() instanceof Sign sign)) {
            return;
        }

        UUID eventId = readId(sign, eventShopKey);
        UUID venueId = readId(sign, venueShopKey);
        if (eventId == null && venueId == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        try {
            if (eventId != null) {
                EventRecord record = events.findEvent(eventId)
                        .orElseThrow(() -> new IllegalArgumentException("That event is no longer available."));
                VenueRecord venue = events.venue(record);
                if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
                    GardenMessages.send(player,
                            record.name() + " at " + venue.name()
                                    + " | " + EventTime.format(record.startAt())
                                    + " | Ticket: ⟡ " + record.ticketPrice() + ".");
                    return;
                }
                List<TicketRecord> tickets = events.buyTickets(player, record, 1);
                GardenMessages.send(player,
                        "Ticket purchased for " + record.name() + ". The physical ticket is now in your inventory.");
                return;
            }

            VenueRecord venue = events.findVenue(venueId)
                    .orElseThrow(() -> new IllegalArgumentException("That venue is no longer available."));
            if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
                GardenMessages.send(player,
                        venue.name() + " permanent admission | Ticket: ⟡ " + venue.ticketPrice() + ".");
                return;
            }
            VenueTicketRecord ticket = events.buyVenueTicket(player, venue);
            GardenMessages.send(player,
                    "Admission ticket purchased for " + venue.name()
                            + " for ⟡ " + ticket.paidAmount() + ".");
        } catch (IllegalArgumentException exception) {
            GardenMessages.send(player, exception.getMessage());
        } catch (SQLException exception) {
            GardenMessages.send(player, "The ticket shop could not complete that purchase right now.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!(event.getBlock().getState() instanceof Sign sign)) return;
        UUID eventId = readId(sign, eventShopKey);
        UUID venueId = readId(sign, venueShopKey);
        if (eventId == null && venueId == null) return;

        try {
            boolean allowed;
            if (eventId != null) {
                EventRecord record = events.findEvent(eventId).orElse(null);
                allowed = record == null
                        ? event.getPlayer().hasPermission("gardenevents.admin")
                        : events.canManageEvent(event.getPlayer(), record);
            } else {
                VenueRecord venue = events.findVenue(venueId).orElse(null);
                allowed = venue == null
                        ? event.getPlayer().hasPermission("gardenevents.admin")
                        : events.canManageVenue(event.getPlayer(), venue);
            }
            if (!allowed) {
                event.setCancelled(true);
                GardenMessages.send(event.getPlayer(), "You do not manage this ticket shop.");
            }
        } catch (SQLException exception) {
            event.setCancelled(true);
            GardenMessages.send(event.getPlayer(), "Ticket shop ownership could not be checked right now.");
        }
    }

    private void saveBinding(SignChangeEvent event, NamespacedKey key, UUID id) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!(event.getBlock().getState() instanceof Sign sign)) return;
            sign.getPersistentDataContainer().set(key, PersistentDataType.STRING, id.toString());
            sign.update(true, false);
        });
    }

    private UUID readId(Sign sign, NamespacedKey key) {
        String raw = sign.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
