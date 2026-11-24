package com.elecsarthi.support;

import com.elecsarthi.config.ClockConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the application Clock with a {@link MutableClock}; import it into tests that need to control time. */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(ClockConfig.INDIA_ZONE);
    }
}
