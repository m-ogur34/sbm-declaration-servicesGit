package tr.com.allianz.ysv.services.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import tr.com.allianz.ysv.services.exception.ApiKeyException;

/**
 * {@link ApiKeySecurityAnnotation} taşıyan uçlarda {@code X-ApiKey} başlığını doğrular (PEN 2.2).
 *
 * <p>Interceptor, istek gövdesi okunup doğrulanmadan <b>önce</b> çalışır: anahtarsız istek
 * gövde/başlık hatası yerine her zaman 401 alır. Karşılaştırma sabit sürelidir; anahtar loga
 * yazılmaz.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class ApiKeySecurityInterceptor implements HandlerInterceptor {

    public static final String API_KEY_HEADER = "X-ApiKey";

    private final ApiKeySecurityConfig config;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || !isSecured(method)) {
            return true;
        }
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey == null || apiKey.isBlank() || !matches(apiKey)) {
            log.warn("API key rejected ({}) on {} {} from {}", apiKey == null || apiKey.isBlank() ? "missing" : "invalid",
                    request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
            throw new ApiKeyException();
        }
        return true;
    }

    private static boolean isSecured(HandlerMethod method) {
        return method.hasMethodAnnotation(ApiKeySecurityAnnotation.class)
                || method.getBeanType().isAnnotationPresent(ApiKeySecurityAnnotation.class);
    }

    private boolean matches(String apiKey) {
        byte[] given = apiKey.getBytes(StandardCharsets.UTF_8);
        boolean matched = false;
        for (ApiKeySecurityConfig.ApiKeyConfig key : config.getApiKeys()) {
            matched |= MessageDigest.isEqual(given, key.getApiKey().getBytes(StandardCharsets.UTF_8));
        }
        return matched;
    }
}
