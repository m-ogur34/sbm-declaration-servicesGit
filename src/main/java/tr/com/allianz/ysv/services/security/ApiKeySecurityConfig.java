package tr.com.allianz.ysv.services.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Geçerli API anahtarları ({@code api-key-security.api-keys[].api-key}). Değer Vault'tan gelir
 * ({@code SBM_DECLARATION_API_KEY}); tanımlı değilse uygulama açılmaz.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "api-key-security")
public class ApiKeySecurityConfig {

    @NotEmpty
    @Valid
    private List<ApiKeyConfig> apiKeys;

    @Getter
    @Setter
    public static class ApiKeyConfig {

        @NotBlank
        @Size(min = 32)
        private String apiKey;
    }
}
