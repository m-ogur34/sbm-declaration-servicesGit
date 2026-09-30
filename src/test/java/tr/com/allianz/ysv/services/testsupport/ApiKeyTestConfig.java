package tr.com.allianz.ysv.services.testsupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import tr.com.allianz.ysv.services.security.ApiKeySecurityConfig;
import tr.com.allianz.ysv.services.security.ApiKeySecurityInterceptor;

/**
 * {@code @WebMvcTest}'lerde her isteğe geçerli {@code X-ApiKey} ekler. Anahtar test
 * application.yml'de {@code ${random.uuid}} ile üretilir; kodda sabit anahtar metni yoktur.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ApiKeyTestConfig {

    @Bean
    MockMvcBuilderCustomizer apiKeyHeader(ApiKeySecurityConfig config) {
        String apiKey = config.getApiKeys().get(0).getApiKey();
        return builder -> builder.defaultRequest(get("/").header(ApiKeySecurityInterceptor.API_KEY_HEADER, apiKey));
    }
}
