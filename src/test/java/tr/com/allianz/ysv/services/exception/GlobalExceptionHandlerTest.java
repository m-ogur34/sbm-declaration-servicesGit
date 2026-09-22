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
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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

    @Test
    @DisplayName("an SBM failure keeps SBM's code and adds its Turkish description")
    void handleSbmIntegration_returns502WithSbmCode() {
        ResponseEntity<ErrorResponse> response = handler.handleSbmIntegration(
                new SbmIntegrationException(SbmErrorCode.RISK_HAVUZU_00007.getCode(),
                        "Büyükşehirde ilçe gönderilemez."), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("RISK-HAVUZU-00007");
        assertThat(response.getBody().path()).isEqualTo("/api/v1/declarations/send");
        assertThat(response.getBody().timestamp()).isNotNull();
        assertThat(response.getBody().details())
                .containsExactly(SbmErrorCode.RISK_HAVUZU_00007.getDescription());
    }

    @Test
    void handleToken_returns503() {
        ResponseEntity<ErrorResponse> response =
                handler.handleToken(new TokenException("Token servisine erişilemedi (transactionId=t)."), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().code()).isEqualTo(SbmErrorCode.SEC_00001.getCode());
        assertThat(response.getBody().details()).containsExactly("Token servisine erişilemedi (transactionId=t).");
    }

    @Test
    void handleNotFound_returns404() {
        ResponseEntity<ErrorResponse> response =
                handler.handleNotFound(new DeclarationNotFoundException("Beyanname bulunamadı: X"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo(GlobalExceptionHandler.NOT_FOUND_CODE);
        assertThat(response.getBody().message()).isEqualTo("Beyanname bulunamadı: X");
    }

    @Test
    void handleIllegalArgument_returns400WithOurMessage() {
        ResponseEntity<ErrorResponse> response =
                handler.handleIllegalArgument(new IllegalArgumentException("ay 1-12 aralığında olmalı"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo(GlobalExceptionHandler.VALIDATION_CODE);
        assertThat(response.getBody().details()).containsExactly("ay 1-12 aralığında olmalı");
    }

    @Test
    @DisplayName("a type mismatch names the parameter but never echoes the rejected value")
    void handleTypeMismatch_namesOnlyTheParameter() throws Exception {
        MethodParameter parameter = new MethodParameter(Object.class.getMethod("equals", Object.class), 0);
        ResponseEntity<ErrorResponse> response = handler.handleTypeMismatch(
                new MethodArgumentTypeMismatchException("<script>", Integer.class, "year", parameter, null), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().details()).containsExactly("year: geçersiz değer");
    }

    @Test
    @DisplayName("an unexpected failure never exposes the exception message")
    void handleUnexpected_hidesTheDetail() {
        ResponseEntity<ErrorResponse> response =
                handler.handleUnexpected(new IllegalStateException("ORA-00942 table does not exist"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo(GlobalExceptionHandler.INTERNAL_CODE);
        assertThat(response.getBody().message()).isEqualTo("Beklenmeyen bir hata oluştu.");
        assertThat(response.getBody().details()).isEmpty();
    }

    @Test
    @DisplayName("standard MVC failures keep their status, get our body and no internal message")
    void handleExceptionInternal_wrapsStandardFailures() {
        WebRequest webRequest = new ServletWebRequest(request);

        ResponseEntity<Object> response = handler.handleExceptionInternal(
                new IllegalStateException("Jackson: Unexpected end-of-input"), null, new HttpHeaders(),
                HttpStatus.METHOD_NOT_ALLOWED, webRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertThat(body.code()).isEqualTo(GlobalExceptionHandler.REQUEST_CODE);
        assertThat(body.path()).isEqualTo("/api/v1/declarations/send");
        assertThat(body.message()).doesNotContain("Jackson");
        assertThat(body.details()).isEmpty();
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
