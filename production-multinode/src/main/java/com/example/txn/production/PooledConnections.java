package com.example.txn.production;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/** Uses HikariCP when packaged for production; deliberately fails closed if the pool is absent. */
final class PooledConnections implements AutoCloseable {
    private final DataSource dataSource;
    private final AutoCloseable pool;

    PooledConnections(String jdbcUrl, String username, String password, int maxPoolSize) {
        try {
            Class<?> configClass = Class.forName("com.zaxxer.hikari.HikariConfig");
            Object config = configClass.getConstructor().newInstance();
            configClass.getMethod("setJdbcUrl", String.class).invoke(config, jdbcUrl);
            configClass.getMethod("setUsername", String.class).invoke(config, username);
            configClass.getMethod("setPassword", String.class).invoke(config, password);
            configClass.getMethod("setMaximumPoolSize", int.class).invoke(config, maxPoolSize);
            configClass.getMethod("setMinimumIdle", int.class).invoke(config, Math.min(2, maxPoolSize));
            configClass.getMethod("setConnectionTimeout", long.class).invoke(config, 5000L);
            configClass.getMethod("setValidationTimeout", long.class).invoke(config, 3000L);
            configClass.getMethod("setPoolName", String.class).invoke(config, "transaction-service-pool");
            configClass.getMethod("setAutoCommit", boolean.class).invoke(config, true);
            Class<?> poolClass = Class.forName("com.zaxxer.hikari.HikariDataSource");
            Object instance = poolClass.getConstructor(configClass).newInstance(config);
            this.dataSource = (DataSource) instance;
            this.pool = (AutoCloseable) instance;
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IllegalStateException("Production mode requires HikariCP on the classpath; refusing unpooled database access", e);
        }
    }

    Connection getConnection() throws SQLException { return dataSource.getConnection(); }
    @Override public void close() { try { pool.close(); } catch (Exception e) { throw new IllegalStateException("Could not close database connection pool", e); } }
}
