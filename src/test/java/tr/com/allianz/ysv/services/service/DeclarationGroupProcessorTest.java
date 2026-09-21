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

import java.util.List;
import java.util.Optional;
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
import tr.com.allianz.ysv.services.dto.response.FailureDetail;
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

    private SbmProperties sbmProperties;
    private DeclarationGroupProcessor processor;

    @BeforeEach
    void setUp() {
        sbmProperties = new SbmProperties();
        sbmProperties.setCompanyCode("045");
        processor = new DeclarationGroupProcessor(declarationProcessRepository, declarationLogService,
                sbmClientService, sbmMapper, sbmProperties);
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
        when(sbmClientService.send(any())).thenReturn(successResult());

        Optional<FailureDetail> failure =
                processor.process(OperationType.POST, false, GROUP_IDS, "WDA2422");

        assertThat(failure).isEmpty();
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
        when(sbmClientService.update(any())).thenReturn(successResult());

        Optional<FailureDetail> failure =
                processor.process(OperationType.PUT, false, GROUP_IDS, "WDA2422");

        assertThat(failure).isEmpty();
        assertThat(group).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(ProcessStatus.SENT);
            assertThat(row.getDateUpdated()).isNotNull();
            assertThat(row.getUpdatedByUser()).isEqualTo("WDA2422");
            assertThat(row.getDateSent()).isNull();
        });
        verify(sbmMapper).toUpdateRequest(group, "045", false);
        verify(sbmClientService).update(any());
    }

    @Test
    @DisplayName("cancel maps the group with zeroed amounts")
    void process_cancel_usesZeroedAmounts() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.SENT);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);
        when(sbmClientService.update(any())).thenReturn(successResult());

        processor.process(OperationType.PUT, true, GROUP_IDS, "WDA2422");

        verify(sbmMapper).toUpdateRequest(group, "045", true);
    }

    // --- failures -----------------------------------------------------------------------

    @Test
    @DisplayName("a rejected PUT keeps the group where it was: an ERROR row would be POSTed again")
    void process_putRejected_keepsPreviousStatus() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.SENT);
        group.get(1).setStatus(ProcessStatus.COMPLETED);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);
        when(sbmClientService.update(any())).thenReturn(SbmCallResult.builder()
                .success(false)
                .httpStatus(422)
                .errorCode(SbmErrorCode.CORE_01004.getCode())
                .errorMessage("ilceKodu alanının değeri 1 - 4 arasında olmalıdır.")
                .build());

        Optional<FailureDetail> failure =
                processor.process(OperationType.PUT, false, GROUP_IDS, "WDA2422");

        assertThat(failure).isPresent();
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
        when(sbmClientService.send(any())).thenReturn(SbmCallResult.builder()
                .success(false)
                .httpStatus(422)
                .errorCode(SbmErrorCode.RISK_HAVUZU_00004.getCode())
                .errorMessage("RISK-HAVUZU-00004: Mükerrer Beyanname mevcut")
                .build());

        Optional<FailureDetail> failure =
                processor.process(OperationType.POST, false, GROUP_IDS, "WDA2422");

        assertThat(failure).isPresent();
        assertThat(group).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(ProcessStatus.SENT);
            assertThat(row.getErrorDetails()).contains("RISK-HAVUZU-00004");
        });
    }

    @Test
    @DisplayName("a token failure on PUT also leaves the group in its previous status")
    void process_putTokenFailure_keepsPreviousStatus() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.SENT);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);
        when(sbmClientService.update(any())).thenThrow(new TokenException("token alınamadı"));

        Optional<FailureDetail> failure =
                processor.process(OperationType.PUT, false, GROUP_IDS, "WDA2422");

        assertThat(failure).isPresent();
        assertThat(group).allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(ProcessStatus.SENT));
    }

    @Test
    @DisplayName("a rejected call moves the group to ERROR and stores SBM's message")
    void process_rejected_marksError() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.NEW);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);
        when(sbmClientService.send(any())).thenReturn(SbmCallResult.builder()
                .success(false)
                .httpStatus(422)
                .errorCode("CORE-01004")
                .errorMessage("CORE-01004 [ilceKodu]: değer aralık dışında")
                .requestPayload("{}")
                .responsePayload("{}")
                .build());

        Optional<FailureDetail> failure =
                processor.process(OperationType.POST, false, GROUP_IDS, "WDA2422");

        assertThat(failure).isPresent();
        assertThat(failure.get().errorCode()).isEqualTo("CORE-01004");
        assertThat(failure.get().ysvDosyaNo()).isEqualTo("YSV202513491");
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
        when(sbmClientService.send(any())).thenReturn(SbmCallResult.builder()
                .success(false)
                .httpStatus(422)
                .errorCode("CORE-01004")
                .errorMessage("x".repeat(3000))
                .build());

        processor.process(OperationType.POST, false, GROUP_IDS, "WDA2422");

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

        Optional<FailureDetail> failure =
                processor.process(OperationType.POST, false, GROUP_IDS, "WDA2422");

        assertThat(failure).isPresent();
        assertThat(failure.get().errorCode()).isEqualTo(SbmErrorCode.RISK_HAVUZU_00005.getCode());
        assertThat(group.get(0).getStatus()).isEqualTo(ProcessStatus.ERROR);
        verify(sbmClientService, never()).send(any());
        verify(declarationLogService).logCall(eq(GROUP_IDS), eq(OperationType.POST), eq(LogLevel.ERROR),
                anyString(), isNull(), isNull());
    }

    @Test
    @DisplayName("a duplicated movable type never reaches SBM and lands in ERROR")
    void process_duplicateMovableType_marksErrorWithoutCallingSbm() {
        DeclarationGroupProcessor withRealMapper = new DeclarationGroupProcessor(
                declarationProcessRepository, declarationLogService, sbmClientService,
                new SbmMapper(), sbmProperties);
        List<DeclarationProcess> group = new java.util.ArrayList<>(List.of(
                cityLevelRow(1L, MovableType.MENKUL),
                cityLevelRow(2L, MovableType.MENKUL)));
        group.forEach(row -> row.setStatus(ProcessStatus.NEW));
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);

        Optional<FailureDetail> failure =
                withRealMapper.process(OperationType.POST, false, GROUP_IDS, "WDA2422");

        assertThat(failure).isPresent();
        assertThat(failure.get().errorCode()).isEqualTo(SbmErrorCode.RISK_HAVUZU_00005.getCode());
        assertThat(group).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(ProcessStatus.ERROR);
            assertThat(row.getErrorDetails()).contains("mükerrer menkul tipi");
        });
        verify(sbmClientService, never()).send(any());
    }

    @Test
    @DisplayName("a ysvDosyaNo over 36 characters lands in ERROR without an SBM call")
    void process_tooLongFileNo_marksErrorWithoutCallingSbm() {
        DeclarationGroupProcessor withRealMapper = new DeclarationGroupProcessor(
                declarationProcessRepository, declarationLogService, sbmClientService,
                new SbmMapper(), sbmProperties);
        DeclarationProcess row = baseRow(1L, MovableType.MENKUL)
                .cityCode(1)
                .districtCode(0)
                .sbmFileNo("Y".repeat(37))
                .status(ProcessStatus.NEW)
                .build();
        when(declarationProcessRepository.lockByIds(List.of(1L)))
                .thenReturn(new java.util.ArrayList<>(List.of(row)));

        Optional<FailureDetail> failure =
                withRealMapper.process(OperationType.POST, false, List.of(1L), "WDA2422");

        assertThat(failure).isPresent();
        assertThat(failure.get().errorCode()).isEqualTo(SbmErrorCode.CORE_01008.getCode());
        assertThat(row.getStatus()).isEqualTo(ProcessStatus.ERROR);
        assertThat(row.getErrorDetails()).contains("en fazla 36 karakter");
        verify(sbmClientService, never()).send(any());
    }

    @Test
    @DisplayName("a sigortaSirketKodu over 3 characters lands in ERROR without an SBM call")
    void process_tooLongCompanyCode_marksErrorWithoutCallingSbm() {
        SbmProperties wrongCompanyCode = new SbmProperties();
        wrongCompanyCode.setCompanyCode("2320");        // OPUS internal code, not the SBM one
        DeclarationGroupProcessor withRealMapper = new DeclarationGroupProcessor(
                declarationProcessRepository, declarationLogService, sbmClientService,
                new SbmMapper(), wrongCompanyCode);
        List<DeclarationProcess> group = newGroup(ProcessStatus.NEW);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);

        Optional<FailureDetail> failure =
                withRealMapper.process(OperationType.POST, false, GROUP_IDS, "WDA2422");

        assertThat(failure).isPresent();
        assertThat(failure.get().errorCode()).isEqualTo(SbmErrorCode.CORE_01008.getCode());
        assertThat(group).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(ProcessStatus.ERROR);
            assertThat(row.getErrorDetails()).contains("en fazla 3 karakter");
        });
        verify(sbmClientService, never()).send(any());
    }

    @Test
    @DisplayName("a city without a district is sent: the district rule is SBM's to enforce")
    void process_missingDistrict_isSentAnyway() {
        DeclarationGroupProcessor withRealMapper = new DeclarationGroupProcessor(
                declarationProcessRepository, declarationLogService, sbmClientService,
                new SbmMapper(), sbmProperties);
        DeclarationProcess row = baseRow(1L, MovableType.MENKUL)
                .cityCode(2)
                .districtCode(0)
                .status(ProcessStatus.NEW)
                .build();
        when(declarationProcessRepository.lockByIds(List.of(1L)))
                .thenReturn(new java.util.ArrayList<>(List.of(row)));
        when(sbmClientService.send(any())).thenReturn(successResult());

        Optional<FailureDetail> failure =
                withRealMapper.process(OperationType.POST, false, List.of(1L), "WDA2422");

        assertThat(failure).isEmpty();
        assertThat(row.getStatus()).isEqualTo(ProcessStatus.SENT);
        verify(sbmClientService).send(any());
    }

    @Test
    void process_tokenFailure_marksErrorWithSec00001() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.NEW);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);
        when(sbmClientService.send(any())).thenThrow(new TokenException("Token servisine erişilemedi"));

        Optional<FailureDetail> failure =
                processor.process(OperationType.POST, false, GROUP_IDS, "WDA2422");

        assertThat(failure).isPresent();
        assertThat(failure.get().errorCode()).isEqualTo(SbmErrorCode.SEC_00001.getCode());
        assertThat(group.get(0).getStatus()).isEqualTo(ProcessStatus.ERROR);
    }

    @Test
    void process_missingRows_isReported() {
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(List.of());

        Optional<FailureDetail> failure =
                processor.process(OperationType.POST, false, GROUP_IDS, "WDA2422");

        assertThat(failure).isPresent();
        assertThat(failure.get().errorCode()).isEqualTo(SbmErrorCode.CORE_01001.getCode());
        assertThat(failure.get().ysvDosyaNo()).isNull();
    }

    // --- status guard ---------------------------------------------------------------------

    @Test
    @DisplayName("a group that another transaction already sent is not sent again")
    void process_postOnAlreadySentGroup_isRejected() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.SENT);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);

        Optional<FailureDetail> failure =
                processor.process(OperationType.POST, false, GROUP_IDS, "WDA2422");

        assertThat(failure).isPresent();
        assertThat(failure.get().errorCode()).isEqualTo(DeclarationGroupProcessor.STATUS_CONFLICT_CODE);
        assertThat(group.get(0).getStatus()).isEqualTo(ProcessStatus.SENT);
        verify(sbmClientService, never()).send(any());
    }

    @Test
    void process_updateOnNewGroup_isRejected() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.NEW);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);

        Optional<FailureDetail> failure =
                processor.process(OperationType.PUT, false, GROUP_IDS, "WDA2422");

        assertThat(failure).isPresent();
        assertThat(failure.get().errorCode()).isEqualTo(DeclarationGroupProcessor.STATUS_CONFLICT_CODE);
        verify(sbmClientService, never()).update(any());
    }

    @Test
    void process_rowWithoutStatus_isRejected() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.NEW);
        group.get(1).setStatus(null);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);

        Optional<FailureDetail> failure =
                processor.process(OperationType.POST, false, GROUP_IDS, "WDA2422");

        assertThat(failure).isPresent();
        assertThat(failure.get().errorCode()).isEqualTo(DeclarationGroupProcessor.STATUS_CONFLICT_CODE);
    }

    @Test
    @DisplayName("a group is only eligible when every one of its rows is")
    void process_mixedStatuses_isRejected() {
        List<DeclarationProcess> group = newGroup(ProcessStatus.NEW);
        group.get(1).setStatus(ProcessStatus.COMPLETED);
        when(declarationProcessRepository.lockByIds(GROUP_IDS)).thenReturn(group);

        assertThat(processor.process(OperationType.POST, false, GROUP_IDS, "WDA2422")).isPresent();
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
                .httpStatus(200)
                .transactionId("tx-1")
                .requestPayload("{}")
                .responsePayload("{\"result\":true}")
                .ysvDosyaNo("YSV202513491")
                .build();
    }
}
