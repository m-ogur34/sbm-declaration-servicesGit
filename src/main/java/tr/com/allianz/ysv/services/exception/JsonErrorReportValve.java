package tr.com.allianz.ysv.services.exception;

import java.io.PrintWriter;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.http.HttpStatusCode;

/**
 * Tomcat'in HTML hata sayfası yerine SBM hata biçiminde JSON döner (PEN 2.4).
 *
 * <p>Spring'e hiç ulaşmayan istekler (ör. URL'de {@code %2f}; Tomcat bağlayıcısı reddeder)
 * {@link GlobalExceptionHandler}'dan geçmez; Tomcat bu valve ile cevap üretir. Varsayılanı
 * sunucu adı/sürümü içeren HTML'dir. Burada yalnız durum kodu ve genel mesaj yazılır.</p>
 */
@Slf4j
public class JsonErrorReportValve extends ErrorReportValve {

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        int status = response.getStatus();
        if (status < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }
        log.warn("Request rejected by Tomcat on {} with HTTP {}", request.getRequestURI(), status);
        try {
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            PrintWriter writer = response.getReporter();
            if (writer != null) {
                writer.write(body(status));
                response.finishResponse();
            }
        } catch (Exception ex) {
            log.warn("Tomcat error response could not be written: {}", ex.toString());
        }
    }

    static String body(int status) {
        return "{\"result\":false,\"status\":" + status + ",\"error\":{\"timestamp\":\"" + LocalDateTime.now()
                + "\",\"reasons\":[{\"code\":\"" + GlobalExceptionHandler.REQUEST_CODE + "\",\"message\":\""
                + GlobalExceptionHandler.messageFor(HttpStatusCode.valueOf(status)) + "\"}]}}";
    }
}
