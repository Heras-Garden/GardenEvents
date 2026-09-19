package com.herasgarden.gardenevents.storage;

import com.herasgarden.gardencore.api.storage.GardenStorage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class EventsSchema {
    private EventsSchema() {
    }

    public static void ensure(GardenStorage storage) throws SQLException {
        try (Connection connection = storage.connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gev_venues ("
                    + "venue_uuid VARCHAR(36) PRIMARY KEY,"
                    + "claim_uuid VARCHAR(36) NOT NULL UNIQUE,"
                    + "owner_uuid VARCHAR(36) NOT NULL,"
                    + "name VARCHAR(64) NOT NULL,"
                    + "name_key VARCHAR(64) NOT NULL UNIQUE,"
                    + "world_uuid VARCHAR(36) NOT NULL,"
                    + "world_name VARCHAR(128) NOT NULL,"
                    + "x DOUBLE NOT NULL,"
                    + "y DOUBLE NOT NULL,"
                    + "z DOUBLE NOT NULL,"
                    + "ticket_required INTEGER NOT NULL DEFAULT 0,"
                    + "ticket_price BIGINT NOT NULL DEFAULT 0,"
                    + "created_at BIGINT NOT NULL)");

            ensureColumn(connection, "gev_venues", "ticket_required",
                    "ALTER TABLE gev_venues ADD COLUMN ticket_required INTEGER NOT NULL DEFAULT 0");
            ensureColumn(connection, "gev_venues", "ticket_price",
                    "ALTER TABLE gev_venues ADD COLUMN ticket_price BIGINT NOT NULL DEFAULT 0");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gev_events ("
                    + "event_uuid VARCHAR(36) PRIMARY KEY,"
                    + "venue_uuid VARCHAR(36) NOT NULL,"
                    + "host_uuid VARCHAR(36) NOT NULL,"
                    + "name VARCHAR(96) NOT NULL,"
                    + "start_at BIGINT NOT NULL,"
                    + "end_at BIGINT NOT NULL,"
                    + "ticket_price BIGINT NOT NULL,"
                    + "capacity INTEGER NOT NULL,"
                    + "status VARCHAR(24) NOT NULL,"
                    + "created_at BIGINT NOT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gev_events_venue "
                    + "ON gev_events (venue_uuid, status, start_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gev_events_start "
                    + "ON gev_events (status, start_at)");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gev_tickets ("
                    + "ticket_uuid VARCHAR(36) PRIMARY KEY,"
                    + "event_uuid VARCHAR(36) NOT NULL,"
                    + "owner_uuid VARCHAR(36) NOT NULL,"
                    + "order_uuid VARCHAR(36) NOT NULL,"
                    + "status VARCHAR(24) NOT NULL,"
                    + "paid_amount BIGINT NOT NULL DEFAULT 0,"
                    + "transferable INTEGER NOT NULL DEFAULT 1,"
                    + "purchased_at BIGINT NOT NULL,"
                    + "used_at BIGINT NULL,"
                    + "admitted_player_uuid VARCHAR(36) NULL)");

            ensureColumn(connection, "gev_tickets", "paid_amount",
                    "ALTER TABLE gev_tickets ADD COLUMN paid_amount BIGINT NOT NULL DEFAULT 0");
            ensureColumn(connection, "gev_tickets", "transferable",
                    "ALTER TABLE gev_tickets ADD COLUMN transferable INTEGER NOT NULL DEFAULT 1");
            ensureColumn(connection, "gev_tickets", "admitted_player_uuid",
                    "ALTER TABLE gev_tickets ADD COLUMN admitted_player_uuid VARCHAR(36) NULL");
            dropLegacyOwnerIndex(statement);

            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gev_ticket_owner_event_lookup "
                    + "ON gev_tickets (event_uuid, owner_uuid, status)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gev_ticket_event "
                    + "ON gev_tickets (event_uuid, status, purchased_at)");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gev_venue_tickets ("
                    + "ticket_uuid VARCHAR(36) PRIMARY KEY,"
                    + "venue_uuid VARCHAR(36) NOT NULL,"
                    + "owner_uuid VARCHAR(36) NOT NULL,"
                    + "order_uuid VARCHAR(36) NOT NULL,"
                    + "status VARCHAR(24) NOT NULL,"
                    + "paid_amount BIGINT NOT NULL DEFAULT 0,"
                    + "transferable INTEGER NOT NULL DEFAULT 1,"
                    + "purchased_at BIGINT NOT NULL,"
                    + "used_at BIGINT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gev_venue_ticket_owner "
                    + "ON gev_venue_tickets (venue_uuid, owner_uuid, status)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gev_venue_ticket_venue "
                    + "ON gev_venue_tickets (venue_uuid, status, purchased_at)");
        }
    }

    private static void ensureColumn(Connection connection, String table, String column, String ddl) throws SQLException {
        try (ResultSet result = connection.getMetaData().getColumns(null, null, table, column)) {
            if (result.next()) {
                return;
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(ddl);
        }
    }

    private static void dropLegacyOwnerIndex(Statement statement) {
        try {
            statement.executeUpdate("DROP INDEX IF EXISTS idx_gev_ticket_owner_event");
            return;
        } catch (SQLException ignored) {
        }
        try {
            statement.executeUpdate("DROP INDEX idx_gev_ticket_owner_event ON gev_tickets");
        } catch (SQLException ignored) {
        }
    }
}
