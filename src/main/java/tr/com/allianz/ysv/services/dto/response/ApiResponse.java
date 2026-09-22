package tr.com.allianz.ysv.services.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Uygulamanın tüm cevaplarının zarfı — SBM dokümanındaki yapının aynısı.
 *
 * <pre>
 * başarı: { "result": true,  "status": 200, "data":  { ... } }
 * hata:   { "result": false, "status": 422, "error": { "timestamp": "...", "reasons": [ ... ] } }
 * </pre>
 *
 * <p>{@code status} her zaman HTTP durum koduyla aynıdır.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(Boolean result, Integer status, T data, ApiError error) {

    public static <T> ApiResponse<T> of(boolean result, int status, T data) {
        return new ApiResponse<>(result, status, data, null);
    }

    public static <T> ApiResponse<T> ok(int status, T data) {
        return of(true, status, data);
    }

    public static <T> ApiResponse<T> failure(int status, List<ApiErrorReason> reasons) {
        return new ApiResponse<>(false, status, null,
                new ApiError(LocalDateTime.now(), reasons == null ? List.of() : List.copyOf(reasons)));
    }

    public static <T> ApiResponse<T> failure(int status, String code, String message) {
        return failure(status, List.of(ApiErrorReason.of(code, message)));
    }

    /** SBM'nin {@code error} bloğu. */
    public record ApiError(LocalDateTime timestamp, List<ApiErrorReason> reasons) {
    }

    /** SBM'nin {@code error.reasons[]} elemanı. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApiErrorReason(String field, String code, String message, String rejectedValue) {

        public static ApiErrorReason of(String code, String message) {
            return new ApiErrorReason(null, code, message, null);
        }

        public static ApiErrorReason ofField(String field, String code, String message) {
            return new ApiErrorReason(field, code, message, null);
        }
    }
}
