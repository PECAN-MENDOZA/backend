package com.mvp.backend.shared.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.demo-seed")
public record DemoSeedProperties(
        boolean enabled,
        boolean reset,
        boolean exitAfterRun,
        int studentCount,
        String teacherPassword,
        String studentPassword) {
}
