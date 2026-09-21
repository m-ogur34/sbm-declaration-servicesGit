package tr.com.allianz.ysv.services.exception;

/** İstenen beyanname satırı veritabanında yoksa fırlatılır (HTTP 404). */
public class DeclarationNotFoundException extends RuntimeException {

    public DeclarationNotFoundException(String message) {
        super(message);
    }
}
