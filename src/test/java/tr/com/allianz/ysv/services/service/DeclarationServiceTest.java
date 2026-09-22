package tr.com.allianz.ysv.services.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static tr.com.allianz.ysv.services.testsupport.DeclarationProcessFixtures.districtRow;
import static tr.com.allianz.ysv.services.testsupport.DeclarationProcessFixtures.cityLevelRow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import tr.com.allianz.ysv.services.config.SbmProperties;
import tr.com.allianz.ysv.services.dto.internal.SbmCallResult;
import tr.com.allianz.ysv.services.dto.internal.SbmQueryResponse;
import tr.com.allianz.ysv.services.dto.request.DeclarationFilterRequest;
import tr.com.allianz.ysv.services.dto.request.DeclarationUpdateRequest;
import tr.com.allianz.ysv.services.dto.request.RequestContext;
import tr.com.allianz.ysv.services.dto.response.DeclarationUpdateResponse;
import tr.com.allianz.ysv.services.dto.response.BatchOperationResponse;
import tr.com.allianz.ysv.services.dto.response.FailureDetail;
import tr.com.allianz.ysv.services.dto.response.PageResponse;
import tr.com.allianz.ysv.services.dto.response.ProcessView;
import tr.com.allianz.ysv.services.entity.DeclarationProcess;
import tr.com.allianz.ysv.services.enums.LogLevel;
import tr.com.allianz.ysv.services.enums.MovableType;
import tr.com.allianz.ysv.services.enums.OperationType;
import tr.com.allianz.ysv.services.enums.ProcessStatus;
import tr.com.allianz.ysv.services.enums.SbmErrorCode;
import tr.com.allianz.ysv.services.exception.DeclarationNotFoundException;
import tr.com.allianz.ysv.services.exception.SbmIntegrationException;
import tr.com.allianz.ysv.services.mapper.ProcessMapper;
import tr.com.allianz.ysv.services.mapper.SbmMapper;
import tr.com.allianz.ysv.services.repository.DeclarationProcessRepository;
import tr.com.allianz.ysv.services.util.JsonUtil;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeclarationServiceTest {

    private static final String USER = "WDA2422";
    private static final RequestContext CTX = RequestContext.of(USER, null, null);

    @Mock
    private DeclarationProcessRepository declarationProcessRepository;
    @Mock
    private DeclarationGroupProcessor declarationGroupProcessor;
    @Mock
    private DeclarationLogService declarationLogService;
    @Mock
    private SbmClientService sbmClientService;
    @Mock
    private ProcessMapper processMapper;

    @Captor
    private ArgumentCaptor<List<Long>> groupIdsCaptor;

    private DeclarationService service;

    @BeforeEach
    void setUp() {
        SbmProperties sbmProperties = new SbmProperties();
        sbmProperties.setCompanyCode("045");
        JsonUtil jsonUtil = new JsonUtil(new ObjectMapper().registerModule(new JavaTimeModule()));

        service = new DeclarationService(declarationProcessRepository, declarationGroupProcessor,
                declarationLogService, sbmClientService, new SbmMapper(), processMapper,
                sbmProperties, jsonUtil);

        when(declarationGroupProcessor.process(any(), anyBoolean(), anyList(), any(RequestContext.class)))
                .thenReturn(Optional.empty());
    }

    // --- grouping -------------------------------------------------------------------------

    @Test
    @DisplayName("rows are folded into one SBM request per İl-İlçe-Yıl-Ay")
    void send_groupsRowsIntoOneRequestPerDeclaration() {
        DeclarationProcess adanaMenkul = cityLevelRow(1L, MovableType.MENKUL);
        DeclarationProcess adanaGayri = cityLevelRow(2L, MovableType.GAYRIMENKUL);
        DeclarationProcess adiyamanMenkul = districtRow(3L, MovableType.MENKUL);
        adiyamanMenkul.setSbmFileNo("YSV202513492");
        when(declarationProcessRepository.findCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(adanaMenkul, adanaGayri, adiyamanMenkul));

        BatchOperationResponse response =
                service.send(new DeclarationFilterRequest(2026, 1, null, null), CTX);

        assertThat(response.totalGroups()).isEqualTo(2);
        assertThat(response.successCount()).isEqualTo(2);
        assertThat(response.failCount()).isZero();
        assertThat(response.failures()).isEmpty();

        verify(declarationGroupProcessor, org.mockito.Mockito.times(2))
                .process(eq(OperationType.POST), eq(false), groupIdsCaptor.capture(), eq(CTX));
        assertThat(groupIdsCaptor.getAllValues()).containsExactly(List.of(1L, 2L), List.of(3L));
    }

    @Test
    @DisplayName("ysvDosyaNo is not part of the key: one İl-İlçe-Yıl-Ay stays one request")
    void send_differentFileNumbersStillFormOneGroup() {
        DeclarationProcess menkul = cityLevelRow(1L, MovableType.MENKUL);
        DeclarationProcess gayrimenkul = cityLevelRow(2L, MovableType.GAYRIMENKUL);
        gayrimenkul.setSbmFileNo("YSV202599999");
        when(declarationProcessRepository.findCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(menkul, gayrimenkul));

        BatchOperationResponse response = service.send(null, CTX);

        assertThat(response.totalGroups()).isEqualTo(1);
        verify(declarationGroupProcessor).process(OperationType.POST, false, List.of(1L, 2L), CTX);
    }

    @Test
    @DisplayName("DISTRICT_CODE 0 and null land in the same group: both mean \"no district\"")
    void send_rowsWithoutADistrictFormOneGroup() {
        DeclarationProcess zeroDistrict = districtRow(1L, MovableType.MENKUL);
        zeroDistrict.setDistrictCode(0);
        DeclarationProcess nullDistrict = districtRow(2L, MovableType.GAYRIMENKUL);
        nullDistrict.setDistrictCode(null);
        when(declarationProcessRepository.findCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(zeroDistrict, nullDistrict));

        BatchOperationResponse response = service.send(null, CTX);

        assertThat(response.totalGroups()).isEqualTo(1);
        verify(declarationGroupProcessor).process(OperationType.POST, false, List.of(1L, 2L), CTX);
    }

    @Test
    @DisplayName("a district level row is never folded into the city level one")
    void send_districtAndCityLevelRowsAreSeparateGroups() {
        DeclarationProcess cityLevel = cityLevelRow(1L, MovableType.MENKUL);
        DeclarationProcess withDistrict = cityLevelRow(2L, MovableType.GAYRIMENKUL);
        withDistrict.setDistrictCode(1707);
        when(declarationProcessRepository.findCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(cityLevel, withDistrict));

        assertThat(service.send(null, CTX).totalGroups()).isEqualTo(2);
    }

    @Test
    @DisplayName("different districts stay separate declarations")
    void send_differentDistrictsAreSeparateGroups() {
        DeclarationProcess first = districtRow(1L, MovableType.MENKUL);
        DeclarationProcess second = districtRow(2L, MovableType.MENKUL);
        second.setDistrictCode(1105);
        when(declarationProcessRepository.findCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(first, second));

        assertThat(service.send(null, CTX).totalGroups()).isEqualTo(2);
    }

    @Test
    void send_usesTheSendableStatusesAndTheGivenFilter() {
        when(declarationProcessRepository.findCandidates(any(), any(), any(), any()))
                .thenReturn(List.of());

        service.send(new DeclarationFilterRequest(2026, 1, 34, List.of()), CTX);

        verify(declarationProcessRepository)
                .findCandidates(ProcessStatus.SENDABLE, 2026, 1, 34);
    }

    @Test
    @DisplayName("an explicit ysvDosyaNoList bypasses the year/month/city filter")
    void send_withFileNos_selectsByFileNoOnly() {
        when(declarationProcessRepository.findCandidatesByFileNos(any(), any())).thenReturn(List.of());

        service.send(new DeclarationFilterRequest(2026, 1, 34, List.of("YSV1", "YSV2")), CTX);

        verify(declarationProcessRepository)
                .findCandidatesByFileNos(List.of("YSV1", "YSV2"), ProcessStatus.SENDABLE);
        verify(declarationProcessRepository, never()).findCandidates(any(), any(), any(), any());
    }

    @Test
    void send_withoutFilter_selectsEveryEligibleRow() {
        when(declarationProcessRepository.findCandidates(any(), any(), any(), any()))
                .thenReturn(List.of());

        service.send(null, CTX);

        verify(declarationProcessRepository)
                .findCandidates(ProcessStatus.SENDABLE, null, null, null);
    }

    @Test
    void update_usesPutAndTheUpdatableStatuses() {
        when(declarationProcessRepository.findCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(cityLevelRow(1L, MovableType.MENKUL)));

        service.update(new DeclarationFilterRequest(2026, 1, null, null), CTX);

        verify(declarationProcessRepository)
                .findCandidates(ProcessStatus.UPDATABLE, 2026, 1, null);
        verify(declarationGroupProcessor).process(OperationType.PUT, false, List.of(1L), CTX);
    }

    @Test
    @DisplayName("cancel is a PUT with zeroed amounts: SBM has no delete operation")
    void cancel_usesPutWithZeroedAmounts() {
        when(declarationProcessRepository.findCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(cityLevelRow(1L, MovableType.MENKUL)));

        service.cancel(new DeclarationFilterRequest(null, null, null, null), CTX);

        verify(declarationGroupProcessor).process(OperationType.PUT, true, List.of(1L), CTX);
    }

    @Test
    void send_countsFailuresPerGroup() {
        when(declarationProcessRepository.findCandidates(any(), any(), any(), any()))
                .thenReturn(List.of(cityLevelRow(1L, MovableType.MENKUL)));
        when(declarationGroupProcessor.process(any(), anyBoolean(), anyList(), any(RequestContext.class)))
                .thenReturn(Optional.of(new FailureDetail("YSV202513491", "CORE-01004", "hata")));

        BatchOperationResponse response = service.send(null, CTX);

        assertThat(response.totalGroups()).isEqualTo(1);
        assertThat(response.successCount()).isZero();
        assertThat(response.failCount()).isEqualTo(1);
        assertThat(response.failures()).singleElement()
                .satisfies(failure -> assertThat(failure.errorCode()).isEqualTo("CORE-01004"));
    }

    @Test
    void send_withoutCandidates_returnsAnEmptyResult() {
        when(declarationProcessRepository.findCandidates(any(), any(), any(), any()))
                .thenReturn(List.of());

        BatchOperationResponse response = service.send(null, CTX);

        assertThat(response.totalGroups()).isZero();
        assertThat(response.failures()).isEmpty();
        verify(declarationGroupProcessor, never()).process(any(), anyBoolean(), anyList(), any(RequestContext.class));
    }

    // --- tekli islemler ---------------------------------------------------------------------

    @Test
    @DisplayName("single send of an unknown file number is 404, nothing is sent")
    void sendOne_unknownFileNo_isNotFound() {
        when(declarationProcessRepository.findBySbmFileNo("YOK")).thenReturn(List.of());

        assertThatThrownBy(() -> service.sendOne("YOK", CTX))
                .isInstanceOf(DeclarationNotFoundException.class)
                .hasMessageContaining("YOK");
        verify(declarationGroupProcessor, never()).process(any(), anyBoolean(), anyList(), any(RequestContext.class));
    }

    @Test
    @DisplayName("single send of an already sent declaration reports a status conflict")
    void sendOne_notSendable_reportsConflict() {
        DeclarationProcess sent = cityLevelRow(1L, MovableType.MENKUL);
        sent.setStatus(ProcessStatus.SENT);
        when(declarationProcessRepository.findBySbmFileNo("YSV202513491")).thenReturn(List.of(sent));
        when(declarationProcessRepository.findCandidatesByFileNos(any(), any())).thenReturn(List.of());

        BatchOperationResponse response = service.sendOne("YSV202513491", CTX);

        assertThat(response.failCount()).isEqualTo(1);
        assertThat(response.failures()).singleElement().satisfies(f -> {
            assertThat(f.errorCode()).isEqualTo("ALZ-STATUS-CONFLICT");
            assertThat(f.message()).contains("SENT");
        });
    }

    @Test
    @DisplayName("single send selects the declaration by file number and POSTs it")
    void sendOne_postsTheDeclaration() {
        DeclarationProcess row = cityLevelRow(1L, MovableType.MENKUL);
        when(declarationProcessRepository.findBySbmFileNo("YSV202513491")).thenReturn(List.of(row));
        when(declarationProcessRepository.findCandidatesByFileNos(List.of("YSV202513491"), ProcessStatus.SENDABLE))
                .thenReturn(List.of(row));

        BatchOperationResponse response = service.sendOne("YSV202513491", CTX);

        assertThat(response.successCount()).isEqualTo(1);
        verify(declarationGroupProcessor).process(OperationType.POST, false, List.of(1L), CTX);
    }

    @Test
    @DisplayName("single cancel is a PUT with zeroed amounts on the updatable rows")
    void cancelOne_putsZeroedAmounts() {
        DeclarationProcess row = cityLevelRow(1L, MovableType.MENKUL);
        row.setStatus(ProcessStatus.SENT);
        when(declarationProcessRepository.findBySbmFileNo("YSV202513491")).thenReturn(List.of(row));
        when(declarationProcessRepository.findCandidatesByFileNos(List.of("YSV202513491"), ProcessStatus.UPDATABLE))
                .thenReturn(List.of(row));

        service.cancelOne("YSV202513491", CTX);

        verify(declarationGroupProcessor).process(OperationType.PUT, true, List.of(1L), CTX);
    }

    private static DeclarationUpdateRequest updateBody() {
        return new DeclarationUpdateRequest(null, List.of(new DeclarationUpdateRequest.AmountLine(
                MovableType.MENKUL, java.math.BigDecimal.TEN, java.math.BigDecimal.ONE,
                java.math.BigDecimal.ONE, 10, java.math.BigDecimal.TEN, null)));
    }

    @Test
    @DisplayName("updating a declaration that never reached SBM only changes the database")
    void updateOne_notAtSbm_updatesDatabaseOnly() {
        DeclarationProcess row = cityLevelRow(1L, MovableType.MENKUL);
        when(declarationGroupProcessor.applyUpdate(eq("YSV202513491"), any(), eq(USER))).thenReturn(List.of(row));

        DeclarationUpdateResponse response = service.updateOne("YSV202513491", updateBody(), CTX);

        assertThat(response.sentToSbm()).isFalse();
        assertThat(response.success()).isTrue();
        assertThat(response.message()).contains("gönder");
        verify(declarationGroupProcessor, never()).process(any(), anyBoolean(), anyList(), any(RequestContext.class));
    }

    @Test
    @DisplayName("updating a declaration already at SBM also PUTs it in the same call")
    void updateOne_atSbm_putsToSbm() {
        DeclarationProcess row = cityLevelRow(1L, MovableType.MENKUL);
        row.setStatus(ProcessStatus.SENT);
        when(declarationGroupProcessor.applyUpdate(eq("YSV202513491"), any(), eq(USER))).thenReturn(List.of(row));

        DeclarationUpdateResponse response = service.updateOne("YSV202513491", updateBody(), CTX);

        assertThat(response.sentToSbm()).isTrue();
        assertThat(response.success()).isTrue();
        assertThat(response.errorCode()).isNull();
        verify(declarationGroupProcessor).process(OperationType.PUT, false, List.of(1L), CTX);
    }

    @Test
    @DisplayName("an SBM rejection of the PUT is reported, the database keeps the new values")
    void updateOne_sbmRejects_reportsFailure() {
        DeclarationProcess row = cityLevelRow(1L, MovableType.MENKUL);
        row.setStatus(ProcessStatus.COMPLETED);
        when(declarationGroupProcessor.applyUpdate(eq("YSV202513491"), any(), eq(USER))).thenReturn(List.of(row));
        when(declarationGroupProcessor.process(any(), anyBoolean(), anyList(), any(RequestContext.class)))
                .thenReturn(Optional.of(new FailureDetail("YSV202513491", "CORE-01004", "red")));

        DeclarationUpdateResponse response = service.updateOne("YSV202513491", updateBody(), CTX);

        assertThat(response.sentToSbm()).isTrue();
        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("CORE-01004");
    }

    // --- toplu sorgu ------------------------------------------------------------------------

    @Test
    @DisplayName("bulk query asks SBM once per file number and promotes the confirmed ones")
    void queryBatch_queriesEachFileNoOnce() {
        DeclarationProcess menkul = cityLevelRow(1L, MovableType.MENKUL);
        DeclarationProcess gayri = cityLevelRow(2L, MovableType.GAYRIMENKUL);
        when(declarationProcessRepository.findCandidates(eq(ProcessStatus.QUERYABLE), any(), any(), any()))
                .thenReturn(List.of(menkul, gayri));
        when(sbmClientService.query(any(), any())).thenReturn(SbmCallResult.builder()
                .success(true).responsePayload("{\"result\":true,\"status\":200}").build());

        BatchOperationResponse response = service.queryBatch(new DeclarationFilterRequest(2026, 1, null, null), CTX);

        assertThat(response.totalGroups()).isEqualTo(1);
        assertThat(response.successCount()).isEqualTo(1);
        verify(sbmClientService, org.mockito.Mockito.times(1)).query(any(), eq(CTX));
        verify(declarationGroupProcessor).markCompleted(List.of(1L, 2L), USER);
    }

    @Test
    @DisplayName("a rejected file number in a bulk query is reported and the batch continues")
    void queryBatch_collectsFailures() {
        DeclarationProcess row = cityLevelRow(1L, MovableType.MENKUL);
        when(declarationProcessRepository.findCandidatesByFileNos(any(), any())).thenReturn(List.of(row));
        when(sbmClientService.query(any(), any())).thenReturn(SbmCallResult.builder()
                .success(false).httpStatus(422).errorCode("CORE-01001").errorMessage("Kayıt bulunamadı.").build());

        BatchOperationResponse response =
                service.queryBatch(new DeclarationFilterRequest(null, null, null, List.of("YSV202513491")), CTX);

        assertThat(response.failCount()).isEqualTo(1);
        assertThat(response.failures()).singleElement()
                .satisfies(f -> assertThat(f.errorCode()).isEqualTo("CORE-01001"));
        verify(declarationGroupProcessor, never()).markCompleted(anyCollection(), anyString());
    }

    @Test
    void queryBatch_withoutFilter_usesQueryableStatuses() {
        when(declarationProcessRepository.findCandidates(any(), any(), any(), any())).thenReturn(List.of());

        assertThat(service.queryBatch(null, CTX).totalGroups()).isZero();

        verify(declarationProcessRepository).findCandidates(ProcessStatus.QUERYABLE, null, null, null);
    }

    // --- query -----------------------------------------------------------------------------

    @Test
    @DisplayName("a confirmed declaration is promoted from SENT to COMPLETED")
    void query_success_promotesRowsToCompleted() {
        when(declarationProcessRepository.findBySbmFileNo("YSV202513491"))
                .thenReturn(List.of(cityLevelRow(1L, MovableType.MENKUL)));
        when(sbmClientService.query(any(), any())).thenReturn(SbmCallResult.builder()
                .success(true)
                .httpStatus(200)
                .requestPayload("{}")
                .responsePayload("{\"result\":true,\"status\":200,\"data\":{"
                        + "\"ysvDosyaNo\":\"YSV202513491\",\"sonOdemeTarihi\":\"2026-01-20\","
                        + "\"ysvTutarList\":[]}}")
                .build());

        SbmQueryResponse response = service.query("YSV202513491", CTX);

        assertThat(response.getResult()).isTrue();
        assertThat(response.getData().getYsvDosyaNo()).isEqualTo("YSV202513491");
        verify(declarationGroupProcessor).markCompleted(List.of(1L), USER);
    }

    @Test
    void query_withoutLocalRows_doesNotPromoteAnything() {
        when(declarationProcessRepository.findBySbmFileNo(anyString())).thenReturn(List.of());
        when(sbmClientService.query(any(), any())).thenReturn(SbmCallResult.builder()
                .success(true)
                .responsePayload("{\"result\":true,\"status\":200}")
                .build());

        assertThat(service.query("YSV202513491", CTX)).isNotNull();

        verify(declarationGroupProcessor, never()).markCompleted(anyCollection(), anyString());
    }

    @Test
    void query_rejectedBySbm_throws() {
        when(declarationProcessRepository.findBySbmFileNo(anyString())).thenReturn(List.of());
        when(sbmClientService.query(any(), any())).thenReturn(SbmCallResult.builder()
                .success(false)
                .httpStatus(422)
                .errorCode(SbmErrorCode.CORE_01001.getCode())
                .errorMessage("Kayıt bulunamadı.")
                .build());

        assertThatThrownBy(() -> service.query("YSV202513491", CTX))
                .isInstanceOf(SbmIntegrationException.class)
                .hasMessageContaining("Kayıt bulunamadı");
    }

    @Test
    void query_withUnparsableBody_throws() {
        when(declarationProcessRepository.findBySbmFileNo(anyString())).thenReturn(List.of());
        when(sbmClientService.query(any(), any())).thenReturn(SbmCallResult.builder()
                .success(true)
                .httpStatus(200)
                .responsePayload("not-json")
                .build());

        assertThatThrownBy(() -> service.query("YSV202513491", CTX))
                .isInstanceOf(SbmIntegrationException.class)
                .hasMessageContaining("çözümlenemedi");
    }

    @Test
    void query_alwaysWritesAnAuditRow() {
        when(declarationProcessRepository.findBySbmFileNo(anyString())).thenReturn(List.of());
        when(sbmClientService.query(any(), any())).thenReturn(SbmCallResult.builder()
                .success(true)
                .responsePayload("{\"result\":true}")
                .build());

        service.query("YSV202513491", CTX);

        verify(declarationLogService).logCall(eq(List.of()), eq(OperationType.GET), any(),
                anyString(), isNull(), anyString());
    }

    @Test
    void query_withoutFileNo_isRejectedBeforeAnyCall() {
        assertThatThrownBy(() -> service.query("  ", CTX))
                .isInstanceOf(SbmIntegrationException.class);
        verify(sbmClientService, never()).query(any(), any());
    }

    // --- listing ----------------------------------------------------------------------------

    @Test
    void search_mapsThePageToTheReadModel() {
        DeclarationProcess row = cityLevelRow(1L, MovableType.MENKUL);
        Pageable pageable = PageRequest.of(0, 20);
        Page<DeclarationProcess> page = new PageImpl<>(List.of(row), pageable, 1);
        when(declarationProcessRepository.search(any(), any(), any(), any(), any())).thenReturn(page);
        when(processMapper.toView(row)).thenReturn(new ProcessView(1L, 2026, 1, 1, 0,
                "YSV202513491", "MENKUL", "NEW", null, null, null, null, null, 10, null, null));

        PageResponse<ProcessView> response =
                service.search(ProcessStatus.NEW, 2026, 1, 1, pageable);

        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.content()).singleElement()
                .satisfies(view -> assertThat(view.sbmFileNo()).isEqualTo("YSV202513491"));
        verify(declarationProcessRepository).search(ProcessStatus.NEW, 2026, 1, 1, pageable);
    }
}
