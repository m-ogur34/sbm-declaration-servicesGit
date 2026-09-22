package tr.com.allianz.ysv.services.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static tr.com.allianz.ysv.services.testsupport.DeclarationProcessFixtures.baseRow;
import static tr.com.allianz.ysv.services.testsupport.DeclarationProcessFixtures.cityLevelRow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import tr.com.allianz.ysv.services.dto.request.RequestContext;
import tr.com.allianz.ysv.services.dto.request.DeclarationUpdateRequest;
import tr.com.allianz.ysv.services.mapper.ProcessMapper;
import tr.com.allianz.ysv.services.exception.DeclarationNotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tr.com.allianz.ysv.services.config.SbmProperties;
import tr.com.allianz.ysv.services.dto.internal.SbmCallResult;
import tr.com.allianz.ysv.services.dto.internal.SbmDeclarationRequest;
import tr.com.allianz.ysv.services.dto.internal.GroupOutcome;
import tr.com.allianz.ysv.services.entity.DeclarationProcess;
import tr.com.allianz.ysv.services.enums.LogLevel;
import tr.com.allianz.ysv.services.enums.MovableType;
import tr.com.allianz.ysv.services.enums.OperationType;
import tr.com.allianz.ysv.services.enums.ProcessStatus;
import tr.com.allianz.ysv.services.enums.SbmErrorCode;
import tr.com.allianz.ysv.services.exception.SbmIntegrationException;
import tr.com.allianz.ysv.services.exception.TokenException;
import tr.com.allianz.ysv.services.mapper.SbmMapper;
import tr.com.allianz.ysv.services.repository.DeclarationProcessRepository;
import tr.com.allianz.ysv.services.util.JsonUtil;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeclarationGroupProcessorTest {

    private static final List<Long> GROUP_IDS = List.of(1L, 2L);

    @Mock
    private DeclarationProcessRepository declarationProcessRepository;
    @Mock
    private DeclarationLogService declarationLogService;
    @Mock
    private SbmClientService sbmClientService;
    @Mock
    private SbmMapper sbmMapper;
    @Mock
    private ProcessMapper processMapper;

    private static final RequestContext CTX = RequestContext.of("WDA2422", null, null);
    private static final JsonUtil JSON = new JsonUtil(new ObjectMapper().registerModule(new JavaTimeModule()));

    private SbmProperties sbmProperties;
    private DeclarationGroupProcessor processor;

    @BeforeEach
    void setUp() {
        sbmProperties = new SbmProperties();
        sbmProperties.setCompanyCode("045");
        processor = new DeclarationGroupProcessor(declarationProcessRepository, declarationLogService,
                sbmClientService, sbmMapper, sbmProperties, processMapper,
                new JsonUtil(new ObjectMapper().registerModule(new JavaTimeModule())));
        when(sbmMapper.toSendRequest(anyList(), anyString()))
                .thenReturn(SbmDeclarationRequest.builder().build());
        when(sbmMapper.toUpdateRequest(anyList(), anyString(), anyBoolean()))
                .thenReturn(SbmDeclarationRequest.builder().build());
    }

    // --- success ------------------------------------------------------------------------

    @Test
    @DisplayName("an accepted POST moves the group to SENT and stamps DATE_SENT / SENT_BY_USER")
    void process_postAccepted_marksSent() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.NEW);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);
        when(sbmClientService.send(any(), any())).thenReturn(successResult());

        GroupOutcome failure =
                processor.process(OperationType.POST, false, GROUP_IDS, CTX);

        assertThat(failure.success()).isTrue();
        assertThat(group).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(ProcessStatus.SENT);
            assertThat(row.getDateSent()).isNotNull();
            assertThat(row.getSentByUser()).isEqualTo("WDA2422");
            assertThat(row.getErrorDetails()).isNull();
            assertThat(row.getDateUpdated()).isNull();
        });
        verify(declarationLogService).logCall(eq(GROUP_IDS), eq(OperationType.POST), eq(LogLevel.INFO),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("an accepted PUT stamps DATE_UPDATED instead of DATE_SENT")
    void process_putAccepted_marksUpdated() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.COMPLETED);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);
        when(sbmClientService.update(any(), any())).thenReturn(successResult());

        GroupOutcome failure =
                processor.process(OperationType.PUT, false, GROUP_IDS, CTX);

        assertThat(failure.success()).isTrue();
        assertThat(group).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(ProcessStatus.SENT);
            assertThat(row.getDateUpdated()).isNotNull();
            assertThat(row.getUpdatedByUser()).isEqualTo("WDA2422");
            assertThat(row.getDateSent()).isNull();
        });
        verify(sbmMapper).toUpdateRequest(group, "045", false);
        verify(sbmClientService).update(any(), any());
    }

    @Test
    @DisplayName("an accepted cancel zeroes the amounts in the database too")
    void process_cancel_zeroesTheDatabaseAmounts() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.SENT);
        group.get(0).setPrevMonthRefundAmount(new java.math.BigDecimal("12.00"));
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);
        when(sbmClientService.update(any(), any())).thenReturn(successResult());

        processor.process(OperationType.PUT, true, GROUP_IDS, CTX);

        verify(sbmMapper).toUpdateRequest(group, "045", true);
        assertThat(group).allSatisfy(row -> {
            assertThat(row.getReceivedPremiumAmount()).isZero();
            assertThat(row.getCancelledPremiumAmount()).isZero();
            assertThat(row.getTaxAmount()).isZero();
            assertThat(row.getTaxPremiumAmount()).isZero();
            assertThat(row.getTaxRatio()).isEqualTo(10);
            assertThat(row.getStatus()).isEqualTo(ProcessStatus.SENT);
        });
        assertThat(group.get(0).getPrevMonthRefundAmount()).isZero();
        assertThat(group.get(1).getPrevMonthRefundAmount()).isNull();
    }

    @Test
    @DisplayName("a rejected cancel leaves the database amounts untouched")
    void process_rejectedCancel_keepsTheAmounts() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.SENT);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);
        when(sbmClientService.update(any(), any())).thenReturn(SbmCallResult.builder()
                .success(false).sbmAnswered(true).httpStatus(422).errorCode("CORE-01004").errorMessage("red").build());

        GroupOutcome outcome = processor.process(OperationType.PUT, true, GROUP_IDS, CTX);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.httpStatus()).isEqualTo(422);
        assertThat(group).allSatisfy(row -> assertThat(row.getReceivedPremiumAmount()).isEqualByComparingTo("7453723.22"));
    }

    @Test
    @DisplayName("a non-SBM answer (e.g. ESB error page) is reported as 502 without the raw body")
    void process_nonSbmAnswer_isBadGateway() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.NEW);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);
        when(sbmClientService.send(any(), any())).thenReturn(SbmCallResult.builder()
                .success(false).sbmAnswered(false).httpStatus(404).errorCode("CORE-00000")
                .responsePayload("<HTML>Error 404--Not Found</HTML>").build());

        GroupOutcome outcome = processor.process(OperationType.POST, false, GROUP_IDS, CTX);

        assertThat(outcome.httpStatus()).isEqualTo(502);
        assertThat(outcome.sbmResponse()).isNull();
    }


    // --- applyUpdate (tekli guncellemenin DB kismi) ------------------------------------------

    private static DeclarationUpdateRequest.AmountLine line(MovableType type, String amount) {
        BigDecimal value = new BigDecimal(amount);
        return new DeclarationUpdateRequest.AmountLine(type, value, BigDecimal.ONE, value, 10, value, null);
    }

    @Test
    @DisplayName("applyUpdate writes the new amounts, rounded to the column scale, and audits the change")
    void applyUpdate_writesAmountsAndAudits() {
        List<DeclarationProcess> rows = newGroup(ProcessStatus.SENT);
        when(declarationProcessRepository.lockBySbmFileNo("YSV202513491")).thenReturn(rows);

        processor.applyUpdate("YSV202513491", new DeclarationUpdateRequest(LocalDate.of(2026, 9, 20),
                List.of(line(MovableType.MENKUL, "1000.005"))), "WDA2422");

        DeclarationProcess menkul = rows.stream().filter(r -> r.getMovableType() == MovableType.MENKUL).findFirst().orElseThrow();
        DeclarationProcess gayri = rows.stream().filter(r -> r.getMovableType() == MovableType.GAYRIMENKUL).findFirst().orElseThrow();
        assertThat(menkul.getReceivedPremiumAmount()).isEqualByComparingTo("1000.01");
        assertThat(gayri.getReceivedPremiumAmount()).isEqualByComparingTo("7453723.22");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getPaymentDate()).isEqualTo(LocalDate.of(2026, 9, 20));
            assertThat(r.getUpdatedByUser()).isEqualTo("WDA2422");
            assertThat(r.getStatus()).isEqualTo(ProcessStatus.SENT);
        });
        verify(declarationProcessRepository).saveAll(rows);
        verify(declarationLogService).logCall(anyList(), eq(OperationType.LOCAL_UPDATE), eq(LogLevel.INFO),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("a COMPLETED declaration falls back to SENT once its amounts change")
    void applyUpdate_completedFallsBackToSent() {
        List<DeclarationProcess> rows = newGroup(ProcessStatus.COMPLETED);
        when(declarationProcessRepository.lockBySbmFileNo("YSV202513491")).thenReturn(rows);

        processor.applyUpdate("YSV202513491", new DeclarationUpdateRequest(null,
                List.of(line(MovableType.MENKUL, "1"))), "WDA2422");

        assertThat(rows).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(ProcessStatus.SENT));
    }

    @Test
    @DisplayName("gecmisAyIadeTutari and the payment date are kept when the request leaves them out")
    void applyUpdate_keepsOptionalFields() {
        List<DeclarationProcess> rows = newGroup(ProcessStatus.NEW);
        rows.forEach(r -> r.setPrevMonthRefundAmount(new BigDecimal("12.00")));
        LocalDate before = rows.get(0).getPaymentDate();
        when(declarationProcessRepository.lockBySbmFileNo("YSV202513491")).thenReturn(rows);

        processor.applyUpdate("YSV202513491", new DeclarationUpdateRequest(null,
                List.of(line(MovableType.MENKUL, "1"))), "WDA2422");

        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getPrevMonthRefundAmount()).isEqualByComparingTo("12.00");
            assertThat(r.getPaymentDate()).isEqualTo(before);
            assertThat(r.getStatus()).isEqualTo(ProcessStatus.NEW);
        });
    }

    @Test
    void applyUpdate_writesGecmisAyIadeWhenGiven() {
        List<DeclarationProcess> rows = newGroup(ProcessStatus.SENT);
        when(declarationProcessRepository.lockBySbmFileNo("YSV202513491")).thenReturn(rows);

        processor.applyUpdate("YSV202513491", new DeclarationUpdateRequest(null, List.of(
                new DeclarationUpdateRequest.AmountLine(MovableType.MENKUL, BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ONE, 10, BigDecimal.ONE, new BigDecimal("-50")))), "WDA2422");

        assertThat(rows.stream().filter(r -> r.getMovableType() == MovableType.MENKUL).findFirst().orElseThrow()
                .getPrevMonthRefundAmount()).isEqualByComparingTo("-50.00");
    }

    @Test
    void applyUpdate_unknownFileNo_isNotFound() {
        when(declarationProcessRepository.lockBySbmFileNo("YOK")).thenReturn(List.of());

        assertThatThrownBy(() -> processor.applyUpdate("YOK",
                new DeclarationUpdateRequest(null, List.of(line(MovableType.MENKUL, "1"))), "WDA2422"))
                .isInstanceOf(DeclarationNotFoundException.class);
    }

    @Test
    void applyUpdate_processingDeclaration_isRejected() {
        when(declarationProcessRepository.lockBySbmFileNo("YSV202513491")).thenReturn(newGroup(ProcessStatus.PROCESSING));

        assertThatThrownBy(() -> processor.applyUpdate("YSV202513491",
                new DeclarationUpdateRequest(null, List.of(line(MovableType.MENKUL, "1"))), "WDA2422"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PROCESSING");
        verify(declarationProcessRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("an update cannot add a movable type the declaration does not have")
    void applyUpdate_unknownMovableType_isRejected() {
        List<DeclarationProcess> onlyMenkul = List.of(cityLevelRow(1L, MovableType.MENKUL));
        when(declarationProcessRepository.lockBySbmFileNo("YSV202513491")).thenReturn(onlyMenkul);

        assertThatThrownBy(() -> processor.applyUpdate("YSV202513491",
                new DeclarationUpdateRequest(null, List.of(line(MovableType.GAYRIMENKUL, "1"))), "WDA2422"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GAYRIMENKUL");
    }

    @Test
    void applyUpdate_repeatedMovableType_isRejected() {
        when(declarationProcessRepository.lockBySbmFileNo("YSV202513491")).thenReturn(newGroup(ProcessStatus.SENT));

        assertThatThrownBy(() -> processor.applyUpdate("YSV202513491", new DeclarationUpdateRequest(null,
                List.of(line(MovableType.MENKUL, "1"), line(MovableType.MENKUL, "2"))), "WDA2422"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tekrarlanamaz");
    }

    @Test
    @DisplayName("the audit message carries the Transaction-Id, the masked requester and the user")
    void buildLogMessage_carriesTraceability() {
        String message = DeclarationGroupProcessor.buildLogMessage(OperationType.POST, "YSV1",
                SbmCallResult.builder().success(true).httpStatus(201).transactionId("tx-1")
                        .requesterIdType("1").requesterIdNo("12*******01").build(), "WDA2422");

        assertThat(message).contains("POST YSV1 başarılı", "HTTP 201", "Transaction-Id: tx-1",
                "Requester: 1/12*******01", "kullanıcı: WDA2422");
    }

    // --- failures -----------------------------------------------------------------------

    @Test
    @DisplayName("a rejected PUT keeps the group where it was: an ERROR row would be POSTed again")
    void process_putRejected_keepsPreviousStatus() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.SENT);
        group.get(1).setStatus(ProcessStatus.COMPLETED);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);
        when(sbmClientService.update(any(), any())).thenReturn(SbmCallResult.builder()
                .success(false)
                .httpStatus(422)
                .errorCode(SbmErrorCode.CORE_01004.getCode())
                .errorMessage("ilceKodu alanının değeri 1 - 4 arasında olmalıdır.")
                .build());

        GroupOutcome failure =
                processor.process(OperationType.PUT, false, GROUP_IDS, CTX);

        assertThat(failure.success()).isFalse();
        assertThat(group.get(0).getStatus()).isEqualTo(ProcessStatus.SENT);
        assertThat(group.get(1).getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(group).allSatisfy(row ->
                assertThat(row.getErrorDetails()).contains("ilceKodu"));
    }

    @Test
    @DisplayName("RISK-HAVUZU-00004 means SBM already holds the declaration, so the group becomes SENT")
    void process_duplicateDeclaration_marksSent() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.NEW);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);
        when(sbmClientService.send(any(), any())).thenReturn(SbmCallResult.builder()
                .success(false)
                .httpStatus(422)
                .errorCode(SbmErrorCode.RISK_HAVUZU_00004.getCode())
                .errorMessage("RISK-HAVUZU-00004: Mükerrer Beyanname mevcut")
                .build());

        GroupOutcome failure =
                processor.process(OperationType.POST, false, GROUP_IDS, CTX);

        assertThat(failure.success()).isFalse();
        assertThat(group).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(ProcessStatus.SENT);
            assertThat(row.getErrorDetails()).contains("RISK-HAVUZU-00004");
        });
    }

    @Test
    @DisplayName("RISK-HAVUZU-00004 naming another file no means the slot is taken by a different declaration: ERROR")
    void process_duplicateOfOtherDeclaration_marksError() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.NEW);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);
        when(sbmClientService.send(any(), any())).thenReturn(SbmCallResult.builder()
                .success(false)
                .httpStatus(422)
                .errorCode(SbmErrorCode.RISK_HAVUZU_00004.getCode())
                .errorMessage("RISK-HAVUZU-00004: Mükerrer beyanname kaydı mevcuttur. Dosya no: PENTEST260811")
                .build());

        processor.process(OperationType.POST, false, GROUP_IDS, CTX);

        assertThat(group).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(ProcessStatus.ERROR);
            assertThat(row.getErrorDetails()).contains("PENTEST260811");
        });
    }

    @Test
    @DisplayName("isSameDeclaration: own file no or no file no in message -> true, other file no -> false")
    void isSameDeclaration_comparesFileNoInMessage() {
        assertThat(DeclarationGroupProcessor.isSameDeclaration("YSV1", "Mükerrer ... Dosya no: YSV1")).isTrue();
        assertThat(DeclarationGroupProcessor.isSameDeclaration("YSV1", "Mükerrer ... Dosya no: YSV2")).isFalse();
        assertThat(DeclarationGroupProcessor.isSameDeclaration("YSV1", "Mükerrer beyanname mevcut")).isTrue();
        assertThat(DeclarationGroupProcessor.isSameDeclaration("YSV1", null)).isTrue();
    }

    @Test
    @DisplayName("a token failure on PUT also leaves the group in its previous status")
    void process_putTokenFailure_keepsPreviousStatus() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.SENT);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);
        when(sbmClientService.update(any(), any())).thenThrow(new TokenException("token alınamadı"));

        GroupOutcome failure =
                processor.process(OperationType.PUT, false, GROUP_IDS, CTX);

        assertThat(failure.success()).isFalse();
        assertThat(group).allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(ProcessStatus.SENT));
    }

    @Test
    @DisplayName("a rejected call moves the group to ERROR and stores SBM's message")
    void process_rejected_marksError() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.NEW);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);
        when(sbmClientService.send(any(), any())).thenReturn(SbmCallResult.builder()
                .success(false)
                .httpStatus(422)
                .errorCode("CORE-01004")
                .errorMessage("CORE-01004 [ilceKodu]: değer aralık dışında")
                .requestPayload("{}")
                .responsePayload("{}")
                .build());

        GroupOutcome failure =
                processor.process(OperationType.POST, false, GROUP_IDS, CTX);

        assertThat(failure.success()).isFalse();
        assertThat(failure.errorCode()).isEqualTo("CORE-01004");
        assertThat(failure.ysvDosyaNo()).isEqualTo("YSV202513491");
        assertThat(group).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(ProcessStatus.ERROR);
            assertThat(row.getErrorDetails()).contains("CORE-01004");
            assertThat(row.getUpdatedByUser()).isEqualTo("WDA2422");
        });
        verify(declarationLogService).logCall(eq(GROUP_IDS), eq(OperationType.POST), eq(LogLevel.ERROR),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("ERROR_DETAILS never exceeds the 2000 characters the column holds")
    void process_longErrorMessage_isTruncated() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.NEW);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);
        when(sbmClientService.send(any(), any())).thenReturn(SbmCallResult.builder()
                .success(false)
                .httpStatus(422)
                .errorCode("CORE-01004")
                .errorMessage("x".repeat(3000))
                .build());

        processor.process(OperationType.POST, false, GROUP_IDS, CTX);

        assertThat(group.get(0).getErrorDetails()).hasSize(JsonUtil.ERROR_DETAILS_MAX_LENGTH);
    }

    @Test
    @DisplayName("a pre-flight validation failure is logged with empty payloads: nothing was sent")
    void process_validationFailure_marksErrorWithoutCallingSbm() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.NEW);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);
        when(sbmMapper.toSendRequest(anyList(), anyString())).thenThrow(
                new SbmIntegrationException(SbmErrorCode.RISK_HAVUZU_00005.getCode(),
                        "Aynı beyannamede mükerrer menkul tipi var: MENKUL"));

        GroupOutcome failure =
                processor.process(OperationType.POST, false, GROUP_IDS, CTX);

        assertThat(failure.success()).isFalse();
        assertThat(failure.errorCode()).isEqualTo(SbmErrorCode.RISK_HAVUZU_00005.getCode());
        assertThat(group.get(0).getStatus()).isEqualTo(ProcessStatus.ERROR);
        verify(sbmClientService, never()).send(any(), any());
        verify(declarationLogService).logCall(eq(GROUP_IDS), eq(OperationType.POST), eq(LogLevel.ERROR),
                anyString(), isNull(), isNull());
    }

    @Test
    @DisplayName("a duplicated movable type never reaches SBM and lands in ERROR")
    void process_duplicateMovableType_marksErrorWithoutCallingSbm() {
        DeclarationGroupProcessor withRealMapper = new DeclarationGroupProcessor(
                declarationProcessRepository, declarationLogService, sbmClientService,
                new SbmMapper(), sbmProperties, processMapper, JSON);
        List<DeclarationProcess> group = new java.util.ArrayList<>(List.of(
                cityLevelRow(1L, MovableType.MENKUL),
                cityLevelRow(2L, MovableType.MENKUL)));
        group.forEach(row -> row.setStatus(ProcessStatus.NEW));
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);

        GroupOutcome failure =
                withRealMapper.process(OperationType.POST, false, GROUP_IDS, CTX);

        assertThat(failure.success()).isFalse();
        assertThat(failure.errorCode()).isEqualTo(SbmErrorCode.RISK_HAVUZU_00005.getCode());
        assertThat(group).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(ProcessStatus.ERROR);
            assertThat(row.getErrorDetails()).contains("mükerrer menkul tipi");
        });
        verify(sbmClientService, never()).send(any(), any());
    }

    @Test
    @DisplayName("a ysvDosyaNo over 36 characters lands in ERROR without an SBM call")
    void process_tooLongFileNo_marksErrorWithoutCallingSbm() {
        DeclarationGroupProcessor withRealMapper = new DeclarationGroupProcessor(
                declarationProcessRepository, declarationLogService, sbmClientService,
                new SbmMapper(), sbmProperties, processMapper, JSON);
        DeclarationProcess row = baseRow(1L, MovableType.MENKUL)
                .cityCode(1)
                .districtCode(0)
                .sbmFileNo("Y".repeat(37))
                .status(ProcessStatus.NEW)
                .build();
        when(declarationProcessRepository.lockByIds(List.of(1L)))
                .thenReturn(new java.util.ArrayList<>(List.of(row)));

        GroupOutcome failure =
                withRealMapper.process(OperationType.POST, false, List.of(1L), CTX);

        assertThat(failure.success()).isFalse();
        assertThat(failure.errorCode()).isEqualTo(SbmErrorCode.CORE_01008.getCode());
        assertThat(failure.httpStatus()).isEqualTo(422);
        assertThat(row.getStatus()).isEqualTo(ProcessStatus.ERROR);
        assertThat(row.getErrorDetails()).contains("en fazla 36 karakter");
        verify(sbmClientService, never()).send(any(), any());
    }

    @Test
    @DisplayName("a sigortaSirketKodu over 3 characters lands in ERROR without an SBM call")
    void process_tooLongCompanyCode_marksErrorWithoutCallingSbm() {
        SbmProperties wrongCompanyCode = new SbmProperties();
        wrongCompanyCode.setCompanyCode("2320");        // OPUS internal code, not the SBM one
        DeclarationGroupProcessor withRealMapper = new DeclarationGroupProcessor(
                declarationProcessRepository, declarationLogService, sbmClientService,
                new SbmMapper(), wrongCompanyCode, processMapper, JSON);
        List<DeclarationProcess> group = newGroup(ProcessStatus.NEW);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);

        GroupOutcome failure =
                withRealMapper.process(OperationType.POST, false, GROUP_IDS, CTX);

        assertThat(failure.success()).isFalse();
        assertThat(failure.errorCode()).isEqualTo(SbmErrorCode.CORE_01008.getCode());
        assertThat(failure.httpStatus()).isEqualTo(422);
        assertThat(group).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(ProcessStatus.ERROR);
            assertThat(row.getErrorDetails()).contains("en fazla 3 karakter");
        });
        verify(sbmClientService, never()).send(any(), any());
    }

    @Test
    @DisplayName("a city without a district is sent: the district rule is SBM's to enforce")
    void process_missingDistrict_isSentAnyway() {
        DeclarationGroupProcessor withRealMapper = new DeclarationGroupProcessor(
                declarationProcessRepository, declarationLogService, sbmClientService,
                new SbmMapper(), sbmProperties, processMapper, JSON);
        DeclarationProcess row = baseRow(1L, MovableType.MENKUL)
                .cityCode(2)
                .districtCode(0)
                .status(ProcessStatus.NEW)
                .build();
        when(declarationProcessRepository.lockByIds(List.of(1L)))
                .thenReturn(new java.util.ArrayList<>(List.of(row)));
        when(sbmClientService.send(any(), any())).thenReturn(successResult());

        GroupOutcome failure =
                withRealMapper.process(OperationType.POST, false, List.of(1L), CTX);

        assertThat(failure.success()).isTrue();
        assertThat(row.getStatus()).isEqualTo(ProcessStatus.SENT);
        verify(sbmClientService).send(any(), any());
    }

    @Test
    void process_tokenFailure_marksErrorWithSec00001() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.NEW);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);
        when(sbmClientService.send(any(), any())).thenThrow(new TokenException("Token servisine erişilemedi"));

        GroupOutcome failure =
                processor.process(OperationType.POST, false, GROUP_IDS, CTX);

        assertThat(failure.success()).isFalse();
        assertThat(failure.errorCode()).isEqualTo(SbmErrorCode.SEC_00001.getCode());
        assertThat(failure.httpStatus()).isEqualTo(503);
        assertThat(group.get(0).getStatus()).isEqualTo(ProcessStatus.ERROR);
    }

    @Test
    void process_missingRows_isReported() {
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(List.of());

        GroupOutcome failure =
                processor.process(OperationType.POST, false, GROUP_IDS, CTX);

        assertThat(failure.success()).isFalse();
        assertThat(failure.errorCode()).isEqualTo(SbmErrorCode.CORE_01001.getCode());
        assertThat(failure.ysvDosyaNo()).isNull();
        assertThat(failure.httpStatus()).isEqualTo(404);
    }

    // --- status guard ---------------------------------------------------------------------

    @Test
    @DisplayName("a group that another transaction already sent is not sent again")
    void process_postOnAlreadySentGroup_isRejected() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.SENT);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);

        GroupOutcome failure =
                processor.process(OperationType.POST, false, GROUP_IDS, CTX);

        assertThat(failure.success()).isFalse();
        assertThat(failure.errorCode()).isEqualTo(DeclarationGroupProcessor.STATUS_CONFLICT_CODE);
        assertThat(failure.httpStatus()).isEqualTo(409);
        assertThat(group.get(0).getStatus()).isEqualTo(ProcessStatus.SENT);
        verify(sbmClientService, never()).send(any(), any());
    }

    @Test
    void process_updateOnNewGroup_isRejected() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.NEW);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);

        GroupOutcome failure =
                processor.process(OperationType.PUT, false, GROUP_IDS, CTX);

        assertThat(failure.success()).isFalse();
        assertThat(failure.errorCode()).isEqualTo(DeclarationGroupProcessor.STATUS_CONFLICT_CODE);
        assertThat(failure.httpStatus()).isEqualTo(409);
        verify(sbmClientService, never()).update(any(), any());
    }

    @Test
    void process_rowWithoutStatus_isRejected() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.NEW);
        group.get(1).setStatus(null);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);

        GroupOutcome failure =
                processor.process(OperationType.POST, false, GROUP_IDS, CTX);

        assertThat(failure.success()).isFalse();
        assertThat(failure.errorCode()).isEqualTo(DeclarationGroupProcessor.STATUS_CONFLICT_CODE);
        assertThat(failure.httpStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("a group is only eligible when every one of its rows is")
    void process_mixedStatuses_isRejected() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.NEW);
        group.get(1).setStatus(ProcessStatus.COMPLETED);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);

        assertThat(processor.process(OperationType.POST, false, GROUP_IDS, CTX).success()).isFalse();
    }

    // --- COMPLETED promotion ---------------------------------------------------------------

    @Test
    @DisplayName("only SENT rows are promoted to COMPLETED")
    void markCompleted_promotesOnlySentRows() {
        DeclarationProcess sent = cityLevelRow(1L, MovableType.MENKUL);
        sent.setStatus(ProcessStatus.SENT);
        DeclarationProcess errored = cityLevelRow(2L, MovableType.GAYRIMENKUL);
        errored.setStatus(ProcessStatus.ERROR);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(List.of(sent, errored));

        processor.markCompleted(GROUP_IDS, "WDA2422");

        assertThat(sent.getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(sent.getUpdatedByUser()).isEqualTo("WDA2422");
        assertThat(sent.getDateUpdated()).isNotNull();
        assertThat(errored.getStatus()).isEqualTo(ProcessStatus.ERROR);
        assertThat(errored.getUpdatedByUser()).isNull();
    }

    private static List<DeclarationProcess> newGroup(ProcessStatus status) {
        DeclarationProcess menkul = cityLevelRow(1L, MovableType.MENKUL);
        DeclarationProcess gayrimenkul = cityLevelRow(2L, MovableType.GAYRIMENKUL);
        menkul.setStatus(status);
        gayrimenkul.setStatus(status);
        return new java.util.ArrayList<>(List.of(menkul, gayrimenkul));
    }

    private static SbmCallResult successResult() {
        return SbmCallResult.builder()
                .success(true)
                .sbmAnswered(true)
                .httpStatus(200)
                .transactionId("tx-1")
                .requestPayload("{}")
                .responsePayload("{\"result\":true}")
                .ysvDosyaNo("YSV202513491")
                .build();
    }
}
