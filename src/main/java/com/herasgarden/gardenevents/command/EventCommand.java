package com.herasgarden.gardenevents.command;

import com.herasgarden.gardencore.api.ui.GardenMessages;
import com.herasgarden.gardenevents.EventService;
import com.herasgarden.gardenevents.EventTime;
import com.herasgarden.gardenevents.EventService.AdmissionResult;
import com.herasgarden.gardenevents.EventService.CancellationResult;
import com.herasgarden.gardenevents.model.EventRecord;
import com.herasgarden.gardenevents.model.TicketRecord;
import com.herasgarden.gardenevents.model.VenueRecord;
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
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class EventCommand implements CommandExecutor, TabCompleter {
    private final EventService events;

    public EventCommand(EventService events) {
        this.events = events;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            GardenMessages.send(sender, "Event commands must be used in-game.");
            return true;
        }
        if (!player.hasPermission("gardenevents.event")) {
            GardenMessages.send(player, "You do not have permission to use event commands.");
            return true;
        }

        try {
            if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
                list(player);
                return true;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "create" -> create(player, args);
                case "info" -> info(player, args);
                case "buy" -> buy(player, args);
                case "issue" -> issue(player, args);
                case "admit" -> admit(player);
                case "later" -> later(player);
                case "cancel" -> cancel(player, args);
                default -> usage(player);
            }
        } catch (ArithmeticException exception) {
            GardenMessages.send(player, "That value is too large.");
        } catch (NumberFormatException exception) {
            GardenMessages.send(player, "Use whole numbers where an amount is required.");
        } catch (IllegalArgumentException exception) {
            GardenMessages.send(player, exception.getMessage());
        } catch (SQLException exception) {
            GardenMessages.send(player, "The event system could not update right now.");
        }
        return true;
    }

    private void create(Player player, String[] args) throws SQLException {
        if (!player.hasPermission("gardenevents.event.create")) {
            GardenMessages.send(player, "You do not have permission to create events.");
            return;
        }
        if (args.length < 7) {
            GardenMessages.send(player,
                    "Use /event create <venue-key> <start-in> <duration> <price> <capacity> <name>.");
            return;
        }

        String venueKey = args[1];
        long startIn = parseDuration(args[2]);
        long duration = parseDuration(args[3]);
        long price = parseNonNegative(args[4], "Ticket price");
        int capacity = Integer.parseInt(args[5].replace(",", ""));
        String name = String.join(" ", Arrays.copyOfRange(args, 6, args.length)).trim();

        EventRecord event = events.createEvent(player, venueKey, startIn, duration, price, capacity, name);
        GardenMessages.send(player, "Event created: " + event.name() + ".");
        GardenMessages.send(player, "Starts: " + EventTime.format(event.startAt())
                + " | Ends: " + EventTime.format(event.endAt()) + ".");
        GardenMessages.send(player, "Ticket: ⟡ " + event.ticketPrice() + ".");
    }

    private void list(Player player) throws SQLException {
        List<EventRecord> upcoming = events.upcomingEvents();
        if (upcoming.isEmpty()) {
            GardenMessages.send(player, "There are no upcoming Garden events.");
            return;
        }

        GardenMessages.send(player, "Upcoming Garden events:");
        for (EventRecord event : upcoming) {
            VenueRecord venue = events.venue(event);
            int sold = events.soldTickets(event.id());
            Component row = GardenMessages.prefix()
                    .append(Component.text(event.name(), NamedTextColor.WHITE)
                            .clickEvent(ClickEvent.runCommand("/event info " + event.name()))
                            .hoverEvent(HoverEvent.showText(Component.text("View event details"))))
                    .append(Component.text(" @ " + venue.name(), NamedTextColor.GRAY))
                    .append(Component.text(" | " + EventTime.format(event.startAt()), NamedTextColor.GRAY))
                    .append(Component.text(" | " + sold + "/" + event.capacity(), NamedTextColor.GRAY))
                    .append(Component.text(" | ⟡ " + event.ticketPrice(), NamedTextColor.GRAY))
                    .append(Component.text("  "))
                    .append(Component.text("[Info]", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.runCommand("/event info " + event.name())))
                    .append(Component.text(" "))
                    .append(Component.text("[Buy]", NamedTextColor.GREEN)
                            .clickEvent(ClickEvent.runCommand("/event buy " + event.name())));
            player.sendMessage(row);
        }
    }

    private void info(Player player, String[] args) throws SQLException {
        EventRecord event = requireEvent(args, 1, args.length);
        VenueRecord venue = events.venue(event);
        int sold = events.soldTickets(event.id());
        GardenMessages.send(player, event.name() + " @ " + venue.name() + ".");
        GardenMessages.send(player, "Starts: " + EventTime.format(event.startAt())
                + " | Ends: " + EventTime.format(event.endAt()) + ".");
        GardenMessages.send(player, "Tickets: " + sold + "/" + event.capacity()
                + " | Price: ⟡ " + event.ticketPrice()
                + " | Status: " + event.status() + ".");
        GardenMessages.send(player, "Event code: " + shortId(event) + ".");
    }

    private void buy(Player player, String[] args) throws SQLException {
        if (!player.hasPermission("gardenevents.event.buy")) {
            GardenMessages.send(player, "You do not have permission to buy event tickets.");
            return;
        }
        if (args.length < 2) {
            throw new IllegalArgumentException("Choose an event from /event list.");
        }

        int amount = 1;
        int eventEnd = args.length;
        if (args.length >= 3 && isInteger(args[args.length - 1])) {
            amount = Integer.parseInt(args[args.length - 1]);
            eventEnd--;
        }

        EventRecord event = requireEvent(args, 1, eventEnd);
        List<TicketRecord> tickets = events.buyTickets(player, event, amount);
        GardenMessages.send(player, tickets.size() + " ticket"
                + (tickets.size() == 1 ? "" : "s") + " issued for " + event.name() + ".");
        GardenMessages.send(player, "Tickets are physical bearer items. Bring one to the venue entrance for admission.");
    }

    private void issue(Player player, String[] args) throws SQLException {
        if (!player.hasPermission("gardenevents.event.create")) {
            GardenMessages.send(player, "You do not have permission to issue event tickets.");
            return;
        }
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "Use /event issue <amount> <event title> or /event issue <event title> [amount].");
        }

        int amount = 1;
        int eventStart = 1;
        int eventEnd = args.length;

        if (isInteger(args[1])) {
            amount = Integer.parseInt(args[1]);
            eventStart = 2;
        } else if (args.length >= 3 && isInteger(args[args.length - 1])) {
            amount = Integer.parseInt(args[args.length - 1]);
            eventEnd--;
        }

        EventRecord event = requireEvent(args, eventStart, eventEnd);
        List<TicketRecord> tickets = events.issueHostTickets(player, event, amount);
        GardenMessages.send(player, "Issued " + tickets.size() + " giveaway ticket"
                + (tickets.size() == 1 ? "" : "s") + " for " + event.name() + ".");
    }

    private void admit(Player player) throws SQLException {
        AdmissionResult result = events.admit(player);
        GardenMessages.send(player, "Ticket accepted. Welcome to " + result.event().name() + ".");
    }

    private void later(Player player) {
        if (events.declineAdmission(player)) {
            GardenMessages.send(player, "Admission skipped. Your ticket was not used.");
        } else {
            GardenMessages.send(player, "There is no pending admission to decline.");
        }
    }

    private void cancel(Player player, String[] args) throws SQLException {
        if (!player.hasPermission("gardenevents.event.create")) {
            GardenMessages.send(player, "You do not have permission to cancel events.");
            return;
        }
        EventRecord event = requireEvent(args, 1, args.length);
        CancellationResult cancelled = events.cancel(player, event);
        GardenMessages.send(player, "Cancelled " + cancelled.event().name()
                + ". Refunded " + cancelled.refundedTickets() + " ticket"
                + (cancelled.refundedTickets() == 1 ? "" : "s")
                + " for ⟡ " + cancelled.refundTotal() + " total.");
    }

    private EventRecord requireEvent(String[] args, int from, int to) throws SQLException {
        if (to <= from) {
            throw new IllegalArgumentException("Choose an event from /event list.");
        }
        String value = String.join(" ", Arrays.copyOfRange(args, from, to)).trim();
        return events.findEvent(value)
                .orElseThrow(() -> new IllegalArgumentException("That event does not exist."));
    }

    private long parseNonNegative(String value, String label) {
        long parsed = Long.parseLong(value.replace(",", ""));
        if (parsed < 0) {
            throw new IllegalArgumentException(label + " cannot be negative.");
        }
        return parsed;
    }

    private long parseDuration(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("A duration is required.");
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        long multiplier;
        String number;
        if (value.endsWith("d")) {
            multiplier = Duration.ofDays(1).toMillis();
            number = value.substring(0, value.length() - 1);
        } else if (value.endsWith("h")) {
            multiplier = Duration.ofHours(1).toMillis();
            number = value.substring(0, value.length() - 1);
        } else if (value.endsWith("m")) {
            multiplier = Duration.ofMinutes(1).toMillis();
            number = value.substring(0, value.length() - 1);
        } else {
            multiplier = Duration.ofMinutes(1).toMillis();
            number = value;
        }
        long amount = Long.parseLong(number);
        if (amount <= 0) {
            throw new IllegalArgumentException("Durations must be positive.");
        }
        return Math.multiplyExact(amount, multiplier);
    }

    private boolean isInteger(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private String shortId(EventRecord event) {
        return event.id().toString().substring(0, 8);
    }

    private void usage(Player player) {
        GardenMessages.send(player,
                "/event create <venue-key> <start-in> <duration> <price> <capacity> <name>, "
                        + "list, info <event>, buy <event> [amount], issue <amount> <event>, admit, later, cancel <event>");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return match(args[0], List.of("create", "list", "info", "buy", "issue", "admit", "later", "cancel"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
            try {
                return match(args[1], events.venues().stream().map(VenueRecord::nameKey).toList());
            } catch (SQLException ignored) {
                return List.of();
            }
        }
        if (args.length == 2
                && (args[0].equalsIgnoreCase("info")
                || args[0].equalsIgnoreCase("buy")
                || args[0].equalsIgnoreCase("cancel"))) {
            try {
                return match(args[1], events.upcomingEvents().stream().map(EventRecord::name).toList());
            } catch (SQLException ignored) {
                return List.of();
            }
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("issue")) {
            return List.of("1", "2", "5", "10", "16", "32");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("issue")) {
            try {
                return match(args[2], events.upcomingEvents().stream().map(EventRecord::name).toList());
            } catch (SQLException ignored) {
                return List.of();
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            return List.of("10m", "30m", "1h", "1d");
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("create")) {
            return List.of("30m", "1h", "2h", "4h");
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
