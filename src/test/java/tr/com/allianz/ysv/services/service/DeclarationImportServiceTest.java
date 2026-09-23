package tr.com.allianz.ysv.services.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import tr.com.allianz.ysv.services.config.SbmProperties;
import tr.com.allianz.ysv.services.dto.response.ExcelRowError;
import tr.com.allianz.ysv.services.dto.response.ImportResultResponse;
import tr.com.allianz.ysv.services.entity.DeclarationProcess;
import tr.com.allianz.ysv.services.enums.LogLevel;
import tr.com.allianz.ysv.services.enums.MovableType;
import tr.com.allianz.ysv.services.enums.OperationType;
import tr.com.allianz.ysv.services.enums.ProcessStatus;
import tr.com.allianz.ysv.services.mapper.ProcessMapper;
import tr.com.allianz.ysv.services.repository.DeclarationProcessRepository;
import tr.com.allianz.ysv.services.service.ExcelDeclarationParser.ParsedRow;
import tr.com.allianz.ysv.services.service.ExcelDeclarationParser.ParsedSheet;
import tr.com.allianz.ysv.services.util.JsonUtil;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeclarationImportServiceTest {

    private static final String USER = "WDA2422";
    private static final LocalDate PAYMENT = LocalDate.of(2026, 9, 20);

    @Mock
    private ExcelDeclarationParser parser;
    @Mock
    private DeclarationProcessRepository repository;
    @Mock
    private DeclarationLogService declarationLogService;
    @Mock
    private ProcessMapper processMapper;

    private DeclarationImportService service;

    @BeforeEach
    void setUp() {
        SbmProperties sbmProperties = new SbmProperties();
        sbmProperties.setCompanyCode("045");
        service = new DeclarationImportService(parser, repository, sbmProperties, declarationLogService,
                processMapper, new JsonUtil(new ObjectMapper().registerModule(new JavaTimeModule())));
        when(repository.lockByPeriod(any(), any())).thenReturn(List.of());
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "beyanname.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "dummy".getBytes());
    }

    private static ParsedRow row(int rowNumber, String fileNo, MovableType type, int month, String amount) {
        BigDecimal value = new BigDecimal(amount);
        return new ParsedRow(rowNumber, month, 34, 0, 2026, fileNo, PAYMENT, type,
                value, value, value, 10, value, null);
    }

    private static ParsedRow row(int rowNumber, String fileNo, MovableType type) {
        return row(rowNumber, fileNo, type, 8, "1.00");
    }

    private static DeclarationProcess existing(long id, String fileNo, MovableType type, ProcessStatus status) {
        BigDecimal one = new BigDecimal("1.00");
        return DeclarationProcess.builder().id(id).declarationYear(2026).declarationMonth(8)
                .cityCode(34).districtCode(0).sbmFileNo(fileNo).movableType(type).paymentDate(PAYMENT)
                .receivedPremiumAmount(one).cancelledPremiumAmount(one).taxAmount(one)
                .taxPremiumAmount(one).taxRatio(10).status(status).build();
    }

    private void sheet(ParsedRow... rows) {
        when(parser.parse(any())).thenReturn(new ParsedSheet(List.of(rows), List.of()));
    }

    private List<DeclarationProcess> inserted() {
        ArgumentCaptor<List<DeclarationProcess>> captor = ArgumentCaptor.captor();
        verify(repository, times(2)).saveAll(captor.capture());
        return captor.getAllValues().get(1);
    }

    // --- ekleme -------------------------------------------------------------------------

    @Test
    @DisplayName("MENKUL + GAYRIMENKUL of a new declaration are both inserted as NEW with company code 045")
    void newRows_areInserted() {
        sheet(row(2, "YSV-1", MovableType.MENKUL), row(3, "YSV-1", MovableType.GAYRIMENKUL));

        ImportResultResponse result = service.importFile(file(), USER);

        assertThat(result.inserted()).isEqualTo(2);
        assertThat(result.updated()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.sourceFileName()).isEqualTo("beyanname.xlsx");
        assertThat(result.insertedFileNos()).containsExactly("YSV-1");
        assertThat(inserted()).hasSize(2).allSatisfy(p -> {
            assertThat(p.getCompanyCode()).isEqualTo("045");
            assertThat(p.getStatus()).isEqualTo(ProcessStatus.NEW);
            assertThat(p.getCreatedByUser()).isEqualTo(USER);
            assertThat(p.getSourceFileName()).isEqualTo("beyanname.xlsx");
        });
    }

    @Test
    @DisplayName("a file number registered in another period is refused")
    void fileNoInAnotherPeriod_isConflict() {
        sheet(row(2, "YSV-OLD", MovableType.MENKUL));
        when(repository.existsBySbmFileNo("YSV-OLD")).thenReturn(true);

        ImportResultResponse result = service.importFile(file(), USER);

        assertThat(result.inserted()).isZero();
        assertThat(result.errors()).singleElement().satisfies(e -> {
            assertThat(e.code()).isEqualTo(DeclarationImportService.CONFLICT_CODE);
            assertThat(e.message()).contains("başka bir dönemde");
        });
    }

    @Test
    @DisplayName("a new declaration for a city/district/period already held by another file number is refused")
    void slotHeldByOtherFileNoInDb_isConflict() {
        when(repository.lockByPeriod(any(), any()))
                .thenReturn(List.of(existing(1L, "PENTEST260801", MovableType.MENKUL, ProcessStatus.SENT)));
        sheet(row(2, "YSV-1", MovableType.MENKUL));

        ImportResultResponse result = service.importFile(file(), USER);

        assertThat(result.inserted()).isZero();
        assertThat(result.errors()).singleElement().satisfies(e -> {
            assertThat(e.code()).isEqualTo(DeclarationImportService.CONFLICT_CODE);
            assertThat(e.message()).contains("PENTEST260801");
        });
    }

    @Test
    @DisplayName("two file numbers for the same city/district in one file: the first wins, the second is refused")
    void slotHeldByOtherFileNoInSameFile_isConflict() {
        sheet(row(2, "YSV-1", MovableType.MENKUL), row(3, "YSV-1", MovableType.GAYRIMENKUL),
                row(4, "YSV-2", MovableType.MENKUL));

        ImportResultResponse result = service.importFile(file(), USER);

        assertThat(result.inserted()).isEqualTo(2);
        assertThat(result.errors()).singleElement().satisfies(e -> {
            assertThat(e.code()).isEqualTo(DeclarationImportService.CONFLICT_CODE);
            assertThat(e.message()).contains("YSV-1");
        });
    }

    @Test
    @DisplayName("a different district in the same city is a different slot")
    void otherDistrict_isNotASlotConflict() {
        when(repository.lockByPeriod(any(), any()))
                .thenReturn(List.of(existing(1L, "YSV-1", MovableType.MENKUL, ProcessStatus.SENT)));
        BigDecimal one = new BigDecimal("1.00");
        sheet(new ParsedRow(2, 8, 34, 1234, 2026, "YSV-2", PAYMENT, MovableType.MENKUL,
                one, one, one, 10, one, null));

        ImportResultResponse result = service.importFile(file(), USER);

        assertThat(result.inserted()).isEqualTo(1);
        assertThat(result.failed()).isZero();
    }

    @Test
    @DisplayName("the same ysvDosyaNo + menkulTipi twice in one file is reported once")
    void duplicateKeyInFile_isReported() {
        sheet(row(2, "YSV-1", MovableType.MENKUL), row(3, "YSV-1", MovableType.MENKUL));

        ImportResultResponse result = service.importFile(file(), USER);

        assertThat(result.inserted()).isEqualTo(1);
        assertThat(result.errors()).singleElement().satisfies(e -> {
            assertThat(e.code()).isEqualTo(DeclarationImportService.DUPLICATE_CODE);
            assertThat(e.rowNumber()).isEqualTo(3);
        });
    }

    @Test
    @DisplayName("a new movable type may join a declaration that has not reached SBM yet")
    void newMovableType_forUnsentDeclaration_isInserted() {
        sheet(row(3, "YSV-1", MovableType.GAYRIMENKUL));
        when(repository.lockByPeriod(2026, 8))
                .thenReturn(List.of(existing(1L, "YSV-1", MovableType.MENKUL, ProcessStatus.NEW)));

        assertThat(service.importFile(file(), USER).inserted()).isEqualTo(1);
    }

    @Test
    @DisplayName("a new movable type cannot be added to a declaration SBM already holds")
    void newMovableType_forSentDeclaration_isConflict() {
        sheet(row(3, "YSV-1", MovableType.GAYRIMENKUL));
        when(repository.lockByPeriod(2026, 8))
                .thenReturn(List.of(existing(1L, "YSV-1", MovableType.MENKUL, ProcessStatus.SENT)));

        ImportResultResponse result = service.importFile(file(), USER);

        assertThat(result.inserted()).isZero();
        assertThat(result.errors()).singleElement()
                .satisfies(e -> assertThat(e.message()).contains("yeni menkul tipi"));
    }

    @Test
    void newMovableType_withDifferentDistrict_isConflict() {
        ParsedRow row = new ParsedRow(3, 8, 34, 1105, 2026, "YSV-1", PAYMENT, MovableType.GAYRIMENKUL,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 10, BigDecimal.ONE, null);
        sheet(row);
        when(repository.lockByPeriod(2026, 8))
                .thenReturn(List.of(existing(1L, "YSV-1", MovableType.MENKUL, ProcessStatus.NEW)));

        assertThat(service.importFile(file(), USER).errors()).singleElement()
                .satisfies(e -> assertThat(e.code()).isEqualTo(DeclarationImportService.CONFLICT_CODE));
    }

    // --- guncelleme (upsert) -------------------------------------------------------------

    @Test
    @DisplayName("an existing row with new amounts is updated, audited and reported for PUT /update")
    void changedRow_isUpdated() {
        DeclarationProcess current = existing(1L, "YSV-1", MovableType.MENKUL, ProcessStatus.COMPLETED);
        when(repository.lockByPeriod(2026, 8)).thenReturn(new ArrayList<>(List.of(current)));
        sheet(row(2, "YSV-1", MovableType.MENKUL, 8, "2.00"));

        ImportResultResponse result = service.importFile(file(), USER);

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.inserted()).isZero();
        assertThat(result.updatedFileNos()).containsExactly("YSV-1");
        assertThat(current.getReceivedPremiumAmount()).isEqualByComparingTo("2.00");
        assertThat(current.getStatus()).isEqualTo(ProcessStatus.SENT);
        assertThat(current.getUpdatedByUser()).isEqualTo(USER);
        verify(declarationLogService).logCall(eq(List.of(1L)), eq(OperationType.LOCAL_UPDATE),
                eq(LogLevel.INFO), anyString(), any(), any());
    }

    @Test
    @DisplayName("an existing row with the same values is left alone")
    void unchangedRow_isNotTouched() {
        DeclarationProcess current = existing(1L, "YSV-1", MovableType.MENKUL, ProcessStatus.SENT);
        when(repository.lockByPeriod(2026, 8)).thenReturn(List.of(current));
        sheet(row(2, "YSV-1", MovableType.MENKUL));

        ImportResultResponse result = service.importFile(file(), USER);

        assertThat(result.updated()).isZero();
        assertThat(result.errors()).isEmpty();
        assertThat(current.getUpdatedByUser()).isNull();
        verify(declarationLogService, never()).logCall(anyList(), any(), any(), anyString(), any(), any());
    }

    @Test
    void changedPaymentDateOrRefund_countsAsChange() {
        DeclarationProcess current = existing(1L, "YSV-1", MovableType.MENKUL, ProcessStatus.NEW);
        when(repository.lockByPeriod(2026, 8)).thenReturn(List.of(current));
        BigDecimal one = new BigDecimal("1.00");
        sheet(new ParsedRow(2, 8, 34, 0, 2026, "YSV-1", PAYMENT, MovableType.MENKUL,
                one, one, one, 10, one, new BigDecimal("-5.00")));

        assertThat(service.importFile(file(), USER).updated()).isEqualTo(1);
        assertThat(current.getPrevMonthRefundAmount()).isEqualByComparingTo("-5.00");
        assertThat(current.getStatus()).isEqualTo(ProcessStatus.NEW);
    }

    @Test
    @DisplayName("DISTRICT_CODE null in the database and 0 in Excel mean the same city level declaration")
    void nullAndZeroDistrict_areTheSameIdentity() {
        DeclarationProcess current = existing(1L, "YSV-1", MovableType.MENKUL, ProcessStatus.SENT);
        current.setDistrictCode(null);
        when(repository.lockByPeriod(2026, 8)).thenReturn(List.of(current));
        sheet(row(2, "YSV-1", MovableType.MENKUL, 8, "3.00"));

        assertThat(service.importFile(file(), USER).updated()).isEqualTo(1);
    }

    @Test
    @DisplayName("the declaration's identity (city / district) cannot change through Excel")
    void differentCity_isConflict() {
        DeclarationProcess current = existing(1L, "YSV-1", MovableType.MENKUL, ProcessStatus.SENT);
        current.setCityCode(6);
        when(repository.lockByPeriod(2026, 8)).thenReturn(List.of(current));
        sheet(row(2, "YSV-1", MovableType.MENKUL, 8, "3.00"));

        ImportResultResponse result = service.importFile(file(), USER);

        assertThat(result.updated()).isZero();
        assertThat(result.errors()).singleElement()
                .satisfies(e -> assertThat(e.code()).isEqualTo(DeclarationImportService.CONFLICT_CODE));
    }

    @Test
    void rowBeingSent_isBusy() {
        DeclarationProcess current = existing(1L, "YSV-1", MovableType.MENKUL, ProcessStatus.PROCESSING);
        when(repository.lockByPeriod(2026, 8)).thenReturn(List.of(current));
        sheet(row(2, "YSV-1", MovableType.MENKUL, 8, "3.00"));

        assertThat(service.importFile(file(), USER).errors()).singleElement()
                .satisfies(e -> assertThat(e.code()).isEqualTo(DeclarationImportService.BUSY_CODE));
    }

    // --- dosya kurallari -------------------------------------------------------------------

    @Test
    void parserRowErrors_areCarried() {
        when(parser.parse(any())).thenReturn(new ParsedSheet(
                List.of(row(2, "YSV-OK", MovableType.MENKUL)),
                List.of(new ExcelRowError(3, "YSV-BAD", "ALZ-EXCEL-FIELD", "ay boş olamaz"))));

        ImportResultResponse result = service.importFile(file(), USER);

        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.inserted()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    @DisplayName("a file may not mix periods: the whole file is refused")
    void multiplePeriods_rejectsWholeFile() {
        sheet(row(2, "YSV-1", MovableType.MENKUL, 7, "1.00"), row(3, "YSV-2", MovableType.MENKUL, 8, "1.00"));

        assertThatThrownBy(() -> service.importFile(file(), USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("birden fazla dönem");
        verify(repository, never()).saveAll(any());
    }

    @Test
    void emptySheet_touchesNothing() {
        when(parser.parse(any())).thenReturn(new ParsedSheet(List.of(), List.of()));

        ImportResultResponse result = service.importFile(file(), USER);

        assertThat(result.inserted()).isZero();
        assertThat(result.totalRows()).isZero();
        verify(repository, never()).lockByPeriod(any(), any());
        verify(repository, never()).saveAll(any());
    }

    @Test
    @DisplayName("an unreadable upload stream is a 400 without the I/O detail")
    void unreadableStream_isBadRequest() throws Exception {
        MockMultipartFile broken = new MockMultipartFile("file", "b.xlsx", null, new byte[0]) {
            @Override
            public java.io.InputStream getInputStream() throws java.io.IOException {
                throw new java.io.IOException("disk /tmp/xyz full");
            }
        };

        assertThatThrownBy(() -> service.importFile(broken, USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Dosya akışı okunamadı.");
    }
    // --- dogrulama (validate) --------------------------------------------------------------

    @Test
    @DisplayName("validate reports what upload would do but saves, logs and locks nothing")
    void validate_reportsWithoutWriting() {
        DeclarationProcess current = existing(1L, "YSV-1", MovableType.MENKUL, ProcessStatus.COMPLETED);
        when(repository.findByPeriod(2026, 8)).thenReturn(List.of(current));
        BigDecimal one = new BigDecimal("1.00");
        sheet(row(2, "YSV-1", MovableType.MENKUL, 8, "2.00"),
                new ParsedRow(3, 8, 34, 1234, 2026, "YSV-2", PAYMENT, MovableType.MENKUL,
                        one, one, one, 10, one, null));

        ImportResultResponse result = service.validate(file());

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.updatedFileNos()).containsExactly("YSV-1");
        assertThat(result.inserted()).isEqualTo(1);
        assertThat(result.insertedFileNos()).containsExactly("YSV-2");
        // DB'den okunan nesne değişmedi: transaction sonunda JPA'nın yazacağı bir şey yok
        assertThat(current.getReceivedPremiumAmount()).isEqualByComparingTo("1.00");
        assertThat(current.getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(current.getUpdatedByUser()).isNull();
        verify(repository, never()).saveAll(any());
        verify(repository, never()).lockByPeriod(any(), any());
        verify(declarationLogService, never()).logCall(anyList(), any(), any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("validate and upload give the same answer for the same file")
    void validate_matchesUpload() {
        List<DeclarationProcess> period = List.of(
                existing(1L, "PENTEST260801", MovableType.MENKUL, ProcessStatus.SENT),
                existing(2L, "YSV-9", MovableType.MENKUL, ProcessStatus.PROCESSING));
        period.get(1).setDistrictCode(99);
        when(repository.findByPeriod(2026, 8)).thenReturn(period);
        when(repository.lockByPeriod(2026, 8)).thenReturn(period);
        when(repository.existsBySbmFileNo("YSV-OLD")).thenReturn(true);
        BigDecimal one = new BigDecimal("1.00");
        sheet(row(2, "YSV-1", MovableType.MENKUL),                    // yuva PENTEST'te
                row(3, "YSV-OLD", MovableType.MENKUL),                // başka dönemde
                new ParsedRow(4, 8, 34, 99, 2026, "YSV-9", PAYMENT, MovableType.MENKUL,
                        new BigDecimal("5.00"), one, one, 10, one, null),  // gönderimde
                row(5, "YSV-1", MovableType.MENKUL));                 // dosyada tekrar

        ImportResultResponse validated = service.validate(file());
        ImportResultResponse uploaded = service.importFile(file(), USER);

        assertThat(validated).isEqualTo(uploaded);
        assertThat(validated.errors()).extracting(ExcelRowError::code).containsExactly(
                DeclarationImportService.CONFLICT_CODE, DeclarationImportService.CONFLICT_CODE,
                DeclarationImportService.BUSY_CODE, DeclarationImportService.DUPLICATE_CODE);
    }

    @Test
    void validate_multiplePeriods_rejectsWholeFile() {
        sheet(row(2, "YSV-1", MovableType.MENKUL, 7, "1.00"), row(3, "YSV-2", MovableType.MENKUL, 8, "1.00"));

        assertThatThrownBy(() -> service.validate(file()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("birden fazla dönem");
    }

    @Test
    void validate_emptySheet_readsNothing() {
        when(parser.parse(any())).thenReturn(new ParsedSheet(List.of(), List.of()));

        assertThat(service.validate(file()).totalRows()).isZero();
        verify(repository, never()).findByPeriod(any(), any());
    }
}
