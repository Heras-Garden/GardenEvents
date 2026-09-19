package com.herasgarden.gardenevents.model;

import java.util.UUID;

public record VenueTicketRecord(
        UUID id,
        UUID venueId,
        UUID ownerId,
        UUID orderId,
        String status,
        long paidAmount,
        boolean transferable,
        long purchasedAt,
        Long usedAt
) {
    public boolean valid() {
        return "VALID".equals(status);
    }
}
