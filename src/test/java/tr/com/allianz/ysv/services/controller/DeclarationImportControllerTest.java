package tr.com.allianz.ysv.services.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import tr.com.allianz.ysv.services.dto.response.ExcelRowError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import tr.com.allianz.ysv.services.dto.response.ImportResultResponse;
import tr.com.allianz.ysv.services.service.DeclarationImportService;

@WebMvcTest(controllers = DeclarationImportController.class)
class DeclarationImportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeclarationImportService declarationImportService;

    private static MockMultipartFile xlsx(String name) {
        return new MockMultipartFile("file", name,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "data".getBytes());
    }

    @Test
    @DisplayName("geçerli .xlsx yüklenir, X-User-Name CREATED_BY_USER'a geçer")
    void upload_returnsImportResult() throws Exception {
        when(declarationImportService.importFile(any(), eq("WDA2422")))
                .thenReturn(ImportResultResponse.of("b.xlsx", 3, 2, 0, List.of("YSV-1"), List.of(), List.of()));

        mockMvc.perform(multipart("/api/v1/declarations/upload")
                        .file(xlsx("b.xlsx"))
                        .header("X-User-Name", "WDA2422"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(true))
                .andExpect(jsonPath("$.data.inserted").value(2))
                .andExpect(jsonPath("$.data.totalRows").value(3));

        verify(declarationImportService).importFile(any(), eq("WDA2422"));
    }

    @Test
    void upload_withoutUserHeader_defaultsToSystem() throws Exception {
        when(declarationImportService.importFile(any(), eq("SYSTEM")))
                .thenReturn(ImportResultResponse.of("b.xlsx", 0, 0, 0, List.of(), List.of(), List.of()));

        mockMvc.perform(multipart("/api/v1/declarations/upload").file(xlsx("b.xlsx")))
                .andExpect(status().isOk());

        verify(declarationImportService).importFile(any(), eq("SYSTEM"));
    }

    @Test
    void upload_nonXlsx_returns400() throws Exception {
        mockMvc.perform(multipart("/api/v1/declarations/upload").file(xlsx("beyanname.csv")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.reasons[0].code").value("ALZ-VALIDATION"));
    }

    @Test
    void upload_emptyFile_returns400() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "b.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[0]);

        mockMvc.perform(multipart("/api/v1/declarations/upload").file(empty))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upload_fileWithoutName_returns400() throws Exception {
        MockMultipartFile noName = new MockMultipartFile("file", null,
                "application/octet-stream", "data".getBytes());

        mockMvc.perform(multipart("/api/v1/declarations/upload").file(noName))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.reasons[0].code").value("ALZ-VALIDATION"));
    }

    @Test
    @DisplayName("a request without the file part is a 400, not a 500")
    void upload_withoutFilePart_returns400() throws Exception {
        mockMvc.perform(multipart("/api/v1/declarations/upload"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upload_invalidUserHeader_returns400() throws Exception {
        mockMvc.perform(multipart("/api/v1/declarations/upload").file(xlsx("b.xlsx"))
                        .header("X-User-Name", "x".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.reasons[0].code").value("ALZ-VALIDATION"));
    }
    // --- validate ------------------------------------------------------------------------

    @Test
    @DisplayName("validate: aynı sonucu döner ama importFile'ı hiç çağırmaz")
    void validate_returnsPlanWithoutImporting() throws Exception {
        when(declarationImportService.validate(any()))
                .thenReturn(ImportResultResponse.of("b.xlsx", 2, 2, 0, List.of("YSV-1"), List.of(), List.of()));

        mockMvc.perform(multipart("/api/v1/declarations/upload/validate").file(xlsx("b.xlsx")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(true))
                .andExpect(jsonPath("$.data.inserted").value(2))
                .andExpect(jsonPath("$.data.insertedFileNos[0]").value("YSV-1"));

        verify(declarationImportService, never()).importFile(any(), any());
    }

    @Test
    void validate_withRowErrors_resultIsFalse() throws Exception {
        when(declarationImportService.validate(any())).thenReturn(ImportResultResponse.of("b.xlsx", 1, 0, 0,
                List.of(), List.of(), List.of(new ExcelRowError(2, "YSV-1", "ALZ-EXCEL-CONFLICT", "x"))));

        mockMvc.perform(multipart("/api/v1/declarations/upload/validate").file(xlsx("b.xlsx")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(false))
                .andExpect(jsonPath("$.data.failed").value(1));
    }

    @Test
    void validate_nonXlsx_returns400() throws Exception {
        mockMvc.perform(multipart("/api/v1/declarations/upload/validate").file(xlsx("beyanname.csv")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.reasons[0].code").value("ALZ-VALIDATION"));
    }
}
