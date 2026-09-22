package tr.com.allianz.ysv.services.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tr.com.allianz.ysv.services.dto.response.ApiResponse;
import tr.com.allianz.ysv.services.enums.SbmErrorCode;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/declarations/send");
    }

    private static void assertSbmShape(ApiResponse<?> body, int status, String code) {
        assertThat(body.result()).isFalse();
        assertThat(body.status()).isEqualTo(status);
        assertThat(body.data()).isNull();
        assertThat(body.error().timestamp()).isNotNull();
        assertThat(body.error().reasons()).first().satisfies(r -> assertThat(r.code()).isEqualTo(code));
    }

    @Test
    @DisplayName("a pre-flight SBM rule violation is a 422 carrying SBM's code")
    void handleSbmIntegration_returns422() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleSbmIntegration(
                new SbmIntegrationException(SbmErrorCode.CORE_01008.getCode(), "en fazla 36 karakter"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertSbmShape(response.getBody(), 422, "CORE-01008");
        assertThat(response.getBody().error().reasons().get(0).message()).isEqualTo("en fazla 36 karakter");
    }

    @Test
    void handleToken_returns503() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleToken(new TokenException("Token servisine erişilemedi (transactionId=t)."), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertSbmShape(response.getBody(), 503, SbmErrorCode.SEC_00001.getCode());
    }

    @Test
    void handleNotFound_returns404() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleNotFound(new DeclarationNotFoundException("Beyanname bulunamadı: X"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertSbmShape(response.getBody(), 404, GlobalExceptionHandler.NOT_FOUND_CODE);
    }

    @Test
    void handleIllegalArgument_returns400WithOurMessage() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleIllegalArgument(new IllegalArgumentException("ay 1-12 aralığında olmalı"), request);

        assertSbmShape(response.getBody(), 400, GlobalExceptionHandler.VALIDATION_CODE);
        assertThat(response.getBody().error().reasons().get(0).message()).isEqualTo("ay 1-12 aralığında olmalı");
    }

    @Test
    @DisplayName("a type mismatch names the field but never echoes the rejected value")
    void handleTypeMismatch_namesOnlyTheField() throws Exception {
        MethodParameter parameter = new MethodParameter(Object.class.getMethod("equals", Object.class), 0);
        ResponseEntity<ApiResponse<Void>> response = handler.handleTypeMismatch(
                new MethodArgumentTypeMismatchException("<script>", Integer.class, "year", parameter, null), request);

        assertSbmShape(response.getBody(), 400, GlobalExceptionHandler.VALIDATION_CODE);
        assertThat(response.getBody().error().reasons().get(0).field()).isEqualTo("year");
        assertThat(response.getBody().error().reasons().get(0).rejectedValue()).isNull();
    }

    @Test
    @DisplayName("an unexpected failure never exposes the exception message")
    void handleUnexpected_hidesTheDetail() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleUnexpected(new IllegalStateException("ORA-00942 table does not exist"), request);

        assertSbmShape(response.getBody(), 500, GlobalExceptionHandler.INTERNAL_CODE);
        assertThat(response.getBody().error().reasons().get(0).message()).isEqualTo("Beklenmeyen bir hata oluştu.");
    }

    @Test
    @DisplayName("standard MVC failures keep their status and get SBM's error shape without the internal message")
    void handleExceptionInternal_wrapsStandardFailures() {
        ResponseEntity<Object> response = handler.handleExceptionInternal(
                new IllegalStateException("Jackson: Unexpected end-of-input"), null, new HttpHeaders(),
                HttpStatus.METHOD_NOT_ALLOWED, new ServletWebRequest(request));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        ApiResponse<?> body = (ApiResponse<?>) response.getBody();
        assertSbmShape(body, 405, GlobalExceptionHandler.REQUEST_CODE);
        assertThat(body.error().reasons().get(0).message()).doesNotContain("Jackson");
    }

    @Test
    void messageFor_coversTheCommonStatuses() {
        assertThat(GlobalExceptionHandler.messageFor(HttpStatusCode.valueOf(400))).contains("geçersiz");
        assertThat(GlobalExceptionHandler.messageFor(HttpStatusCode.valueOf(404))).contains("bulunamadı");
        assertThat(GlobalExceptionHandler.messageFor(HttpStatusCode.valueOf(405))).contains("metodu");
        assertThat(GlobalExceptionHandler.messageFor(HttpStatusCode.valueOf(406))).contains("üretilemiyor");
        assertThat(GlobalExceptionHandler.messageFor(HttpStatusCode.valueOf(413))).contains("10MB");
        assertThat(GlobalExceptionHandler.messageFor(HttpStatusCode.valueOf(415))).contains("İçerik tipi");
        assertThat(GlobalExceptionHandler.messageFor(HttpStatusCode.valueOf(409))).isEqualTo("İstek işlenemedi.");
    }
}
