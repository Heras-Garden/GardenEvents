package com.herasgarden.gardenevents.model;

import java.util.UUID;

public record TicketRecord(
        UUID id,
        UUID eventId,
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
