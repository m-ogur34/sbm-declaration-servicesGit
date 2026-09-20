package tr.com.allianz.ysv.services.exception;

import java.io.Serial;
import lombok.Getter;

@Getter
public class SbmIntegrationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String errorCode;

    public SbmIntegrationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
