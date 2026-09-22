package tr.com.allianz.ysv.services.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tr.com.allianz.ysv.services.dto.internal.SbmReply;
import tr.com.allianz.ysv.services.dto.request.DeclarationFilterRequest;
import tr.com.allianz.ysv.services.dto.request.RequestContext;
import tr.com.allianz.ysv.services.dto.response.ApiResponse;
import tr.com.allianz.ysv.services.dto.response.BatchResult;
import tr.com.allianz.ysv.services.dto.response.PageResponse;
import tr.com.allianz.ysv.services.enums.ProcessStatus;
import tr.com.allianz.ysv.services.enums.SbmErrorCode;
import tr.com.allianz.ysv.services.exception.DeclarationNotFoundException;
import tr.com.allianz.ysv.services.exception.SbmIntegrationException;
import tr.com.allianz.ysv.services.exception.TokenException;
import tr.com.allianz.ysv.services.service.DeclarationService;

@WebMvcTest(controllers = DeclarationController.class)
class DeclarationControllerTest {

    private static final String BASE = "/api/v1/declarations";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RequestContext USER_ONLY = RequestContext.of("WDA2422", null, null);
    private static final String UPDATE_BODY = """
            {"sonOdemeTarihi":"2026-09-20","ysvTutarList":[{"menkulTipi":"MENKUL",
             "alinanPrimTutari":1000.00,"iptalPrimTutari":100.00,"odenecekVergi":90.00,
             "vergiOrani":10,"vergiPrimTutari":900.00}]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeclarationService declarationService;

    private static ApiResponse<BatchResult> batch(int groups, int failed) {
        return ApiResponse.of(failed == 0, 200, new BatchResult(groups, groups - failed, failed, List.of()));
    }

    private static SbmReply sbm(int status, String body) throws Exception {
        return new SbmReply(status, JSON.readTree(body));
    }

    // --- toplu: {result, status, data} zarfi -----------------------------------------------

    @Test
    @DisplayName("POST /send returns the batch in SBM's envelope and forwards the triggering user")
    void send_returnsTheBatchEnvelope() throws Exception {
        when(declarationService.send(any(), eq(USER_ONLY))).thenReturn(batch(2, 1));

        mockMvc.perform(post(BASE + "/send").header("X-User-Name", "WDA2422")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"year\":2026,\"month\":8,\"ysvDosyaNoList\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(false))
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.totalGroups").value(2))
                .andExpect(jsonPath("$.data.successCount").value(1))
                .andExpect(jsonPath("$.data.failCount").value(1));
    }

    @Test
    @DisplayName("without any header the operation runs as SYSTEM and the company VKN is used")
    void send_withoutHeaders_runsAsSystem() throws Exception {
        when(declarationService.send(any(), any())).thenReturn(batch(0, 0));

        mockMvc.perform(post(BASE + "/send").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        verify(declarationService).send(any(DeclarationFilterRequest.class), eq(RequestContext.system()));
    }

    @Test
    @DisplayName("the requester headers reach the service as the requester identity")
    void send_forwardsTheRequesterIdentity() throws Exception {
        when(declarationService.send(any(), any())).thenReturn(batch(0, 0));

        mockMvc.perform(post(BASE + "/send").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("X-User-Name", "muhammed.ogur")
                        .header("X-Requester-Id-Type", "1")
                        .header("X-Requester-Id-No", "12345678901"))
                .andExpect(status().isOk());

        verify(declarationService).send(any(), eq(RequestContext.of("muhammed.ogur", "1", "12345678901")));
    }

    @Test
    @DisplayName("a malformed requester identity is refused in SBM's error shape before any work starts")
    void send_invalidRequester_returns400() throws Exception {
        mockMvc.perform(post(BASE + "/send").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("X-Requester-Id-Type", "3")
                        .header("X-Requester-Id-No", "12345678901"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result").value(false))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error.timestamp").exists())
                .andExpect(jsonPath("$.error.reasons[0].code").value("ALZ-VALIDATION"));

        verifyNoInteractions(declarationService);
    }

    @Test
    void send_requesterTypeWithoutNumber_returns400() throws Exception {
        mockMvc.perform(post(BASE + "/send").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("X-Requester-Id-Type", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.reasons[0].message").value(org.hamcrest.Matchers.containsString("birlikte")));
    }

    @Test
    void update_delegatesToTheBulkUpdate() throws Exception {
        when(declarationService.update(any(), any())).thenReturn(batch(1, 0));

        mockMvc.perform(put(BASE + "/update").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ysvDosyaNoList\":[\"YSV1\",\"YSV2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(true));

        verify(declarationService).update(
                eq(new DeclarationFilterRequest(null, null, null, List.of("YSV1", "YSV2"))), any());
    }

    @Test
    void queryBatch_delegatesToTheBulkQuery() throws Exception {
        when(declarationService.queryBatch(any(), any())).thenReturn(batch(3, 0));

        mockMvc.perform(post(BASE + "/query").contentType(MediaType.APPLICATION_JSON).content("{\"year\":2026,\"month\":8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalGroups").value(3));
    }

    @Test
    void cancel_delegatesToTheBulkCancel() throws Exception {
        when(declarationService.cancel(any(), any())).thenReturn(batch(1, 0));

        mockMvc.perform(post(BASE + "/cancel").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        verify(declarationService).cancel(any(), any());
    }

    @Test
    @DisplayName("an out of range month is rejected field by field in SBM's error shape")
    void send_invalidMonth_returns400() throws Exception {
        mockMvc.perform(post(BASE + "/send").contentType(MediaType.APPLICATION_JSON).content("{\"month\":13}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.reasons[0].field").value("month"))
                .andExpect(jsonPath("$.error.reasons[0].code").value("ALZ-VALIDATION"))
                .andExpect(jsonPath("$.error.reasons[0].rejectedValue").doesNotExist());
    }

    @Test
    void send_blankFileNoInList_returns400() throws Exception {
        mockMvc.perform(post(BASE + "/send").contentType(MediaType.APPLICATION_JSON).content("{\"ysvDosyaNoList\":[\" \"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.reasons[0].code").value("ALZ-VALIDATION"));
    }

    // --- tekli: SBM'nin cevabi aynen -----------------------------------------------------------

    @Test
    @DisplayName("single send returns SBM's body and SBM's HTTP status (201)")
    void sendOne_returnsSbmAnswerAsIs() throws Exception {
        String body = "{\"result\":true,\"status\":201,\"data\":{\"ysvDosyaNo\":\"PENTEST260821\"}}";
        when(declarationService.sendOne(eq("PENTEST260821"), any())).thenReturn(sbm(201, body));

        mockMvc.perform(post(BASE + "/PENTEST260821/send"))
                .andExpect(status().isCreated())
                .andExpect(content().json(body, true));
    }

    @Test
    @DisplayName("an already sent declaration is a 409, not a 200 with a failure inside")
    void sendOne_conflict_returns409() throws Exception {
        when(declarationService.sendOne(eq("PENTEST260821"), any())).thenReturn(sbm(409,
                "{\"result\":false,\"status\":409,\"error\":{\"reasons\":[{\"code\":\"ALZ-STATUS-CONFLICT\"}]}}"));

        mockMvc.perform(post(BASE + "/PENTEST260821/send"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.reasons[0].code").value("ALZ-STATUS-CONFLICT"));
    }

    @Test
    void cancelOne_returnsSbmAnswer() throws Exception {
        when(declarationService.cancelOne(eq("PENTEST260821"), any()))
                .thenReturn(sbm(200, "{\"result\":true,\"status\":200,\"data\":true}"));

        mockMvc.perform(post(BASE + "/PENTEST260821/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    void sendOne_unknownFileNo_returns404() throws Exception {
        when(declarationService.sendOne(eq("YOK"), any()))
                .thenThrow(new DeclarationNotFoundException("Beyanname bulunamadı: YOK"));

        mockMvc.perform(post(BASE + "/YOK/send"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error.reasons[0].code").value("ALZ-NOT-FOUND"));
    }

    @Test
    @DisplayName("a file number longer than SBM's 36 characters is refused")
    void sendOne_tooLongFileNo_returns400() throws Exception {
        mockMvc.perform(post(BASE + "/" + "X".repeat(37) + "/send"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.reasons[0].code").value("ALZ-VALIDATION"))
                .andExpect(jsonPath("$.error.reasons[0].field").value("ysvDosyaNo"));

        verifyNoInteractions(declarationService);
    }

    @Test
    @DisplayName("PUT /{ysvDosyaNo} returns SBM's PUT answer")
    void updateOne_returnsSbmAnswer() throws Exception {
        when(declarationService.updateOne(eq("PENTEST260821"), any(), any()))
                .thenReturn(sbm(200, "{\"result\":true,\"status\":200,\"data\":true}"));

        mockMvc.perform(put(BASE + "/PENTEST260821").contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(true))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    void updateOne_withoutAmounts_returns400() throws Exception {
        mockMvc.perform(put(BASE + "/PENTEST260821").contentType(MediaType.APPLICATION_JSON).content("{\"ysvTutarList\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.reasons[0].code").value("ALZ-VALIDATION"));
    }

    @Test
    @DisplayName("an unknown menkulTipi value is a 400, not a 500")
    void updateOne_unknownMovableType_returns400() throws Exception {
        mockMvc.perform(put(BASE + "/PENTEST260821").contentType(MediaType.APPLICATION_JSON)
                        .content(UPDATE_BODY.replace("\"MENKUL\"", "\"ARSA\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result").value(false));
    }

    @Test
    void query_returnsSbmAnswerAsIs() throws Exception {
        String body = "{\"result\":true,\"status\":200,\"data\":{\"ysvDosyaNo\":\"YSV202513491\",\"ilceKodu\":null}}";
        when(declarationService.query(eq("YSV202513491"), any())).thenReturn(sbm(200, body));

        mockMvc.perform(get(BASE + "/YSV202513491"))
                .andExpect(status().isOk())
                .andExpect(content().json(body, true));
    }

    @Test
    void processes_returnsAPagedListInTheEnvelope() throws Exception {
        when(declarationService.search(eq(ProcessStatus.NEW), eq(2026), eq(8), eq(34), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0));

        mockMvc.perform(get(BASE + "/processes")
                        .param("status", "NEW").param("year", "2026").param("month", "8").param("cityCode", "34"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(true))
                .andExpect(jsonPath("$.data.size").value(50));
    }

    // --- hata karsiliklari ------------------------------------------------------------------

    @Test
    @DisplayName("a pre-flight SBM rule violation is a 422 with SBM's own code")
    void sbmPreFlight_returns422() throws Exception {
        when(declarationService.query(any(), any()))
                .thenThrow(new SbmIntegrationException(SbmErrorCode.CORE_01008.getCode(), "en fazla 36 karakter"));

        mockMvc.perform(get(BASE + "/YSV202513491"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.reasons[0].code").value("CORE-01008"));
    }

    @Test
    void tokenFailure_returns503() throws Exception {
        when(declarationService.query(any(), any())).thenThrow(new TokenException("Token servisine erişilemedi."));

        mockMvc.perform(get(BASE + "/YSV202513491"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.reasons[0].code").value("SEC-00001"));
    }

    @Test
    @DisplayName("an unexpected failure is a 500 that never shows the exception message")
    void unexpectedFailure_returns500WithoutDetails() throws Exception {
        when(declarationService.query(any(), any()))
                .thenThrow(new IllegalStateException("ORA-00942 CUSTOMER.ALZ_SBM_DECL_PROCESS"));

        mockMvc.perform(get(BASE + "/YSV202513491"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.reasons[0].code").value("ALZ-INTERNAL"))
                .andExpect(jsonPath("$.error.reasons[0].message").value("Beklenmeyen bir hata oluştu."))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ORA-"))));
    }

    @Test
    @DisplayName("malformed JSON is a 400, not a 500, and the parser message is not echoed")
    void malformedJson_returns400() throws Exception {
        mockMvc.perform(post(BASE + "/send").contentType(MediaType.APPLICATION_JSON).content("{\"year\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.reasons[0].code").value("ALZ-REQUEST"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Jackson"))));
    }

    @Test
    void unsupportedMethod_returns405() throws Exception {
        mockMvc.perform(delete(BASE + "/send"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void unsupportedContentType_returns415() throws Exception {
        mockMvc.perform(post(BASE + "/send").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void badEnumInQueryParam_returns400() throws Exception {
        mockMvc.perform(get(BASE + "/processes").param("status", "GECERSIZ"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.reasons[0].field").value("status"));
    }
}
