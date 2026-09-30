package tr.com.allianz.ysv.services.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JsonErrorReportValveTest {

    private final JsonErrorReportValve valve = new JsonErrorReportValve();
    private final Request request = mock(Request.class);
    private final Response response = mock(Response.class);
    private final StringWriter out = new StringWriter();

    @BeforeEach
    void setUp() throws IOException {
        when(request.getRequestURI()).thenReturn("/sbm-declaration-services/api/v1/declarations/files/..%2f%23");
        when(response.getReporter()).thenReturn(new PrintWriter(out));
        when(response.setErrorReported()).thenReturn(true);
    }

    @Test
    @DisplayName("PEN 2.4: Tomcat'in reddettiği istek HTML yerine SBM hata biçiminde JSON alır")
    void error_isWrittenAsJson() throws IOException {
        when(response.getStatus()).thenReturn(400);

        valve.report(request, response, null);

        verify(response).setContentType("application/json");
        verify(response).finishResponse();
        assertThat(out.toString())
                .startsWith("{\"result\":false,\"status\":400,\"error\":{\"timestamp\":\"")
                .contains("\"code\":\"ALZ-REQUEST\"", "İstek gövdesi veya parametreleri geçersiz.")
                .doesNotContain("Tomcat", "<html");
    }

    @Test
    void successOrAlreadyWritten_isLeftAlone() throws IOException {
        when(response.getStatus()).thenReturn(200);
        valve.report(request, response, null);

        when(response.getStatus()).thenReturn(404);
        when(response.getContentWritten()).thenReturn(10L);
        valve.report(request, response, null);

        when(response.getContentWritten()).thenReturn(0L);
        when(response.setErrorReported()).thenReturn(false);
        valve.report(request, response, null);

        verify(response, never()).getReporter();
    }

    @Test
    void missingReporterOrWriteFailure_isSwallowed() throws IOException {
        when(response.getStatus()).thenReturn(400);
        when(response.getReporter()).thenReturn(null);
        valve.report(request, response, null);
        verify(response, never()).finishResponse();

        when(response.getReporter()).thenReturn(new PrintWriter(out));
        doThrow(new IOException("closed")).when(response).finishResponse();
        valve.report(request, response, null);
    }
}
