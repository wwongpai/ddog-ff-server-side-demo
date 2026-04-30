package com.example.ffdemo.config;

import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.exceptions.ProviderNotReadyError;
import datadog.trace.api.openfeature.Provider;
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

        try {
            api.setProviderAndWait(new Provider());
            log.info("Datadog OpenFeature provider initialized successfully");
        } catch (ProviderNotReadyError e) {
            log.warn("Provider not ready yet, will use defaults until config arrives: {}", e.getMessage());
            api.setProvider(new Provider());
        } catch (Exception e) {
            log.error("Failed to initialize OpenFeature provider", e);
            api.setProvider(new Provider());
        }
    }

    @Bean
    public Client openFeatureClient() {
        return OpenFeatureAPI.getInstance().getClient();
    }
}
