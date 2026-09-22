package tr.com.allianz.ysv.services.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tr.com.allianz.ysv.services.config.RestClientConfig;
import tr.com.allianz.ysv.services.config.TokenManagementProperties;
import tr.com.allianz.ysv.services.dto.internal.ClientCredentials;
import tr.com.allianz.ysv.services.dto.internal.TokenRequest;
import tr.com.allianz.ysv.services.dto.internal.TokenResponse;
import tr.com.allianz.ysv.services.dto.request.RequestContext;
import tr.com.allianz.ysv.services.enums.OperationType;
import tr.com.allianz.ysv.services.exception.TokenException;
import tr.com.allianz.ysv.services.util.MaskUtil;


@Slf4j
@Service
public class TokenManagementService {

    private final RestClient tokenRestClient;
    private final TokenManagementProperties properties;

    public TokenManagementService(@Qualifier(RestClientConfig.TOKEN_REST_CLIENT) RestClient tokenRestClient,
                                  TokenManagementProperties properties) {
        this.tokenRestClient = tokenRestClient;
        this.properties = properties;
    }


    /** Token servisi loglarında bu uygulamayı tanıtan ad. */
    static final String EXTERNAL_SERVICE_NAME = "sbm-declaration-services";

    /**
     * @param operationType   token'ın kullanılacağı işlem; token servisine
     *                        {@code externalFunctionName} olarak gider
     * @param context         isteği başlatan; kimliği varsa {@code clientIdentityType/No} olarak
     *                        gider, yoksa gönderilmez ve token servisi şirket VKN'sini döner
     * @param transactionId   bu SBM çağrısının kimliği; SBM'ye de {@code Transaction-Id} olarak gider
     * @return doğrulanmış token cevabı
     * @throws TokenException token servisine erişilemezse ya da cevap eksikse
     */
    public TokenResponse generateToken(OperationType operationType, RequestContext context, String transactionId) {
        TokenRequest request = TokenRequest.builder()
                .clientName(properties.getClientName())
                .transactionId(transactionId)
                .functionName(properties.getFunctionName())
                .companyCode(properties.getCompanyCode())
                .clientIdentityType(context.requesterIdType())
                .clientIdentityNo(context.requesterIdNo())
                .externalServiceName(EXTERNAL_SERVICE_NAME)
                .externalFunctionName(operationType.name())
                .build();

        log.info("Requesting SBM token: transactionId={}, operation={}, requester={}",
                transactionId, operationType,
                context.hasRequester()
                        ? context.requesterIdType() + "/" + MaskUtil.maskIdentity(
                                context.requesterIdType(), context.requesterIdNo())
                        : "şirket VKN (kimlik gönderilmedi)");

        TokenResponse response;
        try {
            response = tokenRestClient.post()
                    .uri(properties.tokenUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RestClientException ex) {
            // Adres / istisna ayrıntısı yalnızca loga; mesaj API cevabına ve ERROR_DETAILS'e gider.
            log.error("Token request failed: transactionId={}: {}", transactionId, ex.getMessage(), ex);
            throw new TokenException("Token servisine erişilemedi (transactionId=" + transactionId + ").", ex);
        }

        validate(response, transactionId);
        log.debug("SBM token acquired: transactionId={}, accessToken={}, requester={}/{}",
                transactionId,
                MaskUtil.mask(response.getAccessToken()),
                response.getClientCredentials().getClientIdentityType(),
                MaskUtil.maskIdentity(response.getClientCredentials().getClientIdentityType(),
                        response.getClientCredentials().getClientIdentityNo()));
        return response;
    }

    /**
     * SBM'de {@code Requester-ID-Type} ve {@code Requester-ID-No} zorunludur (SEC-00006); biri
     * eksikse istek SBM'ye gitmeden burada durdurulur.
     */
    private void validate(TokenResponse response, String transactionId) {
        if (response == null || isBlank(response.getAccessToken())) {
            throw new TokenException("Token servisi boş accessToken döndü (transactionId="
                    + transactionId + ").");
        }
        ClientCredentials credentials = response.getClientCredentials();
        if (credentials == null
                || isBlank(credentials.getClientIdentityType())
                || isBlank(credentials.getClientIdentityNo())) {
            throw new TokenException("Token servisi clientCredentials (kimlik tipi / numarası) döndürmedi "
                    + "(transactionId=" + transactionId + ").");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
