package com.investory.marketdata;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

class HithinkDataConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(HithinkDataConfiguration.class);

    @Test void loadsIndependentConfigurationWithoutDatabaseOrNetwork() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            var properties = context.getBean(HithinkDataProperties.class);
            assertFalse(properties.enabled());
            assertEquals("https://fuyao.aicubes.cn", properties.baseUrl());
            assertEquals(5000, properties.timeoutMs());
        });
    }

    @Test void allowsExplicitOverridesAndRejectsUnsafeOrUnboundedConfiguration() {
        runner.withPropertyValues("investory.hithink.enabled=true", "investory.hithink.api-key=test-secret",
                "investory.hithink.timeout-ms=1500").run(context -> {
            var properties = context.getBean(HithinkDataProperties.class);
            assertTrue(properties.enabled());
            assertEquals("test-secret", properties.apiKey());
            assertFalse(properties.toString().contains("test-secret"));
        });
        assertThrows(IllegalArgumentException.class, () -> new HithinkDataProperties(true, "key", "http://example.com", 1000));
        assertThrows(IllegalArgumentException.class, () -> new HithinkDataProperties(true, "key", "https://example.com/?token=key", 1000));
        assertThrows(IllegalArgumentException.class, () -> new HithinkDataProperties(true, "key", "https://example.com", 60000));
    }
}
