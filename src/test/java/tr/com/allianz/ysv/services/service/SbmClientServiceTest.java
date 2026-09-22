package tr.com.allianz.ysv.services.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import tr.com.allianz.ysv.services.dto.request.RequestContext;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tr.com.allianz.ysv.services.config.EsbProperties;
import tr.com.allianz.ysv.services.config.SbmProperties;
import tr.com.allianz.ysv.services.dto.internal.ClientCredentials;
import tr.com.allianz.ysv.services.dto.internal.SbmAmountItem;
import tr.com.allianz.ysv.services.dto.internal.SbmCallResult;
import tr.com.allianz.ysv.services.dto.internal.SbmDeclarationRequest;
import tr.com.allianz.ysv.services.dto.internal.SbmQueryRequest;
import tr.com.allianz.ysv.services.dto.internal.TokenResponse;
import tr.com.allianz.ysv.services.enums.OperationType;
import tr.com.allianz.ysv.services.enums.SbmErrorCode;
import tr.com.allianz.ysv.services.exception.TokenException;
import tr.com.allianz.ysv.services.util.JsonUtil;

class SbmClientServiceTest {

    private static final String BASE_URL = "http://esb.test.local:12000";
    private static final String BEYANNAME_URL = BASE_URL + "/sbmDeclarationServices";
    private static final String SORGU_URL = BEYANNAME_URL;
    private static final String TRANSACTION_ID = "9d0e6c2e-6f2b-4c2a-9d3e-a1b2c3d4e5f6";
    private static final String ACCESS_TOKEN = "MOCK-TEST-ACCESS-TOKEN-VALUE";

    /** SBM zarfı: POST -> data.ysvDosyaNo. */
    private static final String SUCCESS_BODY =
            "{\"result\":true,\"status\":201,\"data\":{\"ysvDosyaNo\":\"YSV202513491\"}}";

    /** SBM sorgu zarfı: GET -> data = beyannamenin kendisi. */
    private static final String QUERY_SUCCESS_BODY = """
            {"result":true,"status":200,"data":{
              "sigortaSirketKodu":"045","ysvDosyaNo":"YSV202513491","ilceKodu":null,
              "ay":1,"yil":2026,"ilKodu":1,"sonOdemeTarihi":"2026-01-20",
              "ysvTutarList":[{"menkulTipi":"MENKUL","alinanPrimTutari":1,"vergiOrani":10}]
            }}
            """;

    private static final String VALIDATION_ERROR_BODY = """
            {
              "result": false,
              "status": 422,
              "error": {
                "timestamp": "2026-02-02T21:45:38.783",
                "reasons": [
                  { "field": "ilceKodu", "code": "CORE-01004",
                    "message": "ilceKodu alanının değeri 1 - 4 arasında olmalıdır.",
                    "rejectedValue": "21" }
                ]
              }
            }
            """;

    private static final String EXPIRED_TOKEN_BODY = """
            {
              "result": false,
              "status": 401,
              "error": { "timestamp": "2026-02-02T21:45:38.783",
                "reasons": [ { "code": "SEC-00002", "message": "Token geçersiz veya süresi dolmuş." } ] }
            }
            """;

    private static final RequestContext CTX = RequestContext.of("WDA2422", null, null);

    private MockRestServiceServer server;
    private TokenManagementService tokenManagementService;
    private EsbProperties esbProperties;
    private SbmProperties sbmProperties;
    private SbmClientService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        tokenManagementService = mock(TokenManagementService.class);
        when(tokenManagementService.generateToken(any(), any(), anyString())).thenReturn(TokenResponse.builder()
                .accessToken(ACCESS_TOKEN)
                .clientCredentials(ClientCredentials.builder()
                        .clientIdentityType("1")
                        .clientIdentityNo("86773997310")
                        .build())
                .build());

        esbProperties = new EsbProperties();
        esbProperties.setBaseUrl(BASE_URL);
        esbProperties.setConnectTimeout(Duration.ofSeconds(1));
        esbProperties.setReadTimeout(Duration.ofSeconds(2));

        sbmProperties = new SbmProperties();
        sbmProperties.setCompanyCode("045");
        sbmProperties.getRetry().setMaxAttempts(2);

