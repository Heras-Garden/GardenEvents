package com.herasgarden.gardenevents.model;

import java.util.UUID;

public record EventRecord(
        UUID id,
        UUID venueId,
        UUID hostId,
        String name,
        long startAt,
        long endAt,
        long ticketPrice,
        int capacity,
        String status,
        long createdAt
) {
    public boolean scheduled() {
        return "SCHEDULED".equals(status);
    }
}
