package com.herasgarden.gardenevents.api;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Account-based event access for Garden identities that do not own a Bukkit
 * Player inventory, such as Society residents.
 */
public interface SocietyEventDirectory {
    Optional<Visit> nextVisit(long maxTicketPrice, long horizonMillis) throws SQLException;

    PurchaseResult reserve(UUID residentId, String residentName, UUID eventId) throws SQLException;

    boolean hasValidTicket(UUID residentId, UUID eventId) throws SQLException;

    boolean admit(UUID residentId, UUID eventId) throws SQLException;

    record Visit(
            UUID eventId,
            String eventName,
            UUID venueId,
            String venueName,
            UUID worldId,
            double x,
            double y,
            double z,
            long startAt,
            long endAt,
            long ticketPrice
    ) {}

    record PurchaseResult(boolean success, String message, long paidAmount) {
        public static PurchaseResult success(long amount) {
            return new PurchaseResult(true, "Society event ticket reserved.", amount);
        }
        public static PurchaseResult failure(String message) {
            return new PurchaseResult(false, message, 0L);
        }
    }
}
