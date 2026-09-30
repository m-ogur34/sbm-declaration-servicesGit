package tr.com.allianz.ysv.services.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import tr.com.allianz.ysv.services.exception.ApiKeyException;

class ApiKeySecurityInterceptorTest {

    private final String keyA = UUID.randomUUID().toString();
    private final String keyB = UUID.randomUUID().toString();

    private ApiKeySecurityInterceptor interceptor;
    private MockHttpServletRequest request;
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @ApiKeySecurityAnnotation
    static class SecuredController {
        public void call() {
        }
    }

    static class PlainController {
        public void open() {
        }

        @ApiKeySecurityAnnotation
        public void secured() {
        }
    }

    @BeforeEach
    void setUp() {
        ApiKeySecurityConfig config = new ApiKeySecurityConfig();
        config.setApiKeys(List.of(key(keyA), key(keyB)));
        interceptor = new ApiKeySecurityInterceptor(config);
        request = new MockHttpServletRequest("POST", "/api/v1/declarations/send");
    }

    private static ApiKeySecurityConfig.ApiKeyConfig key(String value) {
        ApiKeySecurityConfig.ApiKeyConfig key = new ApiKeySecurityConfig.ApiKeyConfig();
        key.setApiKey(value);
        return key;
    }

    private static HandlerMethod handler(Object bean, String method) throws NoSuchMethodException {
        return new HandlerMethod(bean, bean.getClass().getMethod(method));
    }

    @Test
    @DisplayName("sınıfı işaretli controller'a geçerli anahtar (listedeki herhangi biri) ile girilir")
    void validKey_passes() throws Exception {
        request.addHeader(ApiKeySecurityInterceptor.API_KEY_HEADER, keyB);

        assertThat(interceptor.preHandle(request, response, handler(new SecuredController(), "call"))).isTrue();
    }

    @Test
    void missingKey_isRejected() throws Exception {
        HandlerMethod secured = handler(new SecuredController(), "call");

        assertThatThrownBy(() -> interceptor.preHandle(request, response, secured))
                .isInstanceOf(ApiKeyException.class);
    }

    @Test
    void blankKey_isRejected() throws Exception {
        request.addHeader(ApiKeySecurityInterceptor.API_KEY_HEADER, " ");
        HandlerMethod secured = handler(new SecuredController(), "call");

        assertThatThrownBy(() -> interceptor.preHandle(request, response, secured))
                .isInstanceOf(ApiKeyException.class);
    }

    @Test
    void wrongKey_isRejected() throws Exception {
        request.addHeader(ApiKeySecurityInterceptor.API_KEY_HEADER, keyA + "x");
        HandlerMethod secured = handler(new SecuredController(), "call");

        assertThatThrownBy(() -> interceptor.preHandle(request, response, secured))
                .isInstanceOf(ApiKeyException.class)
                .hasMessage("API anahtarı eksik veya geçersiz.");
    }

    @Test
    @DisplayName("metot seviyesinde işaret de korur; işaretsiz metot ve controller dışı handler serbesttir")
    void onlyAnnotatedHandlersAreChecked() throws Exception {
        PlainController plain = new PlainController();
        HandlerMethod secured = handler(plain, "secured");

        assertThat(interceptor.preHandle(request, response, handler(plain, "open"))).isTrue();
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThatThrownBy(() -> interceptor.preHandle(request, response, secured))
                .isInstanceOf(ApiKeyException.class);
    }
}
