package tr.com.allianz.ysv.services.exception;

import java.io.Serial;


public class TokenException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public TokenException(String message) {
        super(message);
    }

    public TokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
