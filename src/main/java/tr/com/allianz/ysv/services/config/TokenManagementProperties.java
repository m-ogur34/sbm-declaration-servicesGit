package tr.com.allianz.ysv.services.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "token-management")
public class TokenManagementProperties {

    @NotBlank
    private String baseUrl;

    @NotBlank
    private String path;

    @NotBlank
    private String clientName;

    @NotBlank
    private String functionName = "test";


    @NotBlank
    private String companyCode;

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(5);

    @NotNull
    private Duration readTimeout = Duration.ofSeconds(30);

    public String tokenUrl() {
        return baseUrl + path;
    }
}
