package tr.com.allianz.ysv.services.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
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
import tr.com.allianz.ysv.services.dto.internal.ClientCredentials;
import tr.com.allianz.ysv.services.dto.internal.SbmCallResult;
import tr.com.allianz.ysv.services.dto.internal.SbmDeclarationRequest;
import tr.com.allianz.ysv.services.dto.internal.SbmDeclarationResponse;
import tr.com.allianz.ysv.services.dto.internal.SbmError;
import tr.com.allianz.ysv.services.dto.internal.SbmErrorReason;
import tr.com.allianz.ysv.services.dto.internal.SbmQueryRequest;
import tr.com.allianz.ysv.services.dto.internal.TokenResponse;
import tr.com.allianz.ysv.services.dto.request.RequestContext;
import tr.com.allianz.ysv.services.enums.OperationType;
import tr.com.allianz.ysv.services.enums.SbmErrorCode;
import tr.com.allianz.ysv.services.exception.TokenException;
import tr.com.allianz.ysv.services.util.JsonUtil;
import tr.com.allianz.ysv.services.util.MaskUtil;

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
    public SbmCallResult send(SbmDeclarationRequest request, RequestContext context) {
        return callWithRetry(HttpMethod.POST, esbProperties.beyannameUrl(), request, OperationType.POST, context);
    }

    /** Beyanname güncelleme (iptal akışı da bunu kullanır): {@code ysv-beyanname} üzerinde HTTP PUT. */
    public SbmCallResult update(SbmDeclarationRequest request, RequestContext context) {
        return callWithRetry(HttpMethod.PUT, esbProperties.beyannameUrl(), request, OperationType.PUT, context);
    }


    public SbmCallResult query(SbmQueryRequest request, RequestContext context) {
        String url = UriComponentsBuilder.fromUriString(esbProperties.sorguUrl())
                .queryParam("sigortaSirketKodu", request.getSigortaSirketKodu())
                .queryParam("ysvDosyaNo", request.getYsvDosyaNo())
                .toUriString();
        return callWithRetry(HttpMethod.GET, url, request, OperationType.GET, context);
    }

    /**
     * Bir mantıksal çağrı için tek {@code Transaction-Id} üretilir ve tekrar denemelerde de
     * aynısı kullanılır: SBM Entegrasyon Dokümanı §5.2'ye göre aynı işleme ait çağrılar aynı
     * numarayla gruplanır. Aynı değer token isteğinin {@code transactionId}'si olarak da
     * gider, böylece token servisi logu, uygulama logu, DB logu ve SBM tek numarayla izlenir.
     */
    private SbmCallResult callWithRetry(HttpMethod method, String url, Object body,
                                        OperationType operationType, RequestContext context) {
        String transactionId = UUID.randomUUID().toString();
        int maxAttempts = Math.max(1, sbmProperties.getRetry().getMaxAttempts());
        for (int attempt = 1; ; attempt++) {
            SbmCallResult result = call(method, url, body, operationType, context, transactionId);
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

    private SbmCallResult call(HttpMethod method, String url, Object body, OperationType operationType,
                               RequestContext context, String transactionId) {
        String requestPayload = body == null ? null : jsonUtil.toJson(body);
        TokenResponse token = tokenManagementService.generateToken(operationType, context, transactionId);
        ClientCredentials credentials = token.getClientCredentials();
        String requesterIdType = credentials.getClientIdentityType();
        String maskedRequesterNo = MaskUtil.maskIdentity(requesterIdType, credentials.getClientIdentityNo());
        // ESB yönlendirmesini izlemek için: her SBM çağrısının gittiği tam URL loglanır.
        log.info("SBM {} call -> {} {} (transactionId={}, requester={}/{})",
                operationType, method, url, transactionId, requesterIdType, maskedRequesterNo);
        try {
            RestClient.RequestBodySpec spec = esbRestClient.method(method)
                    .uri(url)
                    .headers(headers -> applyHeaders(headers, token, transactionId));
            // GET'te gövde gönderilmez; sorgu parametreleri URL'de query string olarak taşınır.
            if (body != null && method != HttpMethod.GET) {
                spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return spec.exchange((request, response) -> toResult(response, requestPayload, operationType,
                    transactionId, requesterIdType, maskedRequesterNo));
        } catch (Exception ex) {
            // Ayrıntı (adres, istisna) yalnızca uygulama loguna; API cevabına ve ERROR_DETAILS'e
            // iç ağ adresi yazılmaz. Transaction-Id ile log bulunur.
            log.error("SBM {} call could not be completed (transactionId={}): {}",
                    operationType, transactionId, ex.getMessage(), ex);
            return SbmCallResult.builder()
                    .success(false)
                    .httpStatus(0)
                    .transactionId(transactionId)
                    .requesterIdType(requesterIdType)
                    .requesterIdNo(maskedRequesterNo)
                    .requestPayload(requestPayload)
                    .errorCode(SbmErrorCode.CORE_00000.getCode())
                    .errorMessage("SBM servisine erişilemedi (ESB bağlantı hatası). Transaction-Id: " + transactionId)
                    .build();
        }
    }


    /**
     * Kimlik başlıkları token cevabından gelir (token dokümanına göre birincil yol); ikisi de
     * {@link TokenManagementService} tarafından doğrulanmıştır, burada boş olamazlar.
     */
    private void applyHeaders(HttpHeaders headers, TokenResponse token, String transactionId) {
        headers.setBearerAuth(token.getAccessToken());
        headers.set(REQUESTER_ID_TYPE_HEADER, token.getClientCredentials().getClientIdentityType());
        headers.set(REQUESTER_ID_NO_HEADER, token.getClientCredentials().getClientIdentityNo());
        headers.set(TRANSACTION_ID_HEADER, transactionId);
    }

    private SbmCallResult toResult(ClientHttpResponse response,
                                   String requestPayload,
                                   OperationType operationType,
                                   String sentTransactionId,
                                   String requesterIdType,
                                   String maskedRequesterNo) throws IOException {
        int httpStatus = response.getStatusCode().value();
        // SBM gönderdiğimiz Transaction-Id'yi geri döner; cevap SBM'den değil ESB'den geldiyse
        // (ör. 404 HTML) başlık olmaz, o zaman bizim ürettiğimiz kullanılır.
        String returned = response.getHeaders().getFirst(TRANSACTION_ID_HEADER);
        String transactionId = returned != null ? returned : sentTransactionId;
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
                .requesterIdType(requesterIdType)
                .requesterIdNo(maskedRequesterNo)
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
