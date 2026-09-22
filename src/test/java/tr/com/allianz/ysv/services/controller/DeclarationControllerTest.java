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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tr.com.allianz.ysv.services.dto.internal.SbmQueryData;
import tr.com.allianz.ysv.services.dto.internal.SbmQueryResponse;
import tr.com.allianz.ysv.services.dto.request.DeclarationFilterRequest;
import tr.com.allianz.ysv.services.dto.request.RequestContext;
import tr.com.allianz.ysv.services.dto.response.BatchOperationResponse;
import tr.com.allianz.ysv.services.dto.response.DeclarationUpdateResponse;
import tr.com.allianz.ysv.services.dto.response.FailureDetail;
import tr.com.allianz.ysv.services.dto.response.PageResponse;
import tr.com.allianz.ysv.services.dto.response.ProcessView;
import tr.com.allianz.ysv.services.enums.ProcessStatus;
import tr.com.allianz.ysv.services.enums.SbmErrorCode;
import tr.com.allianz.ysv.services.exception.DeclarationNotFoundException;
import tr.com.allianz.ysv.services.exception.SbmIntegrationException;
import tr.com.allianz.ysv.services.exception.TokenException;
import tr.com.allianz.ysv.services.service.DeclarationService;

@WebMvcTest(controllers = DeclarationController.class)
class DeclarationControllerTest {

    private static final String BASE = "/api/v1/declarations";
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

    private static BatchOperationResponse ok(int groups) {
        return BatchOperationResponse.of(groups, List.of());
    }

    // --- toplu -----------------------------------------------------------------------------

