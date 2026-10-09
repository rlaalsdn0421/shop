package com.shop.backend.integration;

import com.shop.backend.application.service.AuthService;
import com.shop.backend.application.service.ProductService;
import com.shop.backend.application.service.ReviewService;
import com.shop.backend.infrastructure.repository.ProductRepository;
import com.shop.backend.infrastructure.repository.ReviewRepository;
import com.shop.backend.infrastructure.repository.UserRepository;
import com.shop.backend.infrastructure.security.JwtService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

/**
 * Base of every real-database test: Flyway runs V1..latest on a real Postgres and Hibernate keeps
 * ddl-auto=validate (from application.yml, deliberately not overridden), so a booting context already proves
 * entities == migrations.
 *
 * <p>One container per JVM (started in the static initializer, killed by Testcontainers' Ryuk at JVM exit).
 * If Docker is unavailable the initializer throws and every test class FAILS - they are never skipped.
 *
 * <p>Tests run WITHOUT a surrounding transaction (the services open their own, concurrency tests need real
 * commits), so each test starts from truncated tables instead of relying on rollback.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AuthService.class, ReviewService.class, ProductService.class, JwtService.class, IntegrationTestConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class PostgresIntegrationTest {

    // Neon (production) is Postgres 15+; 16 is the closest alpine image.
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected DataSource dataSource;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected EntityManager em;
    @Autowired
    protected PlatformTransactionManager txManager;
    @Autowired
    protected ProductRepository productRepository;
    @Autowired
    protected ReviewRepository reviewRepository;
    @Autowired
    protected UserRepository userRepository;
    @Autowired
    protected ProductService productService;
    @Autowired
    protected ReviewService reviewService;
    @Autowired
    protected AuthService authService;

    protected TestData data;

    @BeforeEach
    void cleanTables() {
        TestDatabaseGuard.requireContainerDatabase(dataSource, POSTGRES.getJdbcUrl(), POSTGRES.getDatabaseName());
        // also removes the rows seeded by V2/V4; no test may depend on them
        jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
        data = new TestData(em, txManager);
    }
}
