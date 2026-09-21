package tr.com.allianz.ysv.services.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tr.com.allianz.ysv.services.config.EsbProperties;
import tr.com.allianz.ysv.services.config.RestClientConfig;
import tr.com.allianz.ysv.services.config.SbmProperties;
import tr.com.allianz.ysv.services.dto.internal.SbmCallResult;
import tr.com.allianz.ysv.services.dto.internal.SbmDeclarationRequest;
import tr.com.allianz.ysv.services.dto.internal.SbmDeclarationResponse;
import tr.com.allianz.ysv.services.dto.internal.SbmError;
import tr.com.allianz.ysv.services.dto.internal.SbmErrorReason;
import tr.com.allianz.ysv.services.dto.internal.SbmQueryRequest;
import tr.com.allianz.ysv.services.dto.internal.TokenResponse;
import tr.com.allianz.ysv.services.enums.OperationType;
import tr.com.allianz.ysv.services.enums.SbmErrorCode;
import tr.com.allianz.ysv.services.exception.TokenException;
import tr.com.allianz.ysv.services.util.JsonUtil;

@Slf4j
@Service
public class SbmClientService {

    static final String TRANSACTION_ID_HEADER = "Transaction-Id";
    static final String REQUESTER_ID_TYPE_HEADER = "Requester-ID-Type";
    static final String REQUESTER_ID_NO_HEADER = "Requester-ID-No";

    private final RestClient esbRestClient;
    private final TokenManagementService tokenManagementService;
    private final EsbProperties esbProperties;
    private final SbmProperties sbmProperties;
    private final JsonUtil jsonUtil;

    public SbmClientService(@Qualifier(RestClientConfig.ESB_REST_CLIENT) RestClient esbRestClient,
                            TokenManagementService tokenManagementService,
                            EsbProperties esbProperties,
                            SbmProperties sbmProperties,
                            JsonUtil jsonUtil) {
        this.esbRestClient = esbRestClient;
        this.tokenManagementService = tokenManagementService;
        this.esbProperties = esbProperties;
        this.sbmProperties = sbmProperties;
        this.jsonUtil = jsonUtil;
    }

    /** Yeni beyanname: {@code ysv-beyanname} üzerinde HTTP POST. */
    public SbmCallResult send(SbmDeclarationRequest request) {
        return callWithRetry(HttpMethod.POST, esbProperties.beyannameUrl(), request, OperationType.POST);
    }

    /** Beyanname güncelleme (iptal akışı da bunu kullanır): {@code ysv-beyanname} üzerinde HTTP PUT. */
    public SbmCallResult update(SbmDeclarationRequest request) {
        return callWithRetry(HttpMethod.PUT, esbProperties.beyannameUrl(), request, OperationType.PUT);
    }


    public SbmCallResult query(SbmQueryRequest request) {
        String url = UriComponentsBuilder.fromUriString(esbProperties.sorguUrl())
                .queryParam("sigortaSirketKodu", request.getSigortaSirketKodu())
                .queryParam("ysvDosyaNo", request.getYsvDosyaNo())
                .toUriString();
        return callWithRetry(HttpMethod.GET, url, request, OperationType.GET);
    }

    private SbmCallResult callWithRetry(HttpMethod method, String url, Object body, OperationType operationType) {
        int maxAttempts = Math.max(1, sbmProperties.getRetry().getMaxAttempts());
        for (int attempt = 1; ; attempt++) {
            SbmCallResult result = call(method, url, body, operationType);
            if (result.isSuccess() || attempt >= maxAttempts || !isRetryable(result)) {
                return result;
            }
            log.warn("SBM {} call failed with a retryable error, retrying ({}/{}): httpStatus={}, code={}, transactionId={}",
                    operationType, attempt + 1, maxAttempts, result.getHttpStatus(),
                    result.getErrorCode(), result.getTransactionId());
        }
    }


    static boolean isRetryable(SbmCallResult result) {
        if (result.getHttpStatus() >= 500) {
            return true;
        }
        return SbmErrorCode.isRetryableCode(result.getErrorCode());
    }

