package tr.com.allianz.ysv.services.testsupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import tr.com.allianz.ysv.services.security.ApiKeySecurityInterceptor;

/** {@code @WebMvcTest}'lerde her isteğe geçerli {@code X-ApiKey} ekler (test application.yml ile aynı). */
@TestConfiguration(proxyBeanMethods = false)
public class ApiKeyTestConfig {

    public static final String API_KEY = "test-api-key-0123456789abcdef0123";

    @Bean
    MockMvcBuilderCustomizer apiKeyHeader() {
        return builder -> builder.defaultRequest(get("/").header(ApiKeySecurityInterceptor.API_KEY_HEADER, API_KEY));
    }
}
