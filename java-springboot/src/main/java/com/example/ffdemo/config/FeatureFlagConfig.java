package com.example.ffdemo.config;

import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.FeatureProvider;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeatureFlagConfig {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagConfig.class);

    @PostConstruct
    public void init() {
        OpenFeatureAPI api = OpenFeatureAPI.getInstance();

        // The Datadog dd-java-agent automatically registers as the OpenFeature provider
        // when running with -javaagent:dd-java-agent.jar and Remote Config is enabled.
        // The provider is set via dd-trace-java's internal OpenFeature integration.

        api.onProviderConfigurationChanged(event -> {
            log.info("[Q1] Provider configuration changed — flags updated without restart. "
                    + "Flags changed: {}", event.getFlagsChanged());
        });

        api.onProviderError(event -> {
            log.warn("[Q6] Provider error event received: {}", event.getMessage());
        });

        api.onProviderReady(event -> {
            log.info("OpenFeature provider is READY");
        });

        log.info("OpenFeature configured — waiting for Datadog provider via dd-java-agent");
    }

    @Bean
    public Client openFeatureClient() {
        return OpenFeatureAPI.getInstance().getClient();
    }
}
