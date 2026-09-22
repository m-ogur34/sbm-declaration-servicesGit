package tr.com.allianz.ysv.services.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tr.com.allianz.ysv.services.config.TokenManagementProperties;
import tr.com.allianz.ysv.services.dto.internal.TokenResponse;
import tr.com.allianz.ysv.services.dto.request.RequestContext;
import tr.com.allianz.ysv.services.enums.OperationType;
import tr.com.allianz.ysv.services.exception.TokenException;

class TokenManagementServiceTest {

    private static final String TOKEN_URL =
            "http://token.test.local/alz-token-management/api/v1/tokens/sbm-token-generate";

    private static final String ACCESS_TOKEN = "MOCK-TEST-ACCESS-TOKEN-VALUE";

    private static final String SUCCESS_BODY = """
            {
              "accessToken": "%s",
              "clientCredentials": { "clientIdentityType": 1, "clientIdNumber": "86773997310" }
            }
            """.formatted(ACCESS_TOKEN);

    private static final String TX = "5ee4333d-9833-47bf-ab66-90359f446123";
    private static final RequestContext NO_REQUESTER = RequestContext.of("WDA2422", null, null);
    private static final RequestContext WITH_REQUESTER = RequestContext.of("WDA2422", "1", "12345678901");

    private MockRestServiceServer server;
    private TokenManagementService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        service = new TokenManagementService(builder.build(), properties());
    }

    private static TokenManagementProperties properties() {
        TokenManagementProperties properties = new TokenManagementProperties();
        properties.setBaseUrl("http://token.test.local");
        properties.setPath("/alz-token-management/api/v1/tokens/sbm-token-generate");
        properties.setClientName("ysv");
        properties.setFunctionName("test");
        properties.setCompanyCode("045");
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setReadTimeout(Duration.ofSeconds(2));
        return properties;
    }

    @Test
    @DisplayName("without a requester no identity is sent: the token service answers with the company VKN")
    void generateToken_withoutRequester_letsTheServiceUseTheCompanyTaxNumber() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.clientName").value("ysv"))
                .andExpect(jsonPath("$.companyCode").value("045"))
                .andExpect(jsonPath("$.functionName").value("test"))
                .andExpect(jsonPath("$.transactionId").value(TX))
                .andExpect(jsonPath("$.externalServiceName").value("sbm-declaration-services"))
                .andExpect(jsonPath("$.externalFunctionName").value("POST"))
                .andExpect(jsonPath("$.userName").doesNotExist())
                .andExpect(jsonPath("$.clientIdentityType").doesNotExist())
                .andExpect(jsonPath("$.clientIdentityNo").doesNotExist())
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

        TokenResponse response = service.generateToken(OperationType.POST, NO_REQUESTER, TX);

        assertThat(response.getAccessToken()).isEqualTo(ACCESS_TOKEN);
        assertThat(response.getClientCredentials().getClientIdentityType()).isEqualTo("1");
        assertThat(response.getClientCredentials().getClientIdentityNo()).isEqualTo("86773997310");
        server.verify();
    }

    @Test
    @DisplayName("functionName comes from config, not from the operation type")
    void generateToken_functionNameIsTheSameForEveryOperation() {
        TokenManagementProperties props = properties();
        props.setFunctionName("ysv-prod-fn");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer localServer = MockRestServiceServer.bindTo(builder).build();
        TokenManagementService localService = new TokenManagementService(builder.build(), props);

        localServer.expect(requestTo(TOKEN_URL))
                .andExpect(jsonPath("$.functionName").value("ysv-prod-fn"))
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));
        localServer.expect(requestTo(TOKEN_URL))
                .andExpect(jsonPath("$.functionName").value("ysv-prod-fn"))
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

        localService.generateToken(OperationType.PUT, NO_REQUESTER, TX);
        localService.generateToken(OperationType.GET, NO_REQUESTER, TX);

        localServer.verify();
    }

    @Test
    void generateToken_wrapsTransportFailures() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> service.generateToken(OperationType.GET, NO_REQUESTER, TX))
                .isInstanceOf(TokenException.class)
                .hasMessageContaining("Token servisine erişilemedi");
    }

    @Test
    @DisplayName("a body-less answer never produces a half initialised token")
    void generateToken_rejectsEmptyResponseBody() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.NO_CONTENT));

        assertThatThrownBy(() -> service.generateToken(OperationType.POST, NO_REQUESTER, TX))
                .isInstanceOf(TokenException.class);
    }

    @Test
    void generateToken_rejectsMissingAccessToken() {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.generateToken(OperationType.POST, NO_REQUESTER, TX))
                .isInstanceOf(TokenException.class)
                .hasMessageContaining("boş accessToken");
    }

    @Test
    void generateToken_rejectsBlankAccessToken() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("""
                {"accessToken": "", "clientCredentials": {"clientIdentityType": 1, "clientIdNumber": "1"}}
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.generateToken(OperationType.POST, NO_REQUESTER, TX))
                .isInstanceOf(TokenException.class)
                .hasMessageContaining("boş accessToken");
    }

    @Test
    void generateToken_rejectsMissingClientCredentials() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("""
                {"accessToken": "%s"}
                """.formatted(ACCESS_TOKEN), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.generateToken(OperationType.POST, NO_REQUESTER, TX))
                .isInstanceOf(TokenException.class)
                .hasMessageContaining("clientCredentials");
    }

    @Test
    void generateToken_rejectsBlankClientIdNumber() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("""
                {"accessToken": "%s",
                 "clientCredentials": {"clientIdentityType": "1", "clientIdNumber": "  "}}
                """.formatted(ACCESS_TOKEN), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.generateToken(OperationType.POST, NO_REQUESTER, TX))
                .isInstanceOf(TokenException.class)
                .hasMessageContaining("clientCredentials");
    }

    @Test
    @DisplayName("the requester's identity is forwarded as clientIdentityType / clientIdentityNo")
    void generateToken_forwardsTheRequester() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(jsonPath("$.clientIdentityType").value("1"))
                .andExpect(jsonPath("$.clientIdentityNo").value("12345678901"))
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

        service.generateToken(OperationType.PUT, WITH_REQUESTER, TX);

        server.verify();
    }

    @Test
    @DisplayName("the documented clientIdentityNo field name is read as well as today's clientIdNumber")
    void generateToken_readsTheDocumentedFieldName() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("""
                {"accessToken": "%s",
                 "clientCredentials": {"clientIdentityType": "2", "clientIdentityNo": "8000013270"}}
                """.formatted(ACCESS_TOKEN), MediaType.APPLICATION_JSON));

        TokenResponse response = service.generateToken(OperationType.POST, NO_REQUESTER, TX);

        assertThat(response.getClientCredentials().getClientIdentityType()).isEqualTo("2");
        assertThat(response.getClientCredentials().getClientIdentityNo()).isEqualTo("8000013270");
    }

    @Test
    @DisplayName("an answer without the identity type is rejected: SBM requires both headers")
    void generateToken_rejectsMissingIdentityType() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("""
                {"accessToken": "%s", "clientCredentials": {"clientIdNumber": "86773997310"}}
                """.formatted(ACCESS_TOKEN), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.generateToken(OperationType.POST, NO_REQUESTER, TX))
                .isInstanceOf(TokenException.class)
                .hasMessageContaining("kimlik tipi");
    }

    @Test
    @DisplayName("a transport failure message never carries the token service address")
    void generateToken_failureMessageHasNoAddress() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> service.generateToken(OperationType.GET, NO_REQUESTER, TX))
                .isInstanceOf(TokenException.class)
                .hasMessageContaining(TX)
                .hasMessageNotContaining("token.test.local");
    }
}
