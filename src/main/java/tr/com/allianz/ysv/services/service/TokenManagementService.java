package tr.com.allianz.ysv.services.service;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tr.com.allianz.ysv.services.config.RestClientConfig;
import tr.com.allianz.ysv.services.config.TokenManagementProperties;
import tr.com.allianz.ysv.services.dto.internal.TokenRequest;
import tr.com.allianz.ysv.services.dto.internal.TokenResponse;
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


    public TokenResponse generateToken(OperationType operationType) {
        String transactionId = UUID.randomUUID().toString();
        TokenRequest request = TokenRequest.builder()
                .clientName(properties.getClientName())
                .transactionId(transactionId)
                .functionName(properties.getFunctionName())
                .userName(properties.getUserName())
                .companyCode(properties.getCompanyCode())
                .build();

        log.debug("Requesting SBM token: transactionId={}, functionName={}, operation={}",
                transactionId, request.getFunctionName(), operationType);

        TokenResponse response;
        try {
            response = tokenRestClient.post()
                    .uri(properties.tokenUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RestClientException ex) {
            throw new TokenException("Token servisine erişilemedi (transactionId=" + transactionId
                    + "): " + ex.getMessage(), ex);
        }

        validate(response, transactionId);
        log.debug("SBM token acquired: transactionId={}, accessToken={}, clientIdNumber={}",
                transactionId,
                MaskUtil.mask(response.getAccessToken()),
                MaskUtil.mask(response.getClientCredentials().getClientIdNumber()));
        return response;
    }

    private void validate(TokenResponse response, String transactionId) {
        if (response == null || response.getAccessToken() == null || response.getAccessToken().isBlank()) {
            throw new TokenException("Token servisi boş accessToken döndü (transactionId="
                    + transactionId + ").");
        }
        if (response.getClientCredentials() == null
                || response.getClientCredentials().getClientIdNumber() == null
                || response.getClientCredentials().getClientIdNumber().isBlank()) {
            throw new TokenException("Token servisi clientCredentials bilgisi döndürmedi (transactionId="
                    + transactionId + ").");
        }
    }
}
