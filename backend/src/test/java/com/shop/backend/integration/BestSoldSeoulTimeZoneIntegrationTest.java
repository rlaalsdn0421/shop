package com.shop.backend.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** JVM default zone Asia/Seoul (a developer machine). The property only gives this class its own context cache key. */
@TestPropertySource(properties = "test.jvm-zone=Asia/Seoul")
@ResourceLock(Resources.TIME_ZONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BestSoldSeoulTimeZoneIntegrationTest extends BestSoldTimeZoneIntegrationTest {

    @BeforeAll
    static void setZone() {
        useZone("Asia/Seoul");
    }

    @AfterAll
    static void resetZone() {
        restoreZone();
    }

    @Override
    protected String zoneId() {
        return "Asia/Seoul";
    }
}
