package dev.investigator.store.jdbc;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgreSQL Testcontainer settings shared by the persistence tests (Req 14.1).
 *
 * <p>The container user is a superuser, like the Compose {@code POSTGRES_USER}, so it plays the Flyway
 * owner. The application connects as {@link #APP_ROLE}, which {@code V2__audit.sql} creates exactly as in
 * the Compose stack (CP-57). The password is a test-only value.
 */
final class PostgresContainers {

    /** Same image as the {@code postgres} service in {@code deploy/docker-compose.yml}. */
    static final String IMAGE = "postgres:17.12-alpine";

    static final String APP_ROLE = "investigator_app";
    static final String APP_PASSWORD = "investigator-app-test";

    private PostgresContainers() {
    }

    static PostgreSQLContainer<?> create() {
        return new PostgreSQLContainer<>(IMAGE);
    }

    /** Datasource as the app role, Flyway as the container superuser (the schema owner). */
    static void register(DynamicPropertyRegistry registry, PostgreSQLContainer<?> postgres) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }
}
