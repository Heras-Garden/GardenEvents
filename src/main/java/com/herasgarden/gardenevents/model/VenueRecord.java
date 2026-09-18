package com.herasgarden.gardenevents.model;

import java.util.UUID;

public record VenueRecord(
        UUID id,
        UUID claimId,
        UUID ownerId,
        String name,
        String nameKey,
        UUID worldId,
        String worldName,
        double x,
        double y,
        double z,
        long createdAt
) {
}
