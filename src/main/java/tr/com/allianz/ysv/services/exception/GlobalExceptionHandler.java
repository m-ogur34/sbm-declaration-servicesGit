package tr.com.allianz.ysv.services.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tr.com.allianz.ysv.services.dto.response.ApiResponse;
import tr.com.allianz.ysv.services.dto.response.ApiResponse.ApiErrorReason;
import tr.com.allianz.ysv.services.enums.SbmErrorCode;

/**
 * Tüm hataları SBM dokümanındaki hata biçiminde döner:
 * {@code {"result":false,"status":<http>,"error":{"timestamp":...,"reasons":[{field,code,message}]}}}.
 *
 * <p>{@link ResponseEntityExceptionHandler}'dan türer: bozuk JSON, eksik parametre, yanlış
 * metot, içerik tipi, dosya boyutu gibi istemci hataları doğru 4xx koduyla döner. İstemciye iç
 * ayrıntı (istisna mesajı, sınıf adı, adres, reddedilen değer) verilmez; ayrıntı loga yazılır.</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String VALIDATION_CODE = "ALZ-VALIDATION";
    static final String REQUEST_CODE = "ALZ-REQUEST";
    static final String INTERNAL_CODE = "ALZ-INTERNAL";
    static final String NOT_FOUND_CODE = "ALZ-NOT-FOUND";

    /** SBM'ye gitmeden, SBM kodlu ön doğrulama (ör. ysvDosyaNo 36 karakteri aşıyor). */
    @ExceptionHandler(SbmIntegrationException.class)
    public ResponseEntity<ApiResponse<Void>> handleSbmIntegration(SbmIntegrationException ex,
                                                                  HttpServletRequest request) {
        log.warn("SBM pre-flight validation failed on {}: {} - {}",
                request.getRequestURI(), ex.getErrorCode(), ex.getMessage());
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(TokenException.class)
    public ResponseEntity<ApiResponse<Void>> handleToken(TokenException ex, HttpServletRequest request) {
        log.error("Token acquisition failure on {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return respond(HttpStatus.SERVICE_UNAVAILABLE, SbmErrorCode.SEC_00001.getCode(), ex.getMessage());
    }

    @ExceptionHandler(DeclarationNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(DeclarationNotFoundException ex,
                                                            HttpServletRequest request) {
        log.warn("Declaration not found on {}: {}", request.getRequestURI(), ex.getMessage());
        return respond(HttpStatus.NOT_FOUND, NOT_FOUND_CODE, ex.getMessage());
    }

    /** Uygulamanın kendi doğrulama mesajları (Türkçe, iç ayrıntı içermez). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex,
                                                                   HttpServletRequest request) {
        log.warn("Bad request on {}: {}", request.getRequestURI(), ex.getMessage());
        return respond(HttpStatus.BAD_REQUEST, VALIDATION_CODE, String.valueOf(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                                HttpServletRequest request) {
        log.warn("Type mismatch on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.failure(400,
                List.of(ApiErrorReason.ofField(ex.getName(), VALIDATION_CODE, "Geçersiz değer."))));
    }

    /** Beklenmeyen hata: istemciye yalnızca genel mesaj; ayrıntı loga. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected failure on {}", request.getRequestURI(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_CODE, "Beklenmeyen bir hata oluştu.");
    }

    // --- Spring MVC'nin standart istisnaları ----------------------------------------------

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<ApiErrorReason> reasons = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::reason)
                .toList();
        log.warn("Invalid request on {}: {}", path(request), reasons);
        return ResponseEntity.badRequest().body(ApiResponse.failure(400, reasons));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<ApiErrorReason> reasons = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> ApiErrorReason.ofField(result.getMethodParameter().getParameterName(),
                                VALIDATION_CODE, messageOf(error))))
                .toList();
        log.warn("Invalid request on {}: {}", path(request), reasons);
        return ResponseEntity.badRequest().body(ApiResponse.failure(400, reasons));
    }

    /**
     * Diğer standart MVC hataları (bozuk JSON 400, eksik parametre 400, yanlış metot 405,
     * içerik tipi 415, dosya boyutu 413, ...): kod korunur, gövde SBM hata biçimine çevrilir.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                             Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode,
                                                             WebRequest request) {
        log.warn("Request rejected on {} with HTTP {}: {}", path(request), statusCode.value(), ex.getMessage());
        return ResponseEntity.status(statusCode).headers(headers)
                .body(ApiResponse.failure(statusCode.value(), REQUEST_CODE, messageFor(statusCode)));
    }

    static String messageFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "İstek gövdesi veya parametreleri geçersiz.";
            case 404 -> "Kaynak bulunamadı.";
            case 405 -> "Bu HTTP metodu desteklenmiyor.";
            case 406 -> "İstenen içerik tipi üretilemiyor.";
            case 413 -> "Dosya boyutu sınırı aşıldı (en fazla 10MB).";
            case 415 -> "İçerik tipi desteklenmiyor.";
            default -> "İstek işlenemedi.";
        };
    }

    private static ResponseEntity<ApiResponse<Void>> respond(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ApiResponse.failure(status.value(), code, message));
    }

    private static ApiErrorReason reason(FieldError error) {
        return ApiErrorReason.ofField(error.getField(), VALIDATION_CODE, error.getDefaultMessage());
    }

    private static String messageOf(MessageSourceResolvable error) {
        return error.getDefaultMessage() == null ? "Geçersiz değer." : error.getDefaultMessage();
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servlet ? servlet.getRequest().getRequestURI() : null;
    }
}
