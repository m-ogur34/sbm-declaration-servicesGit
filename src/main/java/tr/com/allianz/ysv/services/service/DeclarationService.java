package tr.com.allianz.ysv.services.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tr.com.allianz.ysv.services.config.SbmProperties;
import tr.com.allianz.ysv.services.dto.internal.DeclarationGroupKey;
import tr.com.allianz.ysv.services.dto.internal.SbmCallResult;
import tr.com.allianz.ysv.services.dto.internal.SbmQueryRequest;
import tr.com.allianz.ysv.services.dto.internal.SbmQueryResponse;
import tr.com.allianz.ysv.services.dto.request.DeclarationFilterRequest;
import tr.com.allianz.ysv.services.dto.request.DeclarationUpdateRequest;
import tr.com.allianz.ysv.services.dto.request.RequestContext;
import tr.com.allianz.ysv.services.dto.response.BatchOperationResponse;
import tr.com.allianz.ysv.services.dto.response.DeclarationUpdateResponse;
import tr.com.allianz.ysv.services.dto.response.FailureDetail;
import tr.com.allianz.ysv.services.dto.response.PageResponse;
import tr.com.allianz.ysv.services.dto.response.ProcessView;
import tr.com.allianz.ysv.services.entity.DeclarationProcess;
import tr.com.allianz.ysv.services.enums.LogLevel;
import tr.com.allianz.ysv.services.enums.OperationType;
import tr.com.allianz.ysv.services.enums.ProcessStatus;
import tr.com.allianz.ysv.services.enums.SbmErrorCode;
import tr.com.allianz.ysv.services.exception.DeclarationNotFoundException;
import tr.com.allianz.ysv.services.exception.SbmIntegrationException;
import tr.com.allianz.ysv.services.mapper.ProcessMapper;
import tr.com.allianz.ysv.services.mapper.SbmMapper;
import tr.com.allianz.ysv.services.repository.DeclarationProcessRepository;
import tr.com.allianz.ysv.services.util.JsonUtil;

