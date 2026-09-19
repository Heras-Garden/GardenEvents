package com.herasgarden.gardenevents.command;

import com.herasgarden.gardencore.api.ui.GardenMessages;
import com.herasgarden.gardenevents.EventService;
import com.herasgarden.gardenevents.EventService.VenueAdmissionResult;
import com.herasgarden.gardenevents.model.VenueRecord;
import com.herasgarden.gardenevents.model.VenueTicketRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class VenueCommand implements CommandExecutor, TabCompleter {
    private final EventService events;

    public VenueCommand(EventService events) {
        this.events = events;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            GardenMessages.send(sender, "Venue commands must be used in-game.");
            return true;
        }
        if (!player.hasPermission("gardenevents.venue")) {
            GardenMessages.send(player, "You do not have permission to use venue commands.");
            return true;
        }

        try {
            if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
                info(player);
                return true;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "create" -> create(player, args);
                case "list" -> list(player);
                case "buy" -> buy(player, args);
                case "admit" -> admit(player);
                case "later" -> later(player);
                case "settings" -> settings(player);
                case "setprice" -> setPrice(player, args);
                case "setopen" -> setOpen(player);
                case "setentrance" -> setEntrance(player);
                case "delete" -> delete(player);
                default -> usage(player);
            }
        } catch (NumberFormatException exception) {
            GardenMessages.send(player, "Use a whole number of Obols for the venue price.");
        } catch (IllegalArgumentException exception) {
            GardenMessages.send(player, exception.getMessage());
        } catch (SQLException exception) {
            GardenMessages.send(player, "The venue system could not update right now.");
        }
        return true;
    }

    private void create(Player player, String[] args) throws SQLException {
        if (!player.hasPermission("gardenevents.venue.create")) {
            GardenMessages.send(player, "You do not have permission to register venues.");
            return;
        }
        if (args.length < 2) {
            GardenMessages.send(player, "Stand at the venue entrance and use /venue create <name>.");
            return;
        }
        VenueRecord venue = events.createVenue(player, join(args, 1));
        GardenMessages.send(player, "Venue registered: " + venue.name() + ".");
        GardenMessages.send(player, "Event command key: " + venue.nameKey() + ".");
        GardenMessages.send(player, "Permanent admission is open by default. Use /venue settings inside the venue to change it.");
    }

    private void info(Player player) throws SQLException {
        VenueRecord venue = currentVenue(player);
        GardenMessages.send(player, venue.name() + " | key: " + venue.nameKey() + ".");
        GardenMessages.send(player, "Venue claim: " + venue.claimId().toString().substring(0, 8)
                + " | entrance saved at this venue.");
        GardenMessages.send(player, admissionSummary(venue));
    }

    private void list(Player player) throws SQLException {
        List<VenueRecord> venues = events.venues();
        if (venues.isEmpty()) {
            GardenMessages.send(player, "There are no registered Garden venues.");
            return;
        }
        GardenMessages.send(player, "Garden venues:");
        for (VenueRecord venue : venues) {
            String admission = venue.ticketRequired()
                    ? "ticket ⟡ " + venue.ticketPrice()
                    : "open admission";
            GardenMessages.send(player, venue.name() + " — " + venue.nameKey() + " | " + admission);
        }
    }

    private void buy(Player player, String[] args) throws SQLException {
        if (!player.hasPermission("gardenevents.venue.buy")) {
            GardenMessages.send(player, "You do not have permission to buy venue tickets.");
            return;
        }
        if (args.length < 2) {
            throw new IllegalArgumentException("Use /venue buy <venue>.");
        }
        VenueRecord venue = events.findVenue(join(args, 1))
                .orElseThrow(() -> new IllegalArgumentException("That venue does not exist."));
        VenueTicketRecord ticket = events.buyVenueTicket(player, venue);
        GardenMessages.send(player, "Admission ticket issued for " + venue.name()
                + " for ⟡ " + ticket.paidAmount() + ".");
        GardenMessages.send(player, "This is a physical one-admission ticket. Keep it anywhere in your inventory.");
    }

    private void admit(Player player) throws SQLException {
        VenueAdmissionResult result = events.admitPermanentVenue(player);
        GardenMessages.send(player, "Ticket accepted. Welcome to " + result.venue().name() + ".");
    }

    private void later(Player player) {
        if (events.declineAdmission(player)) {
            GardenMessages.send(player, "Admission skipped. Your ticket was not used.");
        } else {
            GardenMessages.send(player, "There is no pending admission to decline.");
        }
    }

    private void settings(Player player) throws SQLException {
        VenueRecord venue = currentVenue(player);
        if (!player.hasPermission("gardenevents.venue.create")) {
            throw new IllegalArgumentException("You do not have permission to manage venue settings.");
        }

        GardenMessages.send(player, "Venue settings: " + venue.name() + ".");
        GardenMessages.send(player, admissionSummary(venue));

        Component controls = GardenMessages.prefix()
                .append(Component.text("[Free Tickets]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/venue setprice 0"))
                        .hoverEvent(HoverEvent.showText(Component.text("Require free one-use admission tickets"))))
                .append(Component.space())
                .append(Component.text("[Open Access]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand("/venue setopen"))
                        .hoverEvent(HoverEvent.showText(Component.text("Disable permanent venue tickets"))))
                .append(Component.space())
                .append(Component.text("[Set Entrance]", NamedTextColor.YELLOW)
                        .clickEvent(ClickEvent.runCommand("/venue setentrance"))
                        .hoverEvent(HoverEvent.showText(Component.text("Use your current location as the venue entrance"))));
        player.sendMessage(controls);
        GardenMessages.send(player, "Set a paid permanent admission price with /venue setprice <obols>.");
    }

    private void setPrice(Player player, String[] args) throws SQLException {
        if (!player.hasPermission("gardenevents.venue.create")) {
            throw new IllegalArgumentException("You do not have permission to manage venue settings.");
        }
        if (args.length != 2) {
            throw new IllegalArgumentException("Use /venue setprice <obols>.");
        }
        long price = Long.parseLong(args[1].replace(",", ""));
        VenueRecord venue = events.setVenueTicketing(player, currentVenue(player), true, price);
        GardenMessages.send(player, "Permanent admission enabled for " + venue.name()
                + " at ⟡ " + venue.ticketPrice() + " per entry.");
    }

    private void setOpen(Player player) throws SQLException {
        if (!player.hasPermission("gardenevents.venue.create")) {
            throw new IllegalArgumentException("You do not have permission to manage venue settings.");
        }
        VenueRecord venue = events.setVenueTicketing(player, currentVenue(player), false, 0L);
        GardenMessages.send(player, "Permanent admission tickets disabled for " + venue.name() + ".");
        GardenMessages.send(player, "Scheduled events can still require their own event tickets.");
    }

    private void setEntrance(Player player) throws SQLException {
        if (!player.hasPermission("gardenevents.venue.create")) {
            throw new IllegalArgumentException("You do not have permission to manage venue settings.");
        }
        VenueRecord venue = events.setVenueEntrance(player, currentVenue(player));
        GardenMessages.send(player, "Venue entrance moved for " + venue.name() + ".");
    }

    private void delete(Player player) throws SQLException {
        if (!player.hasPermission("gardenevents.venue.create")) {
            GardenMessages.send(player, "You do not have permission to delete venues.");
            return;
        }
        VenueRecord venue = currentVenue(player);
        if (events.deleteVenue(player, venue)) {
            GardenMessages.send(player, "Deleted venue " + venue.name() + ".");
        }
    }

    private VenueRecord currentVenue(Player player) throws SQLException {
        return events.venueAt(player)
                .orElseThrow(() -> new IllegalArgumentException("Stand inside a registered Garden venue."));
    }

    private String admissionSummary(VenueRecord venue) {
        return venue.ticketRequired()
                ? "Permanent admission: ticket required | Price: ⟡ " + venue.ticketPrice() + "."
                : "Permanent admission: open | Scheduled events may still require event tickets.";
    }

    private String join(String[] args, int start) {
        return String.join(" ", Arrays.copyOfRange(args, start, args.length)).trim();
    }

    private void usage(Player player) {
        GardenMessages.send(player,
                "/venue create <name>, info, list, buy <venue>, admit, later, settings, "
                        + "setprice <obols>, setopen, setentrance, delete");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return match(args[0], List.of(
                    "create", "info", "list", "buy", "admit", "later", "settings",
                    "setprice", "setopen", "setentrance", "delete"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("buy")) {
            try {
                return match(args[1], events.venues().stream().map(VenueRecord::nameKey).toList());
            } catch (SQLException ignored) {
                return List.of();
            }
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("setprice")) {
            return List.of("0", "1", "5", "10", "25", "50");
        }
        return List.of();
    }

    private List<String> match(String prefix, List<String> values) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }
}
