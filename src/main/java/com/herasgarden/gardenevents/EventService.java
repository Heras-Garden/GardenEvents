package com.herasgarden.gardenevents;

import com.herasgarden.gardencore.api.GardenPlatform;
import com.herasgarden.gardencore.api.integration.IntegrationEventType;
import com.herasgarden.gardencore.api.land.LandAccessService;
import com.herasgarden.gardencore.api.order.GardenOrder;
import com.herasgarden.gardencore.api.order.OrderState;
import com.herasgarden.gardencore.api.order.OrderType;
import com.herasgarden.gardenevents.model.EventRecord;
import com.herasgarden.gardenevents.model.TicketRecord;
import com.herasgarden.gardenevents.model.VenueRecord;
import com.herasgarden.gardenevents.model.VenueTicketRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class EventService {
    private final JavaPlugin plugin;
    private final GardenPlatform platform;
    private final LandAccessService land;
    private final int maxCapacity;
    private final long maxStartDelayMillis;
    private final long maxDurationMillis;
    private final long admissionEarlyMillis;
    private final long admissionLateMillis;
    private final double admissionRadiusSquared;
    private final NamespacedKey ticketKey;
    private final NamespacedKey eventKey;
    private final NamespacedKey venueTicketKey;
    private final NamespacedKey venueKey;
    private final Map<UUID, Object> purchaseLocks = new ConcurrentHashMap<>();
    private final Map<UUID, Location> pendingAdmissionExit = new ConcurrentHashMap<>();

    public EventService(
            JavaPlugin plugin,
            GardenPlatform platform,
            LandAccessService land,
            int maxCapacity,
            int maxStartDays,
            int maxDurationHours,
            int admissionEarlyMinutes,
            int admissionLateMinutes,
            double admissionRadius
    ) {
        this.plugin = plugin;
        this.platform = platform;
        this.land = land;
        this.maxCapacity = Math.max(1, maxCapacity);
        this.maxStartDelayMillis = Math.max(1L, maxStartDays) * 24L * 60L * 60L * 1000L;
        this.maxDurationMillis = Math.max(1L, maxDurationHours) * 60L * 60L * 1000L;
        this.admissionEarlyMillis = Math.max(0L, admissionEarlyMinutes) * 60_000L;
        this.admissionLateMillis = Math.max(0L, admissionLateMinutes) * 60_000L;
        double radius = Math.max(1.0, admissionRadius);
        this.admissionRadiusSquared = radius * radius;
        this.ticketKey = new NamespacedKey(plugin, "ticket-id");
        this.eventKey = new NamespacedKey(plugin, "event-id");
        this.venueTicketKey = new NamespacedKey(plugin, "venue-ticket-id");
        this.venueKey = new NamespacedKey(plugin, "venue-id");
    }

    public VenueRecord createVenue(Player owner, String name) throws SQLException {
        String cleanName = cleanName(name, 64, "Venue");
        String key = key(cleanName);
        Block block = owner.getLocation().getBlock();
        UUID claimId = land.claimIdAt(block)
                .orElseThrow(() -> new IllegalArgumentException("Stand inside the Garden claim for this venue."));
        if (!land.canManage(owner, block) && !owner.hasPermission("gardenevents.admin")) {
            throw new IllegalArgumentException("You must manage this Garden claim to register it as a venue.");
        }
        String claimType = land.claimTypeAt(block).orElse("");
        if (claimType.equalsIgnoreCase("TERRITORY")
                || claimType.equalsIgnoreCase("CITY")
                || claimType.equalsIgnoreCase("DISTRICT")) {
            throw new IllegalArgumentException("Register a property/building claim as the venue, not an administrative boundary.");
        }
        if (venueForClaim(claimId).isPresent()) {
            throw new IllegalArgumentException("This claim is already registered as a Garden venue.");
        }
        if (findVenue(key).isPresent()) {
            throw new IllegalArgumentException("That venue name is already in use.");
        }

        UUID id = UUID.randomUUID();
        Location location = owner.getLocation();
        World world = location.getWorld();
        long now = System.currentTimeMillis();
        VenueRecord venue = new VenueRecord(
                id, claimId, owner.getUniqueId(), cleanName, key, world.getUID(), world.getName(),
                location.getX(), location.getY(), location.getZ(), false, 0L, now
        );

        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO gev_venues "
                             + "(venue_uuid, claim_uuid, owner_uuid, name, name_key, world_uuid, world_name, x, y, z, "
                             + "ticket_required, ticket_price, created_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, id.toString());
            statement.setString(2, claimId.toString());
            statement.setString(3, owner.getUniqueId().toString());
            statement.setString(4, cleanName);
            statement.setString(5, key);
            statement.setString(6, world.getUID().toString());
            statement.setString(7, world.getName());
            statement.setDouble(8, venue.x());
            statement.setDouble(9, venue.y());
            statement.setDouble(10, venue.z());
            statement.setInt(11, 0);
            statement.setLong(12, 0L);
            statement.setLong(13, now);
            statement.executeUpdate();
        }
        return venue;
    }

    public Optional<VenueRecord> findVenue(String nameOrKey) throws SQLException {
        String normalized = key(nameOrKey);
        if (normalized.isBlank()) return Optional.empty();
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gev_venues WHERE name_key = ? LIMIT 1")) {
            statement.setString(1, normalized);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readVenue(result)) : Optional.empty();
            }
        }
    }

    public Optional<VenueRecord> findVenue(UUID venueId) throws SQLException {
        if (venueId == null) return Optional.empty();
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gev_venues WHERE venue_uuid = ? LIMIT 1")) {
            statement.setString(1, venueId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readVenue(result)) : Optional.empty();
            }
        }
    }

    public Optional<VenueRecord> venueForClaim(UUID claimId) throws SQLException {
        if (claimId == null) return Optional.empty();
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gev_venues WHERE claim_uuid = ? LIMIT 1")) {
            statement.setString(1, claimId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readVenue(result)) : Optional.empty();
            }
        }
    }

    public Optional<VenueRecord> venueAt(Player player) throws SQLException {
        return venueAt(player.getLocation());
    }

    public Optional<VenueRecord> venueAt(Location location) throws SQLException {
        if (location == null || location.getWorld() == null) return Optional.empty();
        UUID claimId = land.claimIdAt(location.getBlock()).orElse(null);
        return claimId == null ? Optional.empty() : venueForClaim(claimId);
    }

    public List<VenueRecord> venues() throws SQLException {
        List<VenueRecord> venues = new ArrayList<>();
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gev_venues ORDER BY name");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) venues.add(readVenue(result));
        }
        return List.copyOf(venues);
    }

    public boolean deleteVenue(Player actor, VenueRecord venue) throws SQLException {
        if (!canManage(actor, venue)) {
            throw new IllegalArgumentException("You do not manage this venue's Garden claim.");
        }
        if (hasAnyEvents(venue.id()) || hasAnyVenueTickets(venue.id())) {
            throw new IllegalArgumentException("This venue has ticket/event history and cannot be deleted in the current release.");
        }
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM gev_venues WHERE venue_uuid = ?")) {
            statement.setString(1, venue.id().toString());
            return statement.executeUpdate() > 0;
        }
    }

    public VenueRecord setVenueTicketing(Player actor, VenueRecord requested, boolean required, long price)
            throws SQLException {
        VenueRecord venue = findVenue(requested.id())
                .orElseThrow(() -> new IllegalArgumentException("That venue no longer exists."));
        if (!canManage(actor, venue)) {
            throw new IllegalArgumentException("You do not manage this venue.");
        }
        if (price < 0) {
            throw new IllegalArgumentException("Venue ticket price cannot be negative.");
        }

        long savedPrice = required ? price : venue.ticketPrice();
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gev_venues SET ticket_required = ?, ticket_price = ? WHERE venue_uuid = ?")) {
            statement.setInt(1, required ? 1 : 0);
            statement.setLong(2, savedPrice);
            statement.setString(3, venue.id().toString());
            statement.executeUpdate();
        }
        return findVenue(venue.id()).orElseThrow();
    }

    public VenueRecord setVenueEntrance(Player actor, VenueRecord requested) throws SQLException {
        VenueRecord venue = findVenue(requested.id())
                .orElseThrow(() -> new IllegalArgumentException("That venue no longer exists."));
        if (!canManage(actor, venue)) {
            throw new IllegalArgumentException("You do not manage this venue.");
        }
        UUID claimId = land.claimIdAt(actor.getLocation().getBlock()).orElse(null);
        if (claimId == null || !claimId.equals(venue.claimId())) {
            throw new IllegalArgumentException("Stand inside this venue's registered claim to move its entrance.");
        }

        Location location = actor.getLocation();
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gev_venues SET world_uuid = ?, world_name = ?, x = ?, y = ?, z = ? WHERE venue_uuid = ?")) {
            statement.setString(1, location.getWorld().getUID().toString());
            statement.setString(2, location.getWorld().getName());
            statement.setDouble(3, location.getX());
            statement.setDouble(4, location.getY());
            statement.setDouble(5, location.getZ());
            statement.setString(6, venue.id().toString());
            statement.executeUpdate();
        }
        return findVenue(venue.id()).orElseThrow();
    }

    public EventRecord createEvent(
            Player host,
            String venueKey,
            long startDelayMillis,
            long durationMillis,
            long ticketPrice,
            int capacity,
            String name
    ) throws SQLException {
        VenueRecord venue = findVenue(venueKey)
                .orElseThrow(() -> new IllegalArgumentException("That Garden venue does not exist."));
        if (!canManage(host, venue)) {
            throw new IllegalArgumentException("You must manage that venue's Garden claim to schedule an event.");
        }
        if (startDelayMillis < 60_000L || startDelayMillis > maxStartDelayMillis) {
            throw new IllegalArgumentException("Event start time is outside the allowed scheduling window.");
        }
        if (durationMillis < 5L * 60_000L || durationMillis > maxDurationMillis) {
            throw new IllegalArgumentException("Event duration is outside the allowed range.");
        }
        if (ticketPrice < 0) {
            throw new IllegalArgumentException("Ticket price cannot be negative.");
        }
        if (capacity < 1 || capacity > maxCapacity) {
            throw new IllegalArgumentException("Event capacity must be between 1 and " + maxCapacity + ".");
        }
        String cleanName = cleanName(name, 96, "Event");

        long now = System.currentTimeMillis();
        long startAt = Math.addExact(now, startDelayMillis);
        long endAt = Math.addExact(startAt, durationMillis);
        if (overlaps(venue.id(), startAt, endAt)) {
            throw new IllegalArgumentException("That venue already has an overlapping scheduled event.");
        }

        EventRecord event = new EventRecord(
                UUID.randomUUID(), venue.id(), host.getUniqueId(), cleanName,
                startAt, endAt, ticketPrice, capacity, "SCHEDULED", now
        );
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO gev_events "
                             + "(event_uuid, venue_uuid, host_uuid, name, start_at, end_at, ticket_price, capacity, status, created_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'SCHEDULED', ?)")) {
            statement.setString(1, event.id().toString());
            statement.setString(2, venue.id().toString());
            statement.setString(3, host.getUniqueId().toString());
            statement.setString(4, event.name());
            statement.setLong(5, startAt);
            statement.setLong(6, endAt);
            statement.setLong(7, ticketPrice);
            statement.setInt(8, capacity);
            statement.setLong(9, now);
            statement.executeUpdate();
        }

        publish(
                IntegrationEventType.EVENT_ANNOUNCEMENT,
                "event",
                event.id().toString(),
                "{\"event\":\"" + json(event.name())
                        + "\",\"venue\":\"" + json(venue.name())
                        + "\",\"startAt\":" + startAt
                        + ",\"endAt\":" + endAt
                        + ",\"ticketPrice\":" + ticketPrice
                        + ",\"capacity\":" + capacity + "}"
        );
        return event;
    }

    public List<EventRecord> upcomingEvents() throws SQLException {
        List<EventRecord> events = new ArrayList<>();
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gev_events WHERE status = 'SCHEDULED' AND end_at >= ? "
                             + "ORDER BY start_at ASC LIMIT 50")) {
            statement.setLong(1, System.currentTimeMillis());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) events.add(readEvent(result));
            }
        }
        return List.copyOf(events);
    }

    public Optional<EventRecord> findEvent(String value) throws SQLException {
        if (value == null || value.isBlank()) return Optional.empty();
        String query = value.trim();

        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gev_events WHERE LOWER(name) = ? AND status = 'SCHEDULED' ORDER BY start_at ASC LIMIT 2")) {
            statement.setString(1, query.toLowerCase(Locale.ROOT));
            try (ResultSet result = statement.executeQuery()) {
                EventRecord match = null;
                while (result.next()) {
                    if (match != null) {
                        throw new IllegalArgumentException("More than one upcoming event has that title. Use the event code shown in /event info.");
                    }
                    match = readEvent(result);
                }
                if (match != null) {
                    return Optional.of(match);
                }
            }
        }

        try {
            return findEvent(UUID.fromString(query));
        } catch (IllegalArgumentException ignored) {
        }

        String prefix = query.toLowerCase(Locale.ROOT);
        if (prefix.length() >= 8 && prefix.matches("[0-9a-f-]+")) {
            EventRecord match = null;
            try (Connection connection = platform.storage().connection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT * FROM gev_events WHERE LOWER(event_uuid) LIKE ? LIMIT 2")) {
                statement.setString(1, prefix + "%");
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        if (match != null) {
                            throw new IllegalArgumentException("That event code matches more than one event.");
                        }
                        match = readEvent(result);
                    }
                }
            }
            if (match != null) {
                return Optional.of(match);
            }
        }

        return Optional.empty();
    }

    public Optional<EventRecord> findEvent(UUID eventId) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gev_events WHERE event_uuid = ? LIMIT 1")) {
            statement.setString(1, eventId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readEvent(result)) : Optional.empty();
            }
        }
    }

    public VenueRecord venue(EventRecord event) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT v.* FROM gev_venues v JOIN gev_events e ON e.venue_uuid = v.venue_uuid "
                             + "WHERE e.event_uuid = ? LIMIT 1")) {
            statement.setString(1, event.id().toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("The venue for this event no longer exists.");
                }
                return readVenue(result);
            }
        }
    }

    public int soldTickets(UUID eventId) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) AS n FROM gev_tickets "
                             + "WHERE event_uuid = ? AND status IN ('VALID', 'USED')")) {
            statement.setString(1, eventId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt("n") : 0;
            }
        }
    }

    public TicketRecord buyTicket(Player buyer, EventRecord requested) throws SQLException {
        Object lock = purchaseLocks.computeIfAbsent(requested.id(), ignored -> new Object());
        synchronized (lock) {
            EventRecord event = findEvent(requested.id())
                    .orElseThrow(() -> new IllegalArgumentException("That event no longer exists."));
            if (!event.scheduled()) {
                throw new IllegalArgumentException("That event is not accepting tickets.");
            }
            if (System.currentTimeMillis() >= event.startAt()) {
                throw new IllegalArgumentException("Ticket sales for this event have closed.");
            }
            if (event.hostId().equals(buyer.getUniqueId())) {
                throw new IllegalArgumentException("You are hosting this event and do not need a ticket.");
            }
            if (soldTickets(event.id()) >= event.capacity()) {
                throw new IllegalArgumentException("This event is sold out.");
            }

            VenueRecord venue = venue(event);
            GardenOrder order = platform.orders().create(
                    OrderType.EVENT_TICKET,
                    buyer.getUniqueId(),
                    "PLAYER",
                    event.hostId().toString(),
                    event.ticketPrice(),
                    "gardenevents.ticket",
                    event.id().toString(),
                    "{\"eventUuid\":\"" + event.id()
                            + "\",\"venueUuid\":\"" + venue.id() + "\"}"
            );
            platform.orders().transition(order.id(), OrderState.READY, "Event capacity and ticket ownership validated");
            platform.orders().transition(order.id(), OrderState.AWAITING_CONFIRMATION, "Buy command confirms ticket");
            platform.orders().transition(order.id(), OrderState.PAYMENT_PENDING, "Collecting ticket payment");

            if (event.ticketPrice() > 0) {
                if (!platform.currency().withdraw(buyer.getUniqueId(), event.ticketPrice())) {
                    platform.orders().transition(order.id(), OrderState.PAYMENT_FAILED,
                            "Buyer has insufficient Obols for ticket");
                    throw new IllegalArgumentException("You do not have enough Obols for this ticket.");
                }
                if (!platform.currency().deposit(event.hostId(), event.ticketPrice())) {
                    platform.currency().deposit(buyer.getUniqueId(), event.ticketPrice());
                    platform.orders().transition(order.id(), OrderState.PAYMENT_FAILED,
                            "Host payment failed and buyer was refunded");
                    throw new IllegalArgumentException("The event host could not be paid. Your Obols were returned.");
                }
            }

            platform.orders().transition(order.id(), OrderState.PAID,
                    event.ticketPrice() == 0 ? "Free ticket reserved" : "Ticket payment completed");
            platform.orders().transition(order.id(), OrderState.FULFILLING, "Issuing physical event ticket");

            TicketRecord ticket = new TicketRecord(
                    UUID.randomUUID(), event.id(), buyer.getUniqueId(), order.id(),
                    "VALID", event.ticketPrice(), true, System.currentTimeMillis(), null
            );
            try {
                insertTicket(ticket);
            } catch (SQLException exception) {
                boolean reversed = reversePayment(event, buyer);
                safeTransition(order.id(),
                        reversed ? OrderState.REFUNDED : OrderState.FULFILLMENT_FAILED,
                        reversed
                                ? "Ticket creation failed; payment reversed"
                                : "Ticket creation failed and payment reversal needs admin review");
                throw exception;
            }

            ItemStack item = ticketItem(ticket, event, venue);
            Map<Integer, ItemStack> leftovers = buyer.getInventory().addItem(item);
            leftovers.values().forEach(leftover ->
                    buyer.getWorld().dropItemNaturally(buyer.getLocation(), leftover));

            platform.orders().transition(order.id(), OrderState.COMPLETED, "Physical ticket issued");
            publish(
                    IntegrationEventType.TICKET_PURCHASED,
                    "ticket",
                    ticket.id().toString(),
                    "{\"eventUuid\":\"" + event.id()
                            + "\",\"buyerUuid\":\"" + buyer.getUniqueId()
                            + "\",\"amount\":" + ticket.paidAmount() + "}"
            );
            return ticket;
        }
    }

    public List<TicketRecord> buyTickets(Player buyer, EventRecord event, int amount) throws SQLException {
        if (amount < 1 || amount > 64) {
            throw new IllegalArgumentException("Ticket amount must be between 1 and 64.");
        }
        if (soldTickets(event.id()) + amount > event.capacity()) {
            throw new IllegalArgumentException("There are not enough tickets left for that purchase.");
        }

        List<TicketRecord> tickets = new ArrayList<>();
        for (int i = 0; i < amount; i++) {
            tickets.add(buyTicket(buyer, event));
        }
        return List.copyOf(tickets);
    }

    public List<TicketRecord> issueHostTickets(Player actor, EventRecord requested, int amount) throws SQLException {
        if (amount < 1 || amount > 64) {
            throw new IllegalArgumentException("Ticket amount must be between 1 and 64.");
        }

        Object lock = purchaseLocks.computeIfAbsent(requested.id(), ignored -> new Object());
        synchronized (lock) {
            EventRecord event = findEvent(requested.id())
                    .orElseThrow(() -> new IllegalArgumentException("That event no longer exists."));
            VenueRecord venue = venue(event);
            if (!event.scheduled()) {
                throw new IllegalArgumentException("That event is not accepting tickets.");
            }
            if (!event.hostId().equals(actor.getUniqueId())
                    && !canManage(actor, venue)
                    && !actor.hasPermission("gardenevents.admin")) {
                throw new IllegalArgumentException("You do not manage this event.");
            }
            if (soldTickets(event.id()) + amount > event.capacity()) {
                throw new IllegalArgumentException("There are not enough tickets left to issue that many.");
            }

            List<TicketRecord> tickets = new ArrayList<>();
            for (int i = 0; i < amount; i++) {
                GardenOrder order = platform.orders().create(
                        OrderType.EVENT_TICKET,
                        actor.getUniqueId(),
                        "PLAYER",
                        event.hostId().toString(),
                        0,
                        "gardenevents.host-ticket",
                        event.id().toString(),
                        "{\"eventUuid\":\"" + event.id()
                                + "\",\"venueUuid\":\"" + venue.id()
                                + "\",\"hostIssued\":true}"
                );
                platform.orders().transition(order.id(), OrderState.READY, "Host giveaway ticket approved");
                platform.orders().transition(order.id(), OrderState.AWAITING_CONFIRMATION, "Host confirmed giveaway ticket");
                platform.orders().transition(order.id(), OrderState.PAYMENT_PENDING, "No payment required");
                platform.orders().transition(order.id(), OrderState.PAID, "No payment required");
                platform.orders().transition(order.id(), OrderState.FULFILLING, "Issuing host giveaway ticket");

                TicketRecord ticket = new TicketRecord(
                        UUID.randomUUID(), event.id(), actor.getUniqueId(), order.id(),
                        "VALID", 0, true, System.currentTimeMillis(), null
                );
                insertTicket(ticket);
                ItemStack item = ticketItem(ticket, event, venue);
                Map<Integer, ItemStack> leftovers = actor.getInventory().addItem(item);
                leftovers.values().forEach(leftover ->
                        actor.getWorld().dropItemNaturally(actor.getLocation(), leftover));
                platform.orders().transition(order.id(), OrderState.COMPLETED, "Host giveaway ticket issued");
                tickets.add(ticket);
            }
            return List.copyOf(tickets);
        }
    }

    public VenueTicketRecord buyVenueTicket(Player buyer, VenueRecord requested) throws SQLException {
        Object lock = purchaseLocks.computeIfAbsent(requested.id(), ignored -> new Object());
        synchronized (lock) {
            VenueRecord venue = findVenue(requested.id())
                    .orElseThrow(() -> new IllegalArgumentException("That venue no longer exists."));
            if (!venue.ticketRequired()) {
                throw new IllegalArgumentException(venue.name() + " does not require permanent admission tickets.");
            }
            if (canManage(buyer, venue)) {
                throw new IllegalArgumentException("You manage this venue and do not need a permanent admission ticket.");
            }

            GardenOrder order = platform.orders().create(
                    OrderType.EVENT_TICKET,
                    buyer.getUniqueId(),
                    "PLAYER",
                    venue.ownerId().toString(),
                    venue.ticketPrice(),
                    "gardenevents.venue-ticket",
                    venue.id().toString(),
                    "{\"venueUuid\":\"" + venue.id() + "\",\"permanentVenue\":true}"
            );
            platform.orders().transition(order.id(), OrderState.READY, "Permanent venue ticket validated");
            platform.orders().transition(order.id(), OrderState.AWAITING_CONFIRMATION, "Buy command confirms venue ticket");
            platform.orders().transition(order.id(), OrderState.PAYMENT_PENDING, "Collecting venue admission payment");

            if (venue.ticketPrice() > 0) {
                if (!platform.currency().withdraw(buyer.getUniqueId(), venue.ticketPrice())) {
                    platform.orders().transition(order.id(), OrderState.PAYMENT_FAILED,
                            "Buyer has insufficient Obols for venue admission");
                    throw new IllegalArgumentException("You do not have enough Obols for this venue ticket.");
                }
                if (!platform.currency().deposit(venue.ownerId(), venue.ticketPrice())) {
                    platform.currency().deposit(buyer.getUniqueId(), venue.ticketPrice());
                    platform.orders().transition(order.id(), OrderState.PAYMENT_FAILED,
                            "Venue owner payment failed and buyer was refunded");
                    throw new IllegalArgumentException("The venue owner could not be paid. Your Obols were returned.");
                }
            }

            platform.orders().transition(order.id(), OrderState.PAID,
                    venue.ticketPrice() == 0 ? "Free venue ticket reserved" : "Venue ticket payment completed");
            platform.orders().transition(order.id(), OrderState.FULFILLING, "Issuing physical venue ticket");

            VenueTicketRecord ticket = new VenueTicketRecord(
                    UUID.randomUUID(), venue.id(), buyer.getUniqueId(), order.id(),
                    "VALID", venue.ticketPrice(), true, System.currentTimeMillis(), null
            );
            try {
                insertVenueTicket(ticket);
            } catch (SQLException exception) {
                boolean reversed = reverseVenuePayment(venue, buyer);
                safeTransition(order.id(),
                        reversed ? OrderState.REFUNDED : OrderState.FULFILLMENT_FAILED,
                        reversed
                                ? "Venue ticket creation failed; payment reversed"
                                : "Venue ticket creation failed and payment reversal needs admin review");
                throw exception;
            }

            ItemStack item = venueTicketItem(ticket, venue);
            Map<Integer, ItemStack> leftovers = buyer.getInventory().addItem(item);
            leftovers.values().forEach(leftover ->
                    buyer.getWorld().dropItemNaturally(buyer.getLocation(), leftover));

            platform.orders().transition(order.id(), OrderState.COMPLETED, "Physical venue ticket issued");
            publish(
                    IntegrationEventType.TICKET_PURCHASED,
                    "venue-ticket",
                    ticket.id().toString(),
                    "{\"venueUuid\":\"" + venue.id()
                            + "\",\"buyerUuid\":\"" + buyer.getUniqueId()
                            + "\",\"amount\":" + ticket.paidAmount()
                            + ",\"permanentVenue\":true}"
            );
            return ticket;
        }
    }

    public List<EventRecord> startingEvents(long afterExclusive, long throughInclusive) throws SQLException {
        List<EventRecord> events = new ArrayList<>();
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gev_events WHERE status = 'SCHEDULED' AND start_at > ? AND start_at <= ? ORDER BY start_at ASC")) {
            statement.setLong(1, afterExclusive);
            statement.setLong(2, throughInclusive);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    events.add(readEvent(result));
                }
            }
        }
        return List.copyOf(events);
    }

    public boolean hasValidPhysicalTicket(Player player, UUID eventId) throws SQLException {
        return physicalTicket(player, eventId).isPresent();
    }

    public boolean hasValidPhysicalVenueTicket(Player player, UUID venueId) throws SQLException {
        return physicalVenueTicket(player, venueId).isPresent();
    }

    public Optional<EventRecord> eventAcceptingAdmissionAt(Player player) throws SQLException {
        VenueRecord venue = venueAt(player).orElse(null);
        return eventAcceptingAdmissionAt(venue);
    }

    public Optional<EventRecord> eventAcceptingAdmissionAt(VenueRecord venue) throws SQLException {
        return venue == null
                ? Optional.empty()
                : eventInAdmissionWindow(venue.id(), System.currentTimeMillis());
    }

    public boolean canBypassAdmission(Player player, VenueRecord venue, EventRecord event) {
        return player.hasPermission("gardenevents.admin")
                || canManage(player, venue)
                || (event != null && event.hostId().equals(player.getUniqueId()));
    }

    public void rememberAdmissionExit(Player player, Location exit) {
        if (player == null || exit == null || exit.getWorld() == null) return;
        pendingAdmissionExit.put(player.getUniqueId(), exit.clone());
    }

    public boolean declineAdmission(Player player) {
        Location exit = pendingAdmissionExit.remove(player.getUniqueId());
        return exit != null && player.teleport(exit);
    }

    public void clearPendingAdmission(Player player) {
        if (player != null) pendingAdmissionExit.remove(player.getUniqueId());
    }

    public Optional<EventRecord> admittableEventAt(Player player) throws SQLException {
        VenueRecord venue = venueAt(player).orElse(null);
        if (venue == null) {
            return Optional.empty();
        }

        EventRecord event = eventInAdmissionWindow(venue.id(), System.currentTimeMillis()).orElse(null);
        if (event == null || physicalTicket(player, event.id()).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(event);
    }

    public AdmissionResult admit(Player player) throws SQLException {
        VenueRecord venue = venueAt(player)
                .orElseThrow(() -> new IllegalArgumentException("Enter the registered venue before admitting your ticket."));
        long now = System.currentTimeMillis();
        EventRecord event = eventInAdmissionWindow(venue.id(), now)
                .orElseThrow(() -> new IllegalArgumentException("There is no event accepting admission at this venue right now."));

        PhysicalTicket physical = physicalTicket(player, event.id())
                .orElseThrow(() -> new IllegalArgumentException("You do not have a valid ticket for " + event.name() + "."));

        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gev_tickets SET status = 'USED', used_at = ? "
                             + "WHERE ticket_uuid = ? AND status = 'VALID'")) {
            statement.setLong(1, now);
            statement.setString(2, physical.ticket().id().toString());
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("That ticket changed before admission could complete.");
            }
        }

        consumeTicket(player, physical);
        clearPendingAdmission(player);
        publish(
                IntegrationEventType.TICKET_ADMITTED,
                "ticket",
                physical.ticket().id().toString(),
                "{\"eventUuid\":\"" + event.id()
                        + "\",\"playerUuid\":\"" + player.getUniqueId()
                        + "\",\"venueUuid\":\"" + venue.id() + "\"}"
        );
        return new AdmissionResult(event, venue, physical.ticket());
    }

    public VenueAdmissionResult admitPermanentVenue(Player player) throws SQLException {
        VenueRecord venue = venueAt(player)
                .orElseThrow(() -> new IllegalArgumentException("Enter the registered venue before admitting your ticket."));
        if (!venue.ticketRequired()) {
            throw new IllegalArgumentException(venue.name() + " does not require a permanent admission ticket.");
        }

        VenueTicketPhysical physical = physicalVenueTicket(player, venue.id())
                .orElseThrow(() -> new IllegalArgumentException(
                        "You do not have a valid permanent ticket for " + venue.name() + "."));
        long now = System.currentTimeMillis();
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gev_venue_tickets SET status = 'USED', used_at = ? "
                             + "WHERE ticket_uuid = ? AND status = 'VALID'")) {
            statement.setLong(1, now);
            statement.setString(2, physical.ticket().id().toString());
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("That venue ticket changed before admission could complete.");
            }
        }

        consumeVenueTicket(player, physical);
        clearPendingAdmission(player);
        publish(
                IntegrationEventType.TICKET_ADMITTED,
                "venue-ticket",
                physical.ticket().id().toString(),
                "{\"venueUuid\":\"" + venue.id()
                        + "\",\"playerUuid\":\"" + player.getUniqueId()
                        + "\",\"permanentVenue\":true}"
        );
        return new VenueAdmissionResult(venue, physical.ticket());
    }

    private Optional<EventRecord> eventInAdmissionWindow(UUID venueId, long now) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gev_events WHERE venue_uuid = ? AND status = 'SCHEDULED' "
                             + "AND start_at <= ? AND end_at >= ? ORDER BY start_at ASC LIMIT 1")) {
            statement.setString(1, venueId.toString());
            statement.setLong(2, now + admissionEarlyMillis);
            statement.setLong(3, now - admissionLateMillis);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readEvent(result)) : Optional.empty();
            }
        }
    }

    private Optional<PhysicalTicket> physicalTicket(Player player, UUID eventId) throws SQLException {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) {
                continue;
            }

            String rawEvent = item.getItemMeta().getPersistentDataContainer()
                    .get(eventKey, PersistentDataType.STRING);
            if (rawEvent == null || !rawEvent.equals(eventId.toString())) {
                continue;
            }

            UUID id = ticketId(item).orElse(null);
            if (id == null) {
                continue;
            }

            TicketRecord ticket = findTicket(id).orElse(null);
            if (ticket == null || !ticket.valid() || !ticket.eventId().equals(eventId)) {
                continue;
            }
            if (!ticket.transferable() && !ticket.ownerId().equals(player.getUniqueId())) {
                continue;
            }
            return Optional.of(new PhysicalTicket(slot, item, ticket));
        }
        return Optional.empty();
    }

    private void consumeTicket(Player player, PhysicalTicket physical) {
        ItemStack item = player.getInventory().getItem(physical.slot());
        if (item == null || ticketId(item).filter(physical.ticket().id()::equals).isEmpty()) {
            return;
        }
        if (item.getAmount() <= 1) {
            player.getInventory().setItem(physical.slot(), null);
        } else {
            item.setAmount(item.getAmount() - 1);
        }
    }

    private Optional<VenueTicketPhysical> physicalVenueTicket(Player player, UUID venueId) throws SQLException {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) {
                continue;
            }

            String rawVenue = item.getItemMeta().getPersistentDataContainer()
                    .get(venueKey, PersistentDataType.STRING);
            if (rawVenue == null || !rawVenue.equals(venueId.toString())) {
                continue;
            }

            UUID id = venueTicketId(item).orElse(null);
            if (id == null) continue;
            VenueTicketRecord ticket = findVenueTicket(id).orElse(null);
            if (ticket == null || !ticket.valid() || !ticket.venueId().equals(venueId)) {
                continue;
            }
            if (!ticket.transferable() && !ticket.ownerId().equals(player.getUniqueId())) {
                continue;
            }
            return Optional.of(new VenueTicketPhysical(slot, item, ticket));
        }
        return Optional.empty();
    }

    private void consumeVenueTicket(Player player, VenueTicketPhysical physical) {
        ItemStack item = player.getInventory().getItem(physical.slot());
        if (item == null || venueTicketId(item).filter(physical.ticket().id()::equals).isEmpty()) {
            return;
        }
        if (item.getAmount() <= 1) {
            player.getInventory().setItem(physical.slot(), null);
        } else {
            item.setAmount(item.getAmount() - 1);
        }
    }

    public CancellationResult cancel(Player actor, EventRecord requested) throws SQLException {
        Object lock = purchaseLocks.computeIfAbsent(requested.id(), ignored -> new Object());
        synchronized (lock) {
            EventRecord event = findEvent(requested.id())
                    .orElseThrow(() -> new IllegalArgumentException("That event no longer exists."));
            if (!event.scheduled()) {
                if ("REFUND_REVIEW".equals(event.status()) || "REFUNDING".equals(event.status())) {
                    throw new IllegalArgumentException("That event cancellation needs administrator review.");
                }
                throw new IllegalArgumentException("That event is already cancelled or closed.");
            }

            VenueRecord venue = venue(event);
            if (!event.hostId().equals(actor.getUniqueId())
                    && !canManage(actor, venue)
                    && !actor.hasPermission("gardenevents.admin")) {
                throw new IllegalArgumentException("You do not manage this event.");
            }

            long now = System.currentTimeMillis();
            if (now >= event.startAt() - admissionEarlyMillis) {
                throw new IllegalArgumentException(
                        "This event is inside its admission window and can no longer be cancelled automatically.");
            }

            List<TicketRecord> tickets = validTickets(event.id());
            long refundTotal = 0L;
            try {
                for (TicketRecord ticket : tickets) {
                    refundTotal = Math.addExact(refundTotal, ticket.paidAmount());
                }
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("The ticket refund total is too large.");
            }

            if (!setEventStatus(event.id(), "SCHEDULED", "REFUNDING")) {
                throw new IllegalArgumentException("That event changed before cancellation could begin.");
            }

            if (refundTotal > 0 && !platform.currency().withdraw(event.hostId(), refundTotal)) {
                restoreScheduled(event.id());
                throw new IllegalArgumentException(
                        "The event host needs ⟡ " + refundTotal
                                + " available to refund all ticket holders before cancellation.");
            }

            List<TicketRecord> refunded = new ArrayList<>();
            for (TicketRecord ticket : tickets) {
                if (ticket.paidAmount() == 0
                        || platform.currency().deposit(ticket.ownerId(), ticket.paidAmount())) {
                    refunded.add(ticket);
                    continue;
                }

                rollbackRefundFailure(event, venue, tickets, refunded, ticket, refundTotal);
            }

            try {
                finalizeCancellation(event.id());
            } catch (SQLException exception) {
                try {
                    setEventStatus(event.id(), "REFUNDING", "REFUND_REVIEW");
                } catch (SQLException reviewFailure) {
                    plugin.getLogger().severe(
                            "CRITICAL: event " + event.id()
                                    + " refunds were sent but REFUND_REVIEW state could not be saved: "
                                    + reviewFailure.getMessage());
                }
                plugin.getLogger().severe(
                        "Event " + event.id() + " ticket refunds were sent, but cancellation persistence failed: "
                                + exception.getMessage());
                throw new IllegalArgumentException(
                        "Ticket refunds were sent, but this cancellation needs administrator review.");
            }

            for (TicketRecord ticket : tickets) {
                safeTransition(ticket.orderId(), OrderState.REFUNDED, "Event cancelled; ticket refunded");
                publish(
                        IntegrationEventType.TICKET_REFUNDED,
                        "ticket",
                        ticket.id().toString(),
                        "{\"eventUuid\":\"" + event.id()
                                + "\",\"playerUuid\":\"" + ticket.ownerId()
                                + "\",\"amount\":" + ticket.paidAmount() + "}"
                );
            }

            EventRecord cancelled = new EventRecord(
                    event.id(), event.venueId(), event.hostId(), event.name(), event.startAt(), event.endAt(),
                    event.ticketPrice(), event.capacity(), "CANCELLED", event.createdAt()
            );
            publish(
                    IntegrationEventType.EVENT_CANCELLED,
                    "event",
                    event.id().toString(),
                    "{\"event\":\"" + json(event.name())
                            + "\",\"venue\":\"" + json(venue.name())
                            + "\",\"refundedTickets\":" + tickets.size()
                            + ",\"refundTotal\":" + refundTotal + "}"
            );
            return new CancellationResult(cancelled, tickets.size(), refundTotal);
        }
    }

    private void rollbackRefundFailure(
            EventRecord event,
            VenueRecord venue,
            List<TicketRecord> allTickets,
            List<TicketRecord> refunded,
            TicketRecord failedTicket,
            long refundTotal
    ) throws SQLException {
        List<TicketRecord> unrecovered = new ArrayList<>();
        for (TicketRecord ticket : refunded) {
            if (ticket.paidAmount() > 0
                    && !platform.currency().withdraw(ticket.ownerId(), ticket.paidAmount())) {
                unrecovered.add(ticket);
            }
        }

        long unrecoveredAmount = 0L;
        try {
            for (TicketRecord ticket : unrecovered) {
                unrecoveredAmount = Math.addExact(unrecoveredAmount, ticket.paidAmount());
            }
        } catch (ArithmeticException exception) {
            unrecoveredAmount = refundTotal;
        }
        long restoreAmount = Math.max(0L, refundTotal - unrecoveredAmount);
        boolean hostRestored = restoreAmount == 0
                || platform.currency().deposit(event.hostId(), restoreAmount);

        if (unrecovered.isEmpty() && hostRestored) {
            restoreScheduled(event.id());
            throw new IllegalArgumentException(
                    "A ticket refund failed. All other refunds were reversed and the event remains scheduled.");
        }

        if (!unrecovered.isEmpty()) {
            markTicketsRefunded(unrecovered);
            for (TicketRecord ticket : unrecovered) {
                safeTransition(ticket.orderId(), OrderState.REFUNDED,
                        "Event cancellation refund paid; remaining cancellation needs admin review");
                publish(
                        IntegrationEventType.TICKET_REFUNDED,
                        "ticket",
                        ticket.id().toString(),
                        "{\"eventUuid\":\"" + event.id()
                                + "\",\"playerUuid\":\"" + ticket.ownerId()
                                + "\",\"amount\":" + ticket.paidAmount()
                                + ",\"partialCancellation\":true}"
                );
            }
        }

        setEventStatus(event.id(), "REFUNDING", "REFUND_REVIEW");
        plugin.getLogger().severe(
                "Event " + event.id() + " refund requires admin review. Failed ticket "
                        + failedTicket.id() + ", unrecovered refunds=" + unrecovered.size()
                        + ", hostRestored=" + hostRestored + ", venue=" + venue.name()
                        + ", ticketCount=" + allTickets.size() + ".");
        throw new IllegalArgumentException(
                "Cancellation partially refunded ticket holders and now needs administrator review.");
    }

    private List<TicketRecord> validTickets(UUID eventId) throws SQLException {
        List<TicketRecord> tickets = new ArrayList<>();
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gev_tickets WHERE event_uuid = ? AND status = 'VALID' ORDER BY purchased_at ASC")) {
            statement.setString(1, eventId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    tickets.add(readTicket(result));
                }
            }
        }
        return List.copyOf(tickets);
    }

    private boolean setEventStatus(UUID eventId, String from, String to) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gev_events SET status = ? WHERE event_uuid = ? AND status = ?")) {
            statement.setString(1, to);
            statement.setString(2, eventId.toString());
            statement.setString(3, from);
            return statement.executeUpdate() == 1;
        }
    }

    private void restoreScheduled(UUID eventId) throws SQLException {
        if (!setEventStatus(eventId, "REFUNDING", "SCHEDULED")) {
            throw new SQLException("Event refund state could not be restored to SCHEDULED: " + eventId);
        }
    }

    private void finalizeCancellation(UUID eventId) throws SQLException {
        try (Connection connection = platform.storage().connection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement tickets = connection.prepareStatement(
                        "UPDATE gev_tickets SET status = 'REFUNDED' "
                                + "WHERE event_uuid = ? AND status = 'VALID'")) {
                    tickets.setString(1, eventId.toString());
                    tickets.executeUpdate();
                }
                int changed;
                try (PreparedStatement event = connection.prepareStatement(
                        "UPDATE gev_events SET status = 'CANCELLED' "
                                + "WHERE event_uuid = ? AND status = 'REFUNDING'")) {
                    event.setString(1, eventId.toString());
                    changed = event.executeUpdate();
                }
                if (changed != 1) {
                    throw new SQLException("Event refund state changed before cancellation finalized: " + eventId);
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private void markTicketsRefunded(List<TicketRecord> tickets) throws SQLException {
        if (tickets.isEmpty()) return;
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gev_tickets SET status = 'REFUNDED' "
                             + "WHERE ticket_uuid = ? AND status = 'VALID'")) {
            for (TicketRecord ticket : tickets) {
                statement.setString(1, ticket.id().toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private Optional<TicketRecord> ticketForOwner(UUID eventId, UUID ownerId) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gev_tickets WHERE event_uuid = ? AND owner_uuid = ? LIMIT 1")) {
            statement.setString(1, eventId.toString());
            statement.setString(2, ownerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readTicket(result)) : Optional.empty();
            }
        }
    }

    private Optional<VenueTicketRecord> findVenueTicket(UUID ticketId) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gev_venue_tickets WHERE ticket_uuid = ? LIMIT 1")) {
            statement.setString(1, ticketId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readVenueTicket(result)) : Optional.empty();
            }
        }
    }

    private void insertVenueTicket(VenueTicketRecord ticket) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO gev_venue_tickets "
                             + "(ticket_uuid, venue_uuid, owner_uuid, order_uuid, status, paid_amount, transferable, purchased_at, used_at) "
                             + "VALUES (?, ?, ?, ?, 'VALID', ?, ?, ?, NULL)")) {
            statement.setString(1, ticket.id().toString());
            statement.setString(2, ticket.venueId().toString());
            statement.setString(3, ticket.ownerId().toString());
            statement.setString(4, ticket.orderId().toString());
            statement.setLong(5, ticket.paidAmount());
            statement.setInt(6, ticket.transferable() ? 1 : 0);
            statement.setLong(7, ticket.purchasedAt());
            statement.executeUpdate();
        }
    }

    private Optional<TicketRecord> findTicket(UUID ticketId) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gev_tickets WHERE ticket_uuid = ? LIMIT 1")) {
            statement.setString(1, ticketId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readTicket(result)) : Optional.empty();
            }
        }
    }

    private void insertTicket(TicketRecord ticket) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO gev_tickets "
                             + "(ticket_uuid, event_uuid, owner_uuid, order_uuid, status, paid_amount, transferable, purchased_at, used_at) "
                             + "VALUES (?, ?, ?, ?, 'VALID', ?, ?, ?, NULL)")) {
            statement.setString(1, ticket.id().toString());
            statement.setString(2, ticket.eventId().toString());
            statement.setString(3, ticket.ownerId().toString());
            statement.setString(4, ticket.orderId().toString());
            statement.setLong(5, ticket.paidAmount());
            statement.setInt(6, ticket.transferable() ? 1 : 0);
            statement.setLong(7, ticket.purchasedAt());
            statement.executeUpdate();
        }
    }

    private boolean reverseVenuePayment(VenueRecord venue, Player buyer) {
        if (venue.ticketPrice() == 0) return true;
        if (!platform.currency().withdraw(venue.ownerId(), venue.ticketPrice())) {
            return false;
        }
        if (platform.currency().deposit(buyer.getUniqueId(), venue.ticketPrice())) {
            return true;
        }
        platform.currency().deposit(venue.ownerId(), venue.ticketPrice());
        return false;
    }

    private boolean reversePayment(EventRecord event, Player buyer) {
        if (event.ticketPrice() == 0) return true;
        if (!platform.currency().withdraw(event.hostId(), event.ticketPrice())) {
            return false;
        }
        if (platform.currency().deposit(buyer.getUniqueId(), event.ticketPrice())) {
            return true;
        }
        platform.currency().deposit(event.hostId(), event.ticketPrice());
        return false;
    }

    public boolean canManageVenue(Player actor, VenueRecord venue) {
        return canManage(actor, venue);
    }

    public boolean canManageEvent(Player actor, EventRecord event) throws SQLException {
        if (actor.hasPermission("gardenevents.admin")) return true;
        if (event.hostId().equals(actor.getUniqueId())) return true;
        return canManage(actor, venue(event));
    }

    public EventRecord setEventPrice(Player actor, EventRecord requested, long price) throws SQLException {
        if (price < 0) throw new IllegalArgumentException("Ticket price cannot be negative.");
        EventRecord event = managedScheduledEvent(actor, requested);
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gev_events SET ticket_price = ? WHERE event_uuid = ?")) {
            statement.setLong(1, price);
            statement.setString(2, event.id().toString());
            statement.executeUpdate();
        }
        return findEvent(event.id()).orElseThrow();
    }

    public EventRecord setEventCapacity(Player actor, EventRecord requested, int capacity) throws SQLException {
        EventRecord event = managedScheduledEvent(actor, requested);
        if (capacity < 1 || capacity > maxCapacity) {
            throw new IllegalArgumentException("Event capacity must be between 1 and " + maxCapacity + ".");
        }
        int sold = soldTickets(event.id());
        if (capacity < sold) {
            throw new IllegalArgumentException("Capacity cannot be lower than the " + sold + " tickets already issued.");
        }
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gev_events SET capacity = ? WHERE event_uuid = ?")) {
            statement.setInt(1, capacity);
            statement.setString(2, event.id().toString());
            statement.executeUpdate();
        }
        return findEvent(event.id()).orElseThrow();
    }

    public EventRecord rescheduleEvent(
            Player actor, EventRecord requested, long startDelayMillis, long durationMillis) throws SQLException {
        EventRecord event = managedScheduledEvent(actor, requested);
        if (soldTickets(event.id()) > 0) {
            throw new IllegalArgumentException(
                    "An event cannot be rescheduled after tickets have been issued because the physical tickets show its start time.");
        }
        if (startDelayMillis < 60_000L || startDelayMillis > maxStartDelayMillis) {
            throw new IllegalArgumentException("Event start time is outside the allowed scheduling window.");
        }
        if (durationMillis < 5L * 60_000L || durationMillis > maxDurationMillis) {
            throw new IllegalArgumentException("Event duration is outside the allowed range.");
        }

        long startAt = Math.addExact(System.currentTimeMillis(), startDelayMillis);
        long endAt = Math.addExact(startAt, durationMillis);
        if (overlapsExcluding(event.venueId(), event.id(), startAt, endAt)) {
            throw new IllegalArgumentException("That venue already has an overlapping scheduled event.");
        }

        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE gev_events SET start_at = ?, end_at = ? WHERE event_uuid = ?")) {
            statement.setLong(1, startAt);
            statement.setLong(2, endAt);
            statement.setString(3, event.id().toString());
            statement.executeUpdate();
        }
        return findEvent(event.id()).orElseThrow();
    }

    private EventRecord managedScheduledEvent(Player actor, EventRecord requested) throws SQLException {
        EventRecord event = findEvent(requested.id())
                .orElseThrow(() -> new IllegalArgumentException("That event no longer exists."));
        if (!event.scheduled()) throw new IllegalArgumentException("That event is no longer scheduled.");
        if (!canManageEvent(actor, event)) throw new IllegalArgumentException("You do not manage this event.");
        return event;
    }

    private boolean overlapsExcluding(UUID venueId, UUID eventId, long startAt, long endAt) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM gev_events WHERE venue_uuid = ? AND event_uuid <> ? "
                             + "AND status = 'SCHEDULED' AND start_at < ? AND end_at > ? LIMIT 1")) {
            statement.setString(1, venueId.toString());
            statement.setString(2, eventId.toString());
            statement.setLong(3, endAt);
            statement.setLong(4, startAt);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean overlaps(UUID venueId, long startAt, long endAt) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM gev_events WHERE venue_uuid = ? AND status = 'SCHEDULED' "
                             + "AND start_at < ? AND end_at > ? LIMIT 1")) {
            statement.setString(1, venueId.toString());
            statement.setLong(2, endAt);
            statement.setLong(3, startAt);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean hasAnyVenueTickets(UUID venueId) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM gev_venue_tickets WHERE venue_uuid = ? LIMIT 1")) {
            statement.setString(1, venueId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean hasAnyEvents(UUID venueId) throws SQLException {
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM gev_events WHERE venue_uuid = ? LIMIT 1")) {
            statement.setString(1, venueId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean canManage(Player player, VenueRecord venue) {
        if (player.hasPermission("gardenevents.admin")) return true;
        World world = Bukkit.getWorld(venue.worldId());
        if (world == null) world = Bukkit.getWorld(venue.worldName());
        if (world == null) return false;
        return land.canManage(player, world.getBlockAt(
                (int) Math.floor(venue.x()),
                (int) Math.floor(venue.y()),
                (int) Math.floor(venue.z())));
    }

    private boolean atVenueEntrance(Player player, VenueRecord venue) {
        World world = Bukkit.getWorld(venue.worldId());
        if (world == null) world = Bukkit.getWorld(venue.worldName());
        if (world == null || !player.getWorld().getUID().equals(world.getUID())) {
            return false;
        }
        Location entrance = new Location(world, venue.x(), venue.y(), venue.z());
        return player.getLocation().distanceSquared(entrance) <= admissionRadiusSquared;
    }

    private ItemStack ticketItem(TicketRecord ticket, EventRecord event, VenueRecord venue) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Ticket • " + event.name(), NamedTextColor.WHITE));
        meta.lore(List.of(
                Component.text("Venue: " + venue.name(), NamedTextColor.GRAY),
                Component.text("Starts: " + EventTime.format(event.startAt()), NamedTextColor.GRAY),
                Component.text("Event " + event.id().toString().substring(0, 8), NamedTextColor.DARK_GRAY),
                Component.text("Ticket " + ticket.id().toString().substring(0, 8), NamedTextColor.DARK_GRAY),
                Component.text("Bearer ticket. One admission only.", NamedTextColor.DARK_GRAY)
        ));
        meta.getPersistentDataContainer().set(ticketKey, PersistentDataType.STRING, ticket.id().toString());
        meta.getPersistentDataContainer().set(eventKey, PersistentDataType.STRING, event.id().toString());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack venueTicketItem(VenueTicketRecord ticket, VenueRecord venue) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Ticket • " + venue.name(), NamedTextColor.WHITE));
        meta.lore(List.of(
                Component.text("Permanent venue admission", NamedTextColor.GRAY),
                Component.text("Venue " + venue.id().toString().substring(0, 8), NamedTextColor.DARK_GRAY),
                Component.text("Ticket " + ticket.id().toString().substring(0, 8), NamedTextColor.DARK_GRAY),
                Component.text("Bearer ticket. One admission only.", NamedTextColor.DARK_GRAY)
        ));
        meta.getPersistentDataContainer().set(venueTicketKey, PersistentDataType.STRING, ticket.id().toString());
        meta.getPersistentDataContainer().set(venueKey, PersistentDataType.STRING, venue.id().toString());
        item.setItemMeta(meta);
        return item;
    }

    private Optional<UUID> venueTicketId(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) {
            return Optional.empty();
        }
        String raw = item.getItemMeta().getPersistentDataContainer()
                .get(venueTicketKey, PersistentDataType.STRING);
        if (raw == null) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private ItemStack heldTicket(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (ticketId(main).isPresent()) return main;
        ItemStack off = player.getInventory().getItemInOffHand();
        return ticketId(off).isPresent() ? off : null;
    }

    private Optional<UUID> ticketId(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) {
            return Optional.empty();
        }
        String raw = item.getItemMeta().getPersistentDataContainer().get(ticketKey, PersistentDataType.STRING);
        if (raw == null) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private void consumeHeldTicket(Player player, ItemStack held) {
        UUID heldId = ticketId(held).orElse(null);
        ItemStack main = player.getInventory().getItemInMainHand();
        boolean mainHand = heldId != null && ticketId(main).filter(heldId::equals).isPresent();
        ItemStack target = mainHand ? main : player.getInventory().getItemInOffHand();

        if (target.getAmount() <= 1) {
            if (mainHand) {
                player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            } else {
                player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
            }
        } else {
            target.setAmount(target.getAmount() - 1);
        }
    }

    private void safeTransition(UUID orderId, OrderState state, String detail) {
        try {
            platform.orders().transition(orderId, state, detail);
        } catch (SQLException | RuntimeException exception) {
            plugin.getLogger().warning("Could not journal event order " + orderId + " -> "
                    + state + ": " + exception.getMessage());
        }
    }

    private void publish(IntegrationEventType type, String aggregateType, String aggregateId, String payload) {
        try {
            platform.integrations().publish(type, aggregateType, aggregateId, payload);
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not queue " + type + " event for Iris: " + exception.getMessage());
        }
    }

    private VenueRecord readVenue(ResultSet result) throws SQLException {
        return new VenueRecord(
                UUID.fromString(result.getString("venue_uuid")),
                UUID.fromString(result.getString("claim_uuid")),
                UUID.fromString(result.getString("owner_uuid")),
                result.getString("name"),
                result.getString("name_key"),
                UUID.fromString(result.getString("world_uuid")),
                result.getString("world_name"),
                result.getDouble("x"),
                result.getDouble("y"),
                result.getDouble("z"),
                result.getInt("ticket_required") != 0,
                result.getLong("ticket_price"),
                result.getLong("created_at")
        );
    }

    private EventRecord readEvent(ResultSet result) throws SQLException {
        return new EventRecord(
                UUID.fromString(result.getString("event_uuid")),
                UUID.fromString(result.getString("venue_uuid")),
                UUID.fromString(result.getString("host_uuid")),
                result.getString("name"),
                result.getLong("start_at"),
                result.getLong("end_at"),
                result.getLong("ticket_price"),
                result.getInt("capacity"),
                result.getString("status"),
                result.getLong("created_at")
        );
    }

    private TicketRecord readTicket(ResultSet result) throws SQLException {
        Object usedAt = result.getObject("used_at");
        return new TicketRecord(
                UUID.fromString(result.getString("ticket_uuid")),
                UUID.fromString(result.getString("event_uuid")),
                UUID.fromString(result.getString("owner_uuid")),
                UUID.fromString(result.getString("order_uuid")),
                result.getString("status"),
                result.getLong("paid_amount"),
                result.getInt("transferable") != 0,
                result.getLong("purchased_at"),
                usedAt == null ? null : result.getLong("used_at")
        );
    }

    private VenueTicketRecord readVenueTicket(ResultSet result) throws SQLException {
        Object usedAt = result.getObject("used_at");
        return new VenueTicketRecord(
                UUID.fromString(result.getString("ticket_uuid")),
                UUID.fromString(result.getString("venue_uuid")),
                UUID.fromString(result.getString("owner_uuid")),
                UUID.fromString(result.getString("order_uuid")),
                result.getString("status"),
                result.getLong("paid_amount"),
                result.getInt("transferable") != 0,
                result.getLong("purchased_at"),
                usedAt == null ? null : result.getLong("used_at")
        );
    }

    private String cleanName(String value, int max, String kind) {
        String clean = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (clean.length() < 3 || clean.length() > max) {
            throw new IllegalArgumentException(kind + " names must be 3 to " + max + " characters.");
        }
        return clean;
    }

    private String key(String value) {
        if (value == null) return "";
        String clean = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("-+", "-");
        if (clean.startsWith("-")) clean = clean.substring(1);
        if (clean.endsWith("-") && !clean.isEmpty()) clean = clean.substring(0, clean.length() - 1);
        return clean.length() > 64 ? clean.substring(0, 64) : clean;
    }

    private String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private record PhysicalTicket(int slot, ItemStack item, TicketRecord ticket) {
    }

    private record VenueTicketPhysical(int slot, ItemStack item, VenueTicketRecord ticket) {
    }

    public record AdmissionResult(EventRecord event, VenueRecord venue, TicketRecord ticket) {
    }

    public record VenueAdmissionResult(VenueRecord venue, VenueTicketRecord ticket) {
    }

    public record CancellationResult(EventRecord event, int refundedTickets, long refundTotal) {
    }
}
