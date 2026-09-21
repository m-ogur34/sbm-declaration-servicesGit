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
@ConfigurationProperties(prefix = "esb")
public class EsbProperties {

    @NotBlank
    private String baseUrl;

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(10);

    @NotNull
    private Duration readTimeout = Duration.ofSeconds(60);

    @NotNull
    private Ysv ysv = new Ysv();

    /** @return absolute URL of the send/update endpoint */
    public String beyannameUrl() {
        return baseUrl + ysv.getBeyannamePath();
    }

    /** @return absolute URL of the query endpoint */
    public String sorguUrl() {
        return baseUrl + ysv.getSorguPath();
    }

    @Getter
    @Setter
    public static class Ysv {

        @NotBlank
        private String beyannamePath = "/sbmDeclarationServices";

        /** Sorgu da aynı proxy path'i (bkz. {@link #beyannamePath}). */
        @NotBlank
        private String sorguPath = "/sbmDeclarationServices";
    }
}
