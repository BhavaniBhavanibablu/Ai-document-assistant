package com.example.document_assistant.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class DotenvConfig {

    private final ConfigurableEnvironment environment;

    public DotenvConfig(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void loadDotenv() {
        Dotenv dotenv = Dotenv.configure()
                .directory(".")
                .ignoreIfMissing()
                .load();

        Map<String, Object> properties = new HashMap<>();

        dotenv.entries().forEach(entry ->
                properties.put(entry.getKey(), entry.getValue())
        );

        environment.getPropertySources().addFirst(
                new MapPropertySource("dotenvProperties", properties)
        );
    }
}
