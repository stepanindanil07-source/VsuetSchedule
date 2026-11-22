package ru.vsuet.schedule.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class CommonConfiguration {

    /** One injectable clock so that time-dependent code can be tested with a fixed instant. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
