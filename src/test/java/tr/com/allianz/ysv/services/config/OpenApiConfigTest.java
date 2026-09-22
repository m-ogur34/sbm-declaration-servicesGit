package tr.com.allianz.ysv.services.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;
import tr.com.allianz.ysv.services.dto.request.RequestContext;

class OpenApiConfigTest {

    @SuppressWarnings("unused")
    static class Sample {
        public void withContext(RequestContext context) {
        }

        public void withoutContext(String value) {
        }
    }

    @Test
    void openApi_describesTheDeclarationApi() {
        OpenAPI openApi = new OpenApiConfig().sbmDeclarationOpenApi();

        assertThat(openApi.getInfo().getTitle()).isEqualTo("SBM Declaration Services");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("v1");
        assertThat(openApi.getInfo().getDescription()).contains("SBM");
        assertThat(openApi.getInfo().getContact().getName()).isEqualTo("Allianz Sigorta - YSV");
    }

    @Test
    void operationsTakingARequestContext_documentTheThreeHeaders() throws Exception {
        HandlerMethod method = new HandlerMethod(new Sample(), Sample.class.getMethod("withContext", RequestContext.class));

        Operation operation = new OpenApiConfig().requestContextHeaders().customize(new Operation(), method);

        assertThat(operation.getParameters()).extracting(Parameter::getName).containsExactly(
                "X-User-Name", "X-Requester-Id-Type", "X-Requester-Id-No");
        assertThat(operation.getParameters()).allSatisfy(p -> {
            assertThat(p.getIn()).isEqualTo("header");
            assertThat(p.getRequired()).isFalse();
        });
    }

    @Test
    void otherOperations_areLeftAlone() throws Exception {
        HandlerMethod method = new HandlerMethod(new Sample(), Sample.class.getMethod("withoutContext", String.class));

        Operation operation = new OpenApiConfig().requestContextHeaders().customize(new Operation(), method);

        assertThat(operation.getParameters()).isNull();
    }
}
