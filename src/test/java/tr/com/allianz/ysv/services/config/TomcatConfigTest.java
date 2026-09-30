package tr.com.allianz.ysv.services.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardHost;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import tr.com.allianz.ysv.services.exception.JsonErrorReportValve;

class TomcatConfigTest {

    @Test
    void hostErrorReportValve_isReplaced() {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        new TomcatConfig().jsonErrorReportValve().customize(factory);
        StandardHost host = new StandardHost();
        StandardContext context = new StandardContext();
        context.setName("");
        context.setPath("");
        host.addChild(context);
        StandardContext orphan = new StandardContext();

        factory.getTomcatContextCustomizers().forEach(c -> {
            c.customize(context);
            c.customize(orphan);
        });

        assertThat(host.getErrorReportValveClass()).isEqualTo(JsonErrorReportValve.class.getName());
    }
}