/**
 * Gönder / güncelle / sorgula / iptal işlemlerinin giriş noktası. Toplu işlemler filtreye
 * ({@link DeclarationFilterRequest}), tekli işlemler {@code ysvDosyaNo}'ya göre çalışır.
 *
 * <p>Batch bilerek transaction'sız: her grup {@link DeclarationGroupProcessor} içinde kendi
 * transaction'ında işlenir; sondaki bir hata SBM'nin kabul ettiği önceki grupları geri
 * almaz ve uzak çağrılar boyunca kilit tutulmaz.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeclarationService {

    private final DeclarationProcessRepository declarationProcessRepository;
    private final DeclarationGroupProcessor declarationGroupProcessor;
    private final DeclarationLogService declarationLogService;
    private final SbmClientService sbmClientService;
    private final SbmMapper sbmMapper;
    private final ProcessMapper processMapper;
    private final SbmProperties sbmProperties;
    private final JsonUtil jsonUtil;

    // --- toplu -----------------------------------------------------------------------------

    /** Filtreye uyan NEW / ERROR beyannameleri SBM'ye POST eder. */
    public BatchOperationResponse send(DeclarationFilterRequest filter, RequestContext context) {
        return runBatch(filter, ProcessStatus.SENDABLE, OperationType.POST, false, context);
    }

    /** Filtreye uyan SENT / COMPLETED beyannamelerin DB'deki değerlerini SBM'ye PUT eder. */
    public BatchOperationResponse update(DeclarationFilterRequest filter, RequestContext context) {
        return runBatch(filter, ProcessStatus.UPDATABLE, OperationType.PUT, false, context);
    }

    /** SBM'de silme yoktur: iptal, tüm tutarların 0 ile güncellenmesidir. */
    public BatchOperationResponse cancel(DeclarationFilterRequest filter, RequestContext context) {
        return runBatch(filter, ProcessStatus.UPDATABLE, OperationType.PUT, true, context);
    }

    /**
     * Filtreye uyan, SBM'de kaydı olan (SENT / COMPLETED) her beyannameyi sorgular; SBM
     * doğrularsa satırları COMPLETED yapar. Cevapta beyanname içerikleri dönmez (tekli sorgu
     * içindir), yalnızca kaçının doğrulandığı ve hatalar döner.
     */
    public BatchOperationResponse queryBatch(DeclarationFilterRequest filter, RequestContext context) {
        Map<String, List<Long>> byFileNo = new LinkedHashMap<>();
        for (DeclarationProcess process : fetchCandidates(safe(filter), ProcessStatus.QUERYABLE)) {
            byFileNo.computeIfAbsent(process.getSbmFileNo(), k -> new ArrayList<>()).add(process.getId());
        }
        log.info("GET batch started: {} declarations, user={}", byFileNo.size(), context.userName());

        List<FailureDetail> failures = new ArrayList<>();
        byFileNo.forEach((fileNo, ids) -> {
            try {
                querySbm(fileNo, ids, context);
            } catch (SbmIntegrationException ex) {
                failures.add(new FailureDetail(fileNo, ex.getErrorCode(), ex.getMessage()));
            }
        });
        log.info("GET batch finished: {} declarations, {} failures", byFileNo.size(), failures.size());
        return BatchOperationResponse.of(byFileNo.size(), failures);
    }

    // --- tekli -----------------------------------------------------------------------------

    public BatchOperationResponse sendOne(String ysvDosyaNo, RequestContext context) {
        return runOne(ysvDosyaNo, ProcessStatus.SENDABLE, OperationType.POST, false, context);
    }

    public BatchOperationResponse cancelOne(String ysvDosyaNo, RequestContext context) {
        return runOne(ysvDosyaNo, ProcessStatus.UPDATABLE, OperationType.PUT, true, context);
    }

    /**
     * Tekli güncelleme: yeni tutarlar DB'ye yazılır ve beyanname SBM'ye daha önce gitmişse
     * aynı çağrıda PUT edilir. Henüz gitmemişse (NEW / ERROR) yalnızca DB güncellenir; SBM'ye
     * "gönder" ile gider. SBM hata verirse DB yeni değerlerde kalır, istek tekrar atılabilir.
     */
    public DeclarationUpdateResponse updateOne(String ysvDosyaNo,
                                               DeclarationUpdateRequest request,
                                               RequestContext context) {
        List<DeclarationProcess> rows = declarationGroupProcessor.applyUpdate(ysvDosyaNo, request, context.userName());
        List<Long> ids = rows.stream().map(DeclarationProcess::getId).toList();
        boolean atSbm = rows.stream().allMatch(r -> r.getStatus().isUpdatable());

        if (!atSbm) {
            return new DeclarationUpdateResponse(ysvDosyaNo, false, true, null,
                    "DB güncellendi. Beyanname henüz SBM'ye gönderilmediği için PUT yapılmadı; "
                            + "\"gönder\" ile gönderilebilir.", views(ids));
        }
        Optional<FailureDetail> failure =
                declarationGroupProcessor.process(OperationType.PUT, false, ids, context);
        return new DeclarationUpdateResponse(ysvDosyaNo, true, failure.isEmpty(),
                failure.map(FailureDetail::errorCode).orElse(null),
                failure.map(FailureDetail::message).orElse("DB güncellendi ve SBM'ye gönderildi."),
                views(ids));
    }

    /**
     * Beyannameyi SBM'den sorgular; SBM doğrularsa satırları SENT → COMPLETED yapar.
     *
     * @throws SbmIntegrationException SBM reddederse ya da cevap çözümlenemezse
     */
    public SbmQueryResponse query(String ysvDosyaNo, RequestContext context) {
        List<Long> ids = declarationProcessRepository.findBySbmFileNo(ysvDosyaNo).stream()
                .map(DeclarationProcess::getId)
                .toList();
        return querySbm(ysvDosyaNo, ids, context);
    }

    @Transactional(readOnly = true)
    public PageResponse<ProcessView> search(ProcessStatus status,
                                            Integer year,
                                            Integer month,
                                            Integer cityCode,
                                            Pageable pageable) {
        Page<DeclarationProcess> page =
                declarationProcessRepository.search(status, year, month, cityCode, pageable);
        return PageResponse.from(page.map(processMapper::toView));
    }

    // --- iç ---------------------------------------------------------------------------------

    private SbmQueryResponse querySbm(String ysvDosyaNo, List<Long> relatedIds, RequestContext context) {
        SbmQueryRequest request = sbmMapper.toQueryRequest(ysvDosyaNo, sbmProperties.getCompanyCode());
        SbmCallResult result = sbmClientService.query(request, context);
        declarationLogService.logCall(relatedIds, OperationType.GET,
                result.isSuccess() ? LogLevel.INFO : LogLevel.ERROR,
                DeclarationGroupProcessor.buildLogMessage(OperationType.GET, ysvDosyaNo, result, context.userName()),
                result.getRequestPayload(), result.getResponsePayload());

        if (!result.isSuccess()) {
            throw new SbmIntegrationException(result.getErrorCode(),
                    JsonUtil.truncate(result.getErrorMessage(), JsonUtil.ERROR_DETAILS_MAX_LENGTH));
        }
        SbmQueryResponse response = jsonUtil.fromJson(result.getResponsePayload(), SbmQueryResponse.class);
        if (response == null) {
            throw new SbmIntegrationException(SbmErrorCode.CORE_00006.getCode(),
                    "SBM sorgu yanıtı çözümlenemedi. Dosya no: " + ysvDosyaNo);
        }
        if (!relatedIds.isEmpty()) {
            declarationGroupProcessor.markCompleted(relatedIds, context.userName());
        }
        return response;
    }

    /**
     * Tekli işlem: dosya no DB'de yoksa 404; varsa ama durumu işleme uygun değilse cevapta
     * {@code ALZ-STATUS-CONFLICT} döner (ör. zaten gönderilmiş bir beyannameyi tekrar göndermek).
     */
    private BatchOperationResponse runOne(String ysvDosyaNo, Set<ProcessStatus> statuses,
                                          OperationType operationType, boolean zeroAmounts,
                                          RequestContext context) {
        List<DeclarationProcess> rows = declarationProcessRepository.findBySbmFileNo(ysvDosyaNo);
        if (rows.isEmpty()) {
            throw new DeclarationNotFoundException("Beyanname bulunamadı: " + ysvDosyaNo);
        }
        BatchOperationResponse response = runBatch(DeclarationFilterRequest.ofFileNo(ysvDosyaNo),
                statuses, operationType, zeroAmounts, context);
        if (response.totalGroups() == 0) {
            return BatchOperationResponse.of(1, List.of(new FailureDetail(ysvDosyaNo,
                    DeclarationGroupProcessor.STATUS_CONFLICT_CODE,
                    "Beyannamenin durumu (" + rows.get(0).getStatus() + ") bu işlem için uygun değil.")));
        }
        return response;
    }

    private BatchOperationResponse runBatch(DeclarationFilterRequest filter,
                                            Set<ProcessStatus> statuses,
                                            OperationType operationType,
                                            boolean zeroAmounts,
                                            RequestContext context) {
        List<DeclarationProcess> candidates = fetchCandidates(safe(filter), statuses);
        Map<DeclarationGroupKey, List<Long>> groups = groupByDeclaration(candidates);

        log.info("{} batch started: {} rows, {} SBM requests, user={}",
                operationType, candidates.size(), groups.size(), context.userName());

        List<FailureDetail> failures = new ArrayList<>();
        for (List<Long> ids : groups.values()) {
            declarationGroupProcessor.process(operationType, zeroAmounts, ids, context)
                    .ifPresent(failures::add);
        }

        log.info("{} batch finished: {} groups, {} failures", operationType, groups.size(), failures.size());
        return BatchOperationResponse.of(groups.size(), failures);
    }

    private List<DeclarationProcess> fetchCandidates(DeclarationFilterRequest filter,
                                                     Collection<ProcessStatus> statuses) {
        if (filter.hasFileNos()) {
            return declarationProcessRepository.findCandidatesByFileNos(filter.ysvDosyaNoList(), statuses);
        }
        return declarationProcessRepository.findCandidates(statuses,
                filter.year(), filter.month(), filter.cityCode());
    }

    /**
     * SBM İl-İlçe-Yıl-Ay başına tek beyanname kabul eder (RISK-HAVUZU-00004); gruplama anahtarı
     * budur. Grubun satırları {@code ysvTutarList} elemanlarına dönüşür.
     */
    private static Map<DeclarationGroupKey, List<Long>> groupByDeclaration(List<DeclarationProcess> candidates) {
        Map<DeclarationGroupKey, List<Long>> groups = new LinkedHashMap<>();
        for (DeclarationProcess process : candidates) {
            groups.computeIfAbsent(DeclarationGroupKey.of(process), key -> new ArrayList<>())
                    .add(process.getId());
        }
        return groups;
    }

    private List<ProcessView> views(List<Long> ids) {
        return processMapper.toViews(declarationProcessRepository.findAllById(ids));
    }

    private static DeclarationFilterRequest safe(DeclarationFilterRequest filter) {
        return filter == null ? DeclarationFilterRequest.none() : filter;
    }
}
