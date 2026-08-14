package com.nforce.onehr.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class TimeConfig {

    @Bean
    public MutableClock mutableClock() {
        return new MutableClock();
    }

    @Bean
    public Clock clock(MutableClock mutableClock) {
        return mutableClock;
    }
}