    @Test
    @DisplayName("POST /send returns the batch result and forwards the triggering user")
    void send_returnsBatchResult() throws Exception {
        when(declarationService.send(any(), eq(USER_ONLY)))
                .thenReturn(BatchOperationResponse.of(2, List.of(
                        new FailureDetail("YSV1", "CORE-01004", "değer aralık dışında"))));

        mockMvc.perform(post(BASE + "/send").header("X-User-Name", "WDA2422")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"year\":2026,\"month\":8,\"ysvDosyaNoList\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalGroups").value(2))
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failures[0].errorCode").value("CORE-01004"));
    }

    @Test
    @DisplayName("without any header the operation runs as SYSTEM and the company VKN is used")
    void send_withoutHeaders_runsAsSystem() throws Exception {
        when(declarationService.send(any(), any())).thenReturn(ok(0));

        mockMvc.perform(post(BASE + "/send").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        verify(declarationService).send(any(DeclarationFilterRequest.class), eq(RequestContext.system()));
    }

    @Test
    @DisplayName("the requester headers reach the service as the requester identity")
    void send_forwardsTheRequesterIdentity() throws Exception {
        when(declarationService.send(any(), any())).thenReturn(ok(0));

        mockMvc.perform(post(BASE + "/send").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("X-User-Name", "muhammed.ogur")
                        .header("X-Requester-Id-Type", "1")
                        .header("X-Requester-Id-No", "12345678901"))
                .andExpect(status().isOk());

        verify(declarationService).send(any(),
                eq(RequestContext.of("muhammed.ogur", "1", "12345678901")));
    }

    @Test
    @DisplayName("a malformed requester identity is refused before any work starts")
    void send_invalidRequester_returns400() throws Exception {
        mockMvc.perform(post(BASE + "/send").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("X-Requester-Id-Type", "3")
                        .header("X-Requester-Id-No", "12345678901"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALZ-VALIDATION"));

        verifyNoInteractions(declarationService);
    }

    @Test
    void send_requesterTypeWithoutNumber_returns400() throws Exception {
        mockMvc.perform(post(BASE + "/send").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("X-Requester-Id-Type", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("birlikte")));
    }

    @Test
    void update_delegatesToTheBulkUpdate() throws Exception {
        when(declarationService.update(any(), any())).thenReturn(ok(1));

        mockMvc.perform(put(BASE + "/update").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ysvDosyaNoList\":[\"YSV1\",\"YSV2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalGroups").value(1));

        verify(declarationService).update(
                eq(new DeclarationFilterRequest(null, null, null, List.of("YSV1", "YSV2"))), any());
    }

    @Test
    void queryBatch_delegatesToTheBulkQuery() throws Exception {
        when(declarationService.queryBatch(any(), any())).thenReturn(ok(3));

        mockMvc.perform(post(BASE + "/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"year\":2026,\"month\":8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalGroups").value(3));
    }

    @Test
    void cancel_delegatesToTheBulkCancel() throws Exception {
        when(declarationService.cancel(any(), any())).thenReturn(ok(1));

        mockMvc.perform(post(BASE + "/cancel").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        verify(declarationService).cancel(any(), any());
    }

    @Test
    @DisplayName("an out of range month is rejected before any SBM work starts")
    void send_invalidMonth_returns400() throws Exception {
        mockMvc.perform(post(BASE + "/send").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"month\":13}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALZ-VALIDATION"));
    }

    @Test
    void send_blankFileNoInList_returns400() throws Exception {
        mockMvc.perform(post(BASE + "/send").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ysvDosyaNoList\":[\" \"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALZ-VALIDATION"));
    }

    // --- tekli -----------------------------------------------------------------------------

    @Test
    void sendOne_delegatesWithThePathFileNo() throws Exception {
        when(declarationService.sendOne(eq("PENTEST260801"), any())).thenReturn(ok(1));

        mockMvc.perform(post(BASE + "/PENTEST260801/send"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1));
    }

    @Test
    void cancelOne_delegatesWithThePathFileNo() throws Exception {
        when(declarationService.cancelOne(eq("PENTEST260801"), any())).thenReturn(ok(1));

        mockMvc.perform(post(BASE + "/PENTEST260801/cancel"))
                .andExpect(status().isOk());
    }

    @Test
    void sendOne_unknownFileNo_returns404() throws Exception {
        when(declarationService.sendOne(eq("YOK"), any()))
                .thenThrow(new DeclarationNotFoundException("Beyanname bulunamadı: YOK"));

        mockMvc.perform(post(BASE + "/YOK/send"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ALZ-NOT-FOUND"));
    }

    @Test
    @DisplayName("a file number longer than SBM's 36 characters is refused")
    void sendOne_tooLongFileNo_returns400() throws Exception {
        mockMvc.perform(post(BASE + "/" + "X".repeat(37) + "/send"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALZ-VALIDATION"));

        verifyNoInteractions(declarationService);
    }

    @Test
    @DisplayName("PUT /{ysvDosyaNo} writes the new amounts and reports what happened at SBM")
    void updateOne_returnsTheOutcome() throws Exception {
        when(declarationService.updateOne(eq("PENTEST260801"), any(), any()))
                .thenReturn(new DeclarationUpdateResponse("PENTEST260801", true, true, null, "ok",
                        List.of(new ProcessView(1L, 2026, 8, 34, 0, "PENTEST260801", "MENKUL", "SENT",
                                null, null, null, null, null, 10, null, null))));

        mockMvc.perform(put(BASE + "/PENTEST260801").contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentToSbm").value(true))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.rows[0].status").value("SENT"));
    }

    @Test
    void updateOne_withoutAmounts_returns400() throws Exception {
        mockMvc.perform(put(BASE + "/PENTEST260801").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ysvTutarList\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALZ-VALIDATION"));
    }

    @Test
    @DisplayName("an unknown menkulTipi value is a 400, not a 500")
    void updateOne_unknownMovableType_returns400() throws Exception {
        mockMvc.perform(put(BASE + "/PENTEST260801").contentType(MediaType.APPLICATION_JSON)
                        .content(UPDATE_BODY.replace("\"MENKUL\"", "\"ARSA\"")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void query_returnsTheSbmAnswer() throws Exception {
        when(declarationService.query(eq("YSV202513491"), any()))
                .thenReturn(SbmQueryResponse.builder().result(true).status(200)
                        .data(SbmQueryData.builder().ysvDosyaNo("YSV202513491").build()).build());

        mockMvc.perform(get(BASE + "/YSV202513491"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(true))
                .andExpect(jsonPath("$.data.ysvDosyaNo").value("YSV202513491"));
    }

    @Test
    void processes_returnsAPagedList() throws Exception {
        when(declarationService.search(eq(ProcessStatus.NEW), eq(2026), eq(8), eq(34), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0));

        mockMvc.perform(get(BASE + "/processes")
                        .param("status", "NEW").param("year", "2026").param("month", "8").param("cityCode", "34"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(50));
    }

    // --- hata karsiliklari ------------------------------------------------------------------

    @Test
    @DisplayName("an SBM failure is rendered as a 502 with SBM's own code")
    void query_sbmFailure_returns502() throws Exception {
        when(declarationService.query(any(), any()))
                .thenThrow(new SbmIntegrationException(SbmErrorCode.CORE_01001.getCode(), "Kayıt bulunamadı."));

        mockMvc.perform(get(BASE + "/YSV202513491"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("CORE-01001"));
    }

    @Test
    void query_tokenFailure_returns503() throws Exception {
        when(declarationService.query(any(), any())).thenThrow(new TokenException("Token servisine erişilemedi."));

        mockMvc.perform(get(BASE + "/YSV202513491"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SEC-00001"));
    }

    @Test
    @DisplayName("an unexpected failure is a 500 that never shows the exception message")
    void unexpectedFailure_returns500WithoutDetails() throws Exception {
        when(declarationService.query(any(), any()))
                .thenThrow(new IllegalStateException("ORA-00942 CUSTOMER.ALZ_SBM_DECL_PROCESS"));

        mockMvc.perform(get(BASE + "/YSV202513491"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("ALZ-INTERNAL"))
                .andExpect(jsonPath("$.details").isEmpty())
                .andExpect(jsonPath("$.message").value("Beklenmeyen bir hata oluştu."));
    }

    @Test
    @DisplayName("malformed JSON is a 400, not a 500, and the parser message is not echoed")
    void malformedJson_returns400() throws Exception {
        mockMvc.perform(post(BASE + "/send").contentType(MediaType.APPLICATION_JSON).content("{\"year\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALZ-REQUEST"))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    @Test
    void unsupportedMethod_returns405() throws Exception {
        mockMvc.perform(delete(BASE + "/send"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("ALZ-REQUEST"));
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
                .andExpect(jsonPath("$.code").value("ALZ-VALIDATION"));
    }
}
