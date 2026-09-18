package com.herasgarden.gardenevents.command;

import com.herasgarden.gardencore.api.ui.GardenMessages;
import com.herasgarden.gardenevents.EventService;
import com.herasgarden.gardenevents.model.VenueRecord;
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
                case "delete" -> delete(player);
                default -> usage(player);
            }
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
    }

    private void info(Player player) throws SQLException {
        VenueRecord venue = events.venueAt(player)
                .orElseThrow(() -> new IllegalArgumentException("Stand inside a registered Garden venue."));
        GardenMessages.send(player, venue.name() + " | key: " + venue.nameKey() + ".");
        GardenMessages.send(player, "Venue claim: " + venue.claimId().toString().substring(0, 8)
                + " | entrance saved at this venue.");
    }

    private void list(Player player) throws SQLException {
        List<VenueRecord> venues = events.venues();
        if (venues.isEmpty()) {
            GardenMessages.send(player, "There are no registered Garden venues.");
            return;
        }
        GardenMessages.send(player, "Garden venues:");
        for (VenueRecord venue : venues) {
            GardenMessages.send(player, venue.name() + " — " + venue.nameKey());
        }
    }

    private void delete(Player player) throws SQLException {
        if (!player.hasPermission("gardenevents.venue.create")) {
            GardenMessages.send(player, "You do not have permission to delete venues.");
            return;
        }
        VenueRecord venue = events.venueAt(player)
                .orElseThrow(() -> new IllegalArgumentException("Stand inside the venue you want to delete."));
        if (events.deleteVenue(player, venue)) {
            GardenMessages.send(player, "Deleted venue " + venue.name() + ".");
        }
    }

    private String join(String[] args, int start) {
        return String.join(" ", Arrays.copyOfRange(args, start, args.length)).trim();
    }

    private void usage(Player player) {
        GardenMessages.send(player, "/venue create <name>, info, list, delete");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("create", "info", "list", "delete").stream()
                .filter(value -> value.startsWith(prefix))
                .toList();
    }
}
