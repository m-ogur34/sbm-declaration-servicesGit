package tr.com.allianz.ysv.services.exception;

import java.time.LocalDateTime;
import java.util.List;

public record ErrorResponse(LocalDateTime timestamp,
                            String path,
                            String code,
                            String message,
                            List<String> details) {

    public static ErrorResponse of(String path, String code, String message, List<String> details) {
        return new ErrorResponse(LocalDateTime.now(), path, code, message,
                details == null ? List.of() : List.copyOf(details));
    }
}
