package tr.com.allianz.ysv.services.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
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
import tr.com.allianz.ysv.services.enums.SbmErrorCode;

/**
 * Tüm hataları tek {@link ErrorResponse} biçiminde döner.
 *
 * <p>{@link ResponseEntityExceptionHandler}'dan türer: bozuk JSON, eksik parametre, yanlış
 * HTTP metodu, desteklenmeyen içerik tipi, dosya boyutu aşımı gibi istemci hataları doğru
 * 4xx koduyla döner (catch-all'a düşüp 500 olmaz). İstemciye iç ayrıntı (istisna mesajı,
 * sınıf adı, adres) <b>verilmez</b>; ayrıntı yalnızca uygulama loguna yazılır.</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String VALIDATION_CODE = "ALZ-VALIDATION";
    static final String REQUEST_CODE = "ALZ-REQUEST";
    static final String INTERNAL_CODE = "ALZ-INTERNAL";
    static final String NOT_FOUND_CODE = "ALZ-NOT-FOUND";

    @ExceptionHandler(SbmIntegrationException.class)
    public ResponseEntity<ErrorResponse> handleSbmIntegration(SbmIntegrationException ex,
                                                              HttpServletRequest request) {
        log.error("SBM integration failure on {}: {} - {}",
                request.getRequestURI(), ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ErrorResponse.of(request.getRequestURI(), ex.getErrorCode(), ex.getMessage(),
                        List.of(SbmErrorCode.describe(ex.getErrorCode()))));
    }

    @ExceptionHandler(TokenException.class)
    public ResponseEntity<ErrorResponse> handleToken(TokenException ex, HttpServletRequest request) {
        log.error("Token acquisition failure on {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of(request.getRequestURI(), SbmErrorCode.SEC_00001.getCode(),
                        "Token alınamadığı için işlem gerçekleştirilemedi.", List.of(ex.getMessage())));
    }

    @ExceptionHandler(DeclarationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(DeclarationNotFoundException ex,
                                                        HttpServletRequest request) {
        log.warn("Declaration not found on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(request.getRequestURI(), NOT_FOUND_CODE, ex.getMessage(), List.of()));
    }

    /** Uygulamanın kendi doğrulama mesajları (Türkçe, iç ayrıntı içermez). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex,
                                                               HttpServletRequest request) {
        log.warn("Bad request on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(request.getRequestURI(), VALIDATION_CODE,
                        "İstek parametreleri geçersiz.", List.of(String.valueOf(ex.getMessage()))));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest request) {
        log.warn("Type mismatch on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(request.getRequestURI(), VALIDATION_CODE,
                        "İstek parametreleri geçersiz.", List.of(ex.getName() + ": geçersiz değer")));
    }

    /** Beklenmeyen hata: istemciye yalnızca genel mesaj; ayrıntı loga. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected failure on {}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(request.getRequestURI(), INTERNAL_CODE,
                        "Beklenmeyen bir hata oluştu.", List.of()));
    }

    // --- Spring MVC'nin standart istisnaları ----------------------------------------------

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::describeFieldError)
                .toList();
        log.warn("Invalid request on {}: {}", path(request), details);
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(path(request), VALIDATION_CODE, "İstek alanları geçersiz.", details));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<String> details = ex.getAllErrors().stream()
                .map(MessageSourceResolvable::getDefaultMessage)
                .filter(Objects::nonNull)
                .toList();
        log.warn("Invalid request on {}: {}", path(request), details);
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(path(request), VALIDATION_CODE, "İstek alanları geçersiz.", details));
    }

    /**
     * Diğer tüm standart MVC hataları (bozuk JSON 400, eksik parametre 400, yanlış metot 405,
     * içerik tipi 415, dosya boyutu 413, ...): kod korunur, gövde bizim biçimimize çevrilir,
     * istisna mesajı istemciye verilmez.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                             Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode,
                                                             WebRequest request) {
        log.warn("Request rejected on {} with HTTP {}: {}", path(request), statusCode.value(), ex.getMessage());
        return ResponseEntity.status(statusCode).headers(headers)
                .body(ErrorResponse.of(path(request), REQUEST_CODE, messageFor(statusCode), List.of()));
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

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servlet ? servlet.getRequest().getRequestURI() : null;
    }

    private static String describeFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
