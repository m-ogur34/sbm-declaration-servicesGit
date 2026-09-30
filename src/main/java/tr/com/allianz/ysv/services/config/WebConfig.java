package tr.com.allianz.ysv.services.config;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tr.com.allianz.ysv.services.security.ApiKeySecurityConfig;
import tr.com.allianz.ysv.services.security.ApiKeySecurityInterceptor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApiKeySecurityConfig.class)
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final ApiKeySecurityConfig apiKeySecurityConfig;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new RequestContextArgumentResolver());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ApiKeySecurityInterceptor(apiKeySecurityConfig));
    }
}