        service = new SbmClientService(builder.build(), tokenManagementService, esbProperties,
                sbmProperties, new JsonUtil(new ObjectMapper().registerModule(new JavaTimeModule())));
    }

    // --- happy path --------------------------------------------------------------------

    @Test
    @DisplayName("POST goes to the ESB with the token headers and the Transaction-Id is captured")
    void send_success() {
        server.expect(requestTo(BEYANNAME_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andExpect(header("Requester-ID-Type", "1"))
                .andExpect(header("Requester-ID-No", "86773997310"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.ysvDosyaNo").value("YSV202513491"))
                .andExpect(jsonPath("$.sonOdemeTarihi").value("2026-01-20"))
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON)
                        .headers(transactionIdHeader()));

        SbmCallResult result = service.send(declarationRequest(), CTX);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getHttpStatus()).isEqualTo(200);
        assertThat(result.getTransactionId()).isEqualTo(TRANSACTION_ID);
        assertThat(result.getYsvDosyaNo()).isEqualTo("YSV202513491");
        assertThat(result.getErrorCode()).isNull();
        assertThat(result.getErrorMessage()).isNull();
        assertThat(result.getRequestPayload()).contains("YSV202513491");
        server.verify();
        verify(tokenManagementService).generateToken(eq(OperationType.POST), eq(CTX), anyString());
    }

    @Test
    void update_usesPut() {
        server.expect(requestTo(BEYANNAME_URL))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

        assertThat(service.update(declarationRequest(), CTX).isSuccess()).isTrue();

        server.verify();
        verify(tokenManagementService).generateToken(eq(OperationType.PUT), eq(CTX), anyString());
    }

    @Test
    @DisplayName("query is a GET carrying sigortaSirketKodu + ysvDosyaNo as query-string params")
    void query_isGetWithQueryStringParams() {
        server.expect(requestTo(SORGU_URL + "?sigortaSirketKodu=045&ysvDosyaNo=YSV202513491"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andExpect(header("Requester-ID-No", "86773997310"))
                .andRespond(withSuccess(QUERY_SUCCESS_BODY, MediaType.APPLICATION_JSON)
                        .headers(transactionIdHeader()));

        SbmCallResult result = service.query(queryRequest(), CTX);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTransactionId()).isEqualTo(TRANSACTION_ID);
        server.verify();
        verify(tokenManagementService).generateToken(eq(OperationType.GET), eq(CTX), anyString());
    }

    // --- failures ----------------------------------------------------------------------

    @Test
    @DisplayName("a 422 is reported with SBM's own code and is never retried")
    void send_validationError_isNotRetried() {
        server.expect(ExpectedCount.once(), requestTo(BEYANNAME_URL))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(VALIDATION_ERROR_BODY)
                        .headers(transactionIdHeader()));

        SbmCallResult result = service.send(declarationRequest(), CTX);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getHttpStatus()).isEqualTo(422);
        assertThat(result.getErrorCode()).isEqualTo("CORE-01004");
        assertThat(result.getErrorMessage())
                .contains("CORE-01004", "[ilceKodu]", "1 - 4 arasında", "gönderilen değer: 21");
        assertThat(result.getTransactionId()).isEqualTo(TRANSACTION_ID);
        server.verify();
    }

    @Test
    @DisplayName("SEC-00002 triggers exactly one retry with a brand new token")
    void send_expiredToken_isRetriedOnce() {
        server.expect(ExpectedCount.once(), requestTo(BEYANNAME_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(EXPIRED_TOKEN_BODY));
        server.expect(ExpectedCount.once(), requestTo(BEYANNAME_URL))
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

        SbmCallResult result = service.send(declarationRequest(), CTX);

        assertThat(result.isSuccess()).isTrue();
        server.verify();
        verify(tokenManagementService, times(2)).generateToken(eq(OperationType.POST), eq(CTX), anyString());
    }

    @Test
    @DisplayName("a 5xx is retried up to max-attempts and then reported")
    void send_serverError_isRetriedThenReported() {
        server.expect(ExpectedCount.times(2), requestTo(BEYANNAME_URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"result\":false,\"status\":500}"));

        SbmCallResult result = service.send(declarationRequest(), CTX);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getHttpStatus()).isEqualTo(500);
        assertThat(result.getErrorCode()).isEqualTo(SbmErrorCode.CORE_00000.getCode());
        assertThat(result.getErrorMessage()).contains("HTTP 500");
        server.verify();
        verify(tokenManagementService, times(2)).generateToken(eq(OperationType.POST), eq(CTX), anyString());
    }

    @Test
    @DisplayName("max-attempts = 1 disables retrying altogether")
    void send_withoutRetryConfigured_callsOnce() {
        sbmProperties.getRetry().setMaxAttempts(1);
        server.expect(ExpectedCount.once(), requestTo(BEYANNAME_URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"result\":false,\"status\":500}"));

        assertThat(service.send(declarationRequest(), CTX).isSuccess()).isFalse();

        server.verify();
        verify(tokenManagementService, times(1)).generateToken(eq(OperationType.POST), eq(CTX), anyString());
    }

    @Test
    @DisplayName("HTTP 200 with result:false is a failure, not a success")
    void send_resultFalseWithHttp200_isFailure() {
        server.expect(requestTo(BEYANNAME_URL))
                .andRespond(withSuccess("{\"result\":false,\"status\":200}", MediaType.APPLICATION_JSON));

        SbmCallResult result = service.send(declarationRequest(), CTX);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo(SbmErrorCode.CORE_00000.getCode());
    }

    @Test
    void send_emptyBody_isFailure() {
        server.expect(requestTo(BEYANNAME_URL)).andRespond(withStatus(HttpStatus.OK));

        SbmCallResult result = service.send(declarationRequest(), CTX);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getResponsePayload()).isNull();
        assertThat(result.getYsvDosyaNo()).isNull();
        assertThat(result.getErrorMessage()).contains("HTTP 200");
    }

    @Test
    @DisplayName("a reason without code, message or rejectedValue is still described")
    void send_sparseReason_isDescribed() {
        server.expect(requestTo(BEYANNAME_URL))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"result\":false,\"status\":422,\"error\":{\"reasons\":[{}]}}"));

        SbmCallResult result = service.send(declarationRequest(), CTX);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isNull();
        assertThat(result.getErrorMessage()).contains(SbmErrorCode.UNKNOWN.getCode());
    }

    @Test
    @DisplayName("an error body without reasons falls back to the generic SBM description")
    void send_errorWithoutReasons_usesFallbackMessage() {
        server.expect(requestTo(BEYANNAME_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"result\":false,\"status\":400,\"error\":{\"timestamp\":\"x\"}}"));

        SbmCallResult result = service.send(declarationRequest(), CTX);

        assertThat(result.getErrorCode()).isEqualTo(SbmErrorCode.CORE_00000.getCode());
        assertThat(result.getErrorMessage()).contains("HTTP 400");
    }

    @Test
    @DisplayName("a transport failure becomes a result with httpStatus 0 and is not retried")
    void send_transportFailure_isReportedWithoutRetry() {
        server.expect(ExpectedCount.once(), requestTo(BEYANNAME_URL))
                .andRespond(withException(new IOException("connection reset")));

        SbmCallResult result = service.send(declarationRequest(), CTX);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getHttpStatus()).isZero();
        assertThat(result.getErrorCode()).isEqualTo(SbmErrorCode.CORE_00000.getCode());
        assertThat(result.getErrorMessage()).contains("SBM servisine erişilemedi")
                .doesNotContain("connection reset");
        assertThat(result.getTransactionId()).isNotBlank();
        assertThat(result.getRequestPayload()).contains("YSV202513491");
        server.verify();
    }

    @Test
    @DisplayName("without a token nothing is sent and the failure surfaces to the caller")
    void send_tokenFailure_propagates() {
        when(tokenManagementService.generateToken(any(), any(), anyString()))
                .thenThrow(new TokenException("Token servisine erişilemedi"));

        assertThatThrownBy(() -> service.send(declarationRequest(), CTX))
                .isInstanceOf(TokenException.class);
    }

    // --- izlenebilirlik (Transaction-Id) ve kimlik ----------------------------------------

    @Test
    @DisplayName("the same Transaction-Id goes to the token service and to SBM")
    void send_sendsOneTransactionIdToTokenAndSbm() {
        org.mockito.ArgumentCaptor<String> tokenTx = org.mockito.ArgumentCaptor.forClass(String.class);
        server.expect(requestTo(BEYANNAME_URL))
                .andExpect(request -> assertThat(request.getHeaders().getFirst("Transaction-Id")).isNotBlank())
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

        SbmCallResult result = service.send(declarationRequest(), CTX);

        verify(tokenManagementService).generateToken(eq(OperationType.POST), eq(CTX), tokenTx.capture());
        assertThat(result.getTransactionId()).isEqualTo(tokenTx.getValue());
        server.verify();
    }

    @Test
    @DisplayName("a retried call keeps its Transaction-Id: SBM groups the attempts under one number")
    void send_retryKeepsTheTransactionId() {
        org.mockito.ArgumentCaptor<String> tokenTx = org.mockito.ArgumentCaptor.forClass(String.class);
        server.expect(ExpectedCount.twice(), requestTo(BEYANNAME_URL))
                .andRespond(withServerError());

        service.send(declarationRequest(), CTX);

        verify(tokenManagementService, times(2)).generateToken(eq(OperationType.POST), eq(CTX), tokenTx.capture());
        assertThat(tokenTx.getAllValues()).hasSize(2).containsOnly(tokenTx.getAllValues().get(0));
    }

    @Test
    @DisplayName("SBM's own Transaction-Id wins when it answers with one")
    void send_prefersTheReturnedTransactionId() {
        server.expect(requestTo(BEYANNAME_URL))
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON).headers(transactionIdHeader()));

        assertThat(service.send(declarationRequest(), CTX).getTransactionId()).isEqualTo(TRANSACTION_ID);
    }

    @Test
    @DisplayName("the requester SBM sees comes from the token and is reported masked")
    void send_reportsTheMaskedRequester() {
        server.expect(requestTo(BEYANNAME_URL))
                .andExpect(header("Requester-ID-Type", "1"))
                .andExpect(header("Requester-ID-No", "86773997310"))
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

        SbmCallResult result = service.send(declarationRequest(), CTX);

        assertThat(result.getRequesterIdType()).isEqualTo("1");
        assertThat(result.getRequesterIdNo()).isEqualTo("86*******10");
    }

    // --- retry decision ------------------------------------------------------------------

    @Test
    void isRetryable_coversTheTwoDocumentedTriggers() {
        assertThat(SbmClientService.isRetryable(resultWith(500, null))).isTrue();
        assertThat(SbmClientService.isRetryable(resultWith(503, "CORE-00000"))).isTrue();
        assertThat(SbmClientService.isRetryable(resultWith(401, "SEC-00002"))).isTrue();
        assertThat(SbmClientService.isRetryable(resultWith(422, "CORE-01004"))).isFalse();
        assertThat(SbmClientService.isRetryable(resultWith(403, "SEC-00003"))).isFalse();
        assertThat(SbmClientService.isRetryable(resultWith(0, null))).isFalse();
    }

    private static SbmCallResult resultWith(int httpStatus, String errorCode) {
        return SbmCallResult.builder().success(false).httpStatus(httpStatus).errorCode(errorCode).build();
    }

    private static HttpHeaders transactionIdHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(SbmClientService.TRANSACTION_ID_HEADER, TRANSACTION_ID);
        return headers;
    }

    private static SbmDeclarationRequest declarationRequest() {
        return SbmDeclarationRequest.builder()
                .ay(1)
                .yil(2026)
                .ilKodu(1)
                .sigortaSirketKodu("045")
                .sonOdemeTarihi(LocalDate.of(2026, 1, 20))
                .ysvDosyaNo("YSV202513491")
                .ysvTutarList(List.of(SbmAmountItem.builder()
                        .menkulTipi("MENKUL")
                        .vergiOrani(10)
                        .build()))
                .build();
    }

    private static SbmQueryRequest queryRequest() {
        return SbmQueryRequest.builder().sigortaSirketKodu("045").ysvDosyaNo("YSV202513491").build();
    }
}
