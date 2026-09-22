package tr.com.allianz.ysv.services.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.util.Arrays;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tr.com.allianz.ysv.services.dto.request.RequestContext;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    static {
        // RequestContext bir sorgu nesnesi değil; header'lardan çözülür (RequestContextArgumentResolver).
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(RequestContext.class);
    }

    @Bean
    public OpenAPI sbmDeclarationOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SBM Declaration Services")
                        .version("v1")
                        .description("Yangın Sigorta Vergisi (YSV) beyannamelerinin ESB üzerinden "
                                + "SBM'ye gönderilmesi, güncellenmesi ve sorgulanması.")
                        .contact(new Contact().name("Allianz Sigorta - YSV")));
    }

    /** {@link RequestContext} alan her uca üç opsiyonel header'ı dokümante eder. */
    @Bean
    public OperationCustomizer requestContextHeaders() {
        return (operation, handlerMethod) -> {
            boolean usesContext = Arrays.stream(handlerMethod.getMethodParameters())
                    .anyMatch(p -> RequestContext.class.equals(p.getParameterType()));
            if (usesContext) {
                operation.addParametersItem(header(RequestContext.USER_HEADER,
                        "İşlemi yapan kullanıcı; DB'deki *_BY_USER kolonlarına yazılır. Yoksa SYSTEM."));
                operation.addParametersItem(header(RequestContext.REQUESTER_ID_TYPE_HEADER,
                        "İşlemi yapanın kimlik tipi: 1 = T.C. Kimlik No, 2 = VKN, 4 = Yabancı Kimlik No. "
                                + "Kimlik numarasıyla birlikte gönderilir; ikisi de yoksa şirket VKN'si kullanılır."));
                operation.addParametersItem(header(RequestContext.REQUESTER_ID_NO_HEADER,
                        "İşlemi yapanın kimlik numarası (TCKN/YKN 11, VKN 10 hane)."));
            }
            return operation;
        };
    }

    private static Parameter header(String name, String description) {
        return new HeaderParameter().name(name).required(false).description(description)
                .schema(new StringSchema());
    }
}
