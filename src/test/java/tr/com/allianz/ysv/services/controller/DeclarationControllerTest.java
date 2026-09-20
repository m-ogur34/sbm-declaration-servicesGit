package tr.com.allianz.ysv.services.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tr.com.allianz.ysv.services.dto.internal.SbmQueryData;
import tr.com.allianz.ysv.services.dto.internal.SbmQueryResponse;
import tr.com.allianz.ysv.services.dto.request.DeclarationFilterRequest;
import tr.com.allianz.ysv.services.dto.response.BatchOperationResponse;
import tr.com.allianz.ysv.services.dto.response.FailureDetail;
import tr.com.allianz.ysv.services.dto.response.PageResponse;
import tr.com.allianz.ysv.services.dto.response.ProcessView;
import tr.com.allianz.ysv.services.enums.ProcessStatus;
import tr.com.allianz.ysv.services.enums.SbmErrorCode;
import tr.com.allianz.ysv.services.exception.SbmIntegrationException;
import tr.com.allianz.ysv.services.exception.TokenException;
import tr.com.allianz.ysv.services.service.DeclarationService;

@WebMvcTest(controllers = DeclarationController.class)
class DeclarationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeclarationService declarationService;

    @Test
    @DisplayName("POST /send returns the batch result and forwards the triggering user")
    void send_returnsBatchResult() throws Exception {
        when(declarationService.send(any(), eq("WDA2422")))
                .thenReturn(BatchOperationResponse.of(2, List.of(
                        new FailureDetail("YSV1", "CORE-01004", "değer aralık dışında"))));

        mockMvc.perform(post("/api/v1/declarations/send")
                        .header("X-User-Name", "WDA2422")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"year\":2026,\"month\":1,\"processIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalGroups").value(2))
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failCount").value(1))
                .andExpect(jsonPath("$.failures[0].errorCode").value("CORE-01004"));
    }

    @Test
    @DisplayName("without X-User-Name the operation is attributed to SYSTEM")
    void send_defaultsTheUserToSystem() throws Exception {
        when(declarationService.send(any(), any())).thenReturn(BatchOperationResponse.of(0, List.of()));

        mockMvc.perform(post("/api/v1/declarations/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        verify(declarationService).send(any(DeclarationFilterRequest.class), eq("SYSTEM"));
    }

    @Test
    void update_delegatesToTheUpdateFlow() throws Exception {
        when(declarationService.update(any(), any())).thenReturn(BatchOperationResponse.of(1, List.of()));

        mockMvc.perform(put("/api/v1/declarations/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"year\":2026,\"month\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1));

        verify(declarationService).update(any(), eq("SYSTEM"));
    }

    @Test
    void cancel_delegatesToTheCancelFlow() throws Exception {
        when(declarationService.cancel(any(), any())).thenReturn(BatchOperationResponse.of(1, List.of()));

        mockMvc.perform(post("/api/v1/declarations/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processIds\":[1,2]}"))
                .andExpect(status().isOk());

        verify(declarationService).cancel(any(), eq("SYSTEM"));
    }

    @Test
    void query_returnsTheSbmAnswer() throws Exception {
        SbmQueryResponse response = SbmQueryResponse.builder()
                .result(true)
                .status(200)
                .data(SbmQueryData.builder().ysvDosyaNo("YSV202513491").ay(1).yil(2026).build())
                .build();
        when(declarationService.query(eq("YSV202513491"), any())).thenReturn(response);

        mockMvc.perform(get("/api/v1/declarations/query/YSV202513491"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(true))
                .andExpect(jsonPath("$.data.ysvDosyaNo").value("YSV202513491"));
    }

    @Test
    void processes_returnsAPagedList() throws Exception {
        when(declarationService.search(eq(ProcessStatus.ERROR), eq(2026), eq(1), eq(34), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(new ProcessView(1L, 2026, 1, 34, 0,
                        "YSV202513491", "MENKUL", "ERROR", null, null, null, null, null, 10, null,
                        "CORE-01004")), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/declarations/processes")
                        .param("status", "ERROR")
                        .param("year", "2026")
                        .param("month", "1")
                        .param("cityCode", "34")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sbmFileNo").value("YSV202513491"))
                .andExpect(jsonPath("$.content[0].status").value("ERROR"));
    }

    @Test
    @DisplayName("an out of range month is rejected before any SBM work starts")
    void send_invalidMonth_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/declarations/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"year\":2026,\"month\":13}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALZ-VALIDATION"))
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("month")));
    }

    @Test
    @DisplayName("an SBM failure is rendered as a 502 with SBM's own code")
    void query_sbmFailure_returns502() throws Exception {
        when(declarationService.query(any(), any())).thenThrow(
                new SbmIntegrationException(SbmErrorCode.CORE_01001.getCode(), "Kayıt bulunamadı."));

        mockMvc.perform(get("/api/v1/declarations/query/YSV202513491"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("CORE-01001"))
                .andExpect(jsonPath("$.message").value("Kayıt bulunamadı."))
                .andExpect(jsonPath("$.path").value("/api/v1/declarations/query/YSV202513491"));
    }

    @Test
    void query_tokenFailure_returns503() throws Exception {
        when(declarationService.query(any(), any()))
                .thenThrow(new TokenException("Token servisine erişilemedi"));

        mockMvc.perform(get("/api/v1/declarations/query/YSV202513491"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SEC-00001"));
    }

    @Test
    void unexpectedFailure_returns500() throws Exception {
        when(declarationService.query(any(), any())).thenThrow(new IllegalStateException("boom"));

        mockMvc.perform(get("/api/v1/declarations/query/YSV202513491"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("ALZ-INTERNAL"));
    }
}