    private SbmCallResult call(HttpMethod method, String url, Object body, OperationType operationType) {
        String requestPayload = body == null ? null : jsonUtil.toJson(body);
        TokenResponse token = tokenManagementService.generateToken(operationType);
        // ESB yönlendirmesini izlemek için: her SBM çağrısının gittiği tam URL loglanır.
        log.info("SBM {} call -> {} {}", operationType, method, url);
        try {
            RestClient.RequestBodySpec spec = esbRestClient.method(method)
                    .uri(url)
                    .headers(headers -> applyAuthHeaders(headers, token));
            // GET'te gövde gönderilmez; sorgu parametreleri URL'de query string olarak taşınır.
            if (body != null && method != HttpMethod.GET) {
                spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return spec.exchange((request, response) -> toResult(response, requestPayload, operationType));
        } catch (Exception ex) {
            log.error("SBM {} call could not be completed: {}", operationType, ex.getMessage(), ex);
            return SbmCallResult.builder()
                    .success(false)
                    .httpStatus(0)
                    .requestPayload(requestPayload)
                    .errorCode(SbmErrorCode.CORE_00000.getCode())
                    .errorMessage("SBM servisine erişilemedi: " + ex.getMessage())
                    .build();
        }
    }


    private void applyAuthHeaders(HttpHeaders headers, TokenResponse token) {
        headers.setBearerAuth(token.getAccessToken());
        if (token.getClientCredentials().getClientIdentityType() != null) {
            headers.set(REQUESTER_ID_TYPE_HEADER,
                    String.valueOf(token.getClientCredentials().getClientIdentityType()));
        }
        headers.set(REQUESTER_ID_NO_HEADER, token.getClientCredentials().getClientIdNumber());
    }

    private SbmCallResult toResult(ClientHttpResponse response,
                                   String requestPayload,
                                   OperationType operationType) throws IOException {
        int httpStatus = response.getStatusCode().value();
        String transactionId = response.getHeaders().getFirst(TRANSACTION_ID_HEADER);
        String responsePayload = readBody(response);
        SbmDeclarationResponse parsed = jsonUtil.fromJson(responsePayload, SbmDeclarationResponse.class);

        boolean success = httpStatus >= 200 && httpStatus < 300
                && parsed != null && Boolean.TRUE.equals(parsed.getResult());

        // SBM her destek talebinde Transaction-Id istiyor; bu yüzden her zaman loglanır.
        if (success) {
            log.info("SBM {} call succeeded: httpStatus={}, transactionId={}",
                    operationType, httpStatus, transactionId);
        } else {
            log.error("SBM {} call failed: httpStatus={}, transactionId={}, body={}",
                    operationType, httpStatus, transactionId, responsePayload);
        }

        return SbmCallResult.builder()
                .success(success)
                .httpStatus(httpStatus)
                .transactionId(transactionId)
                .requestPayload(requestPayload)
                .responsePayload(responsePayload)
                .ysvDosyaNo(success && parsed != null ? parsed.extractYsvDosyaNo() : null)
                .errorCode(success ? null : firstErrorCode(parsed))
                .errorMessage(success ? null : buildErrorMessage(parsed, httpStatus))
                .build();
    }

    private static String readBody(ClientHttpResponse response) throws IOException {
        byte[] bytes = response.getBody().readAllBytes();
        return bytes.length == 0 ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static String firstErrorCode(SbmDeclarationResponse parsed) {
        List<SbmErrorReason> reasons = reasons(parsed);
        return reasons.isEmpty() ? SbmErrorCode.CORE_00000.getCode() : reasons.get(0).getCode();
    }


    private static String buildErrorMessage(SbmDeclarationResponse parsed, int httpStatus) {
        List<SbmErrorReason> reasons = reasons(parsed);
        if (reasons.isEmpty()) {
            return "SBM isteği reddetti (HTTP " + httpStatus + "). "
                    + SbmErrorCode.CORE_00000.getDescription();
        }
        return reasons.stream()
                .map(SbmClientService::describeReason)
                .collect(Collectors.joining(" | "));
    }

    private static String describeReason(SbmErrorReason reason) {
        StringBuilder text = new StringBuilder();
        text.append(reason.getCode() == null ? SbmErrorCode.UNKNOWN.getCode() : reason.getCode());
        if (reason.getField() != null) {
            text.append(" [").append(reason.getField()).append(']');
        }
        text.append(": ");
        text.append(reason.getMessage() == null
                ? SbmErrorCode.describe(reason.getCode())
                : reason.getMessage());
        if (reason.getRejectedValue() != null) {
            text.append(" (gönderilen değer: ").append(reason.getRejectedValue()).append(')');
        }
        return text.toString();
    }

    private static List<SbmErrorReason> reasons(SbmDeclarationResponse parsed) {
        if (parsed == null) {
            return List.of();
        }
        SbmError error = parsed.getError();
        if (error == null || error.getReasons() == null) {
            return List.of();
        }
        return error.getReasons();
    }
}
