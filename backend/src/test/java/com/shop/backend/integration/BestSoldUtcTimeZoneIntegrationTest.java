package com.shop.backend.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.annotation.DirtiesContext;

/** JVM default zone UTC (what Cloud Run uses). The property only gives this class its own context cache key. */
@TestPropertySource(properties = "test.jvm-zone=UTC")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BestSoldUtcTimeZoneIntegrationTest extends BestSoldTimeZoneIntegrationTest {

    @BeforeAll
    static void setZone() {
        useZone("UTC");
    }

    @AfterAll
    static void resetZone() {
        restoreZone();
    }

    @Override
    protected String zoneId() {
        return "UTC";
    }
}
