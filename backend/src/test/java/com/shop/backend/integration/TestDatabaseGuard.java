package com.shop.backend.integration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Last line of defence before the tests TRUNCATE tables: refuses to run unless the DataSource really is the
 * Testcontainers database. A broken @ServiceConnection wiring combined with an exported DB_URL (e.g. Neon) must
 * never be able to wipe a real database.
 */
final class TestDatabaseGuard {

    private TestDatabaseGuard() {
    }

    /**
     * @param containerJdbcUrl   {@code container.getJdbcUrl()}
     * @param containerDatabase  {@code container.getDatabaseName()}
     */
    static void requireContainerDatabase(DataSource dataSource, String containerJdbcUrl, String containerDatabase) {
        try (Connection connection = dataSource.getConnection()) {
            String actualUrl = withoutQuery(connection.getMetaData().getURL());
            String expectedUrl = withoutQuery(containerJdbcUrl);
            if (!expectedUrl.equals(actualUrl)) {
                throw refuse("JDBC URL is " + actualUrl + " but the Testcontainers database is " + expectedUrl);
            }
            try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT current_database()")) {
                rs.next();
                if (!containerDatabase.equals(rs.getString(1))) {
                    throw refuse("current_database() is " + rs.getString(1) + " but the container's is " + containerDatabase);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not verify the test database; refusing to TRUNCATE", e);
        }
    }

    private static IllegalStateException refuse(String reason) {
        return new IllegalStateException("Refusing to TRUNCATE: the DataSource is not the Testcontainers database (" + reason
                + "). Check @ServiceConnection wiring and unset DB_URL/DB_USERNAME/DB_PASSWORD.");
    }

    private static String withoutQuery(String url) {
        int q = url == null ? -1 : url.indexOf('?');
        return String.valueOf(q < 0 ? url : url.substring(0, q));
    }
}
