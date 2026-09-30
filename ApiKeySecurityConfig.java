package com.allianz.common.collection.aspect;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "api-key-security")
public class ApiKeySecurityConfig {

    private List<ApiKeyConfig> apiKeys;

    @Getter
    @Setter
    public static class ApiKeyConfig {
        private String apiKey;
        private List<String> permittedUrls;
        private List<String> allowedIps;
    }
}

