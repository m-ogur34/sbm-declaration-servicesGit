package tr.com.allianz.ysv.services.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tr.com.allianz.ysv.services.config.SbmProperties;
import tr.com.allianz.ysv.services.dto.internal.DeclarationGroupKey;
import tr.com.allianz.ysv.services.dto.internal.GroupOutcome;
import tr.com.allianz.ysv.services.dto.internal.SbmCallResult;
import tr.com.allianz.ysv.services.dto.internal.SbmQueryRequest;
import tr.com.allianz.ysv.services.dto.internal.SbmReply;
import tr.com.allianz.ysv.services.dto.request.DeclarationFilterRequest;
import tr.com.allianz.ysv.services.dto.request.DeclarationUpdateRequest;
import tr.com.allianz.ysv.services.dto.request.RequestContext;
import tr.com.allianz.ysv.services.dto.response.ApiResponse;
import tr.com.allianz.ysv.services.dto.response.BatchResult;
import tr.com.allianz.ysv.services.dto.response.PageResponse;
import tr.com.allianz.ysv.services.dto.response.ProcessView;
import tr.com.allianz.ysv.services.entity.DeclarationProcess;
import tr.com.allianz.ysv.services.enums.LogLevel;
import tr.com.allianz.ysv.services.enums.OperationType;
import tr.com.allianz.ysv.services.enums.ProcessStatus;
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
 * <p>Cevaplar SBM dokümanındaki yapıdadır ({@link ApiResponse}): tekli işlemler SBM'nin
 * cevabını <b>aynen</b> ve SBM'nin HTTP koduyla döner ({@link SbmReply}); toplu işlemler
 * aynı zarfın {@code data} bloğunda beyanname başına SBM cevabını döner ({@link BatchResult}).</p>
 *
 * <p>Batch bilerek transaction'sız: her grup {@link DeclarationGroupProcessor} içinde kendi
 * transaction'ında işlenir; sondaki bir hata SBM'nin kabul ettiği önceki grupları geri
 * almaz ve uzak çağrılar boyunca kilit tutulmaz.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeclarationService {

    private static final int OK = 200;
    private static final int CONFLICT = 409;
    private static final int UNPROCESSABLE = 422;

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
    public ApiResponse<BatchResult> send(DeclarationFilterRequest filter, RequestContext context) {
        return runBatch(filter, ProcessStatus.SENDABLE, OperationType.POST, false, context);
    }

    /** Filtreye uyan SENT / COMPLETED beyannamelerin DB'deki değerlerini SBM'ye PUT eder. */
    public ApiResponse<BatchResult> update(DeclarationFilterRequest filter, RequestContext context) {
        return runBatch(filter, ProcessStatus.UPDATABLE, OperationType.PUT, false, context);
    }

    /** SBM'de silme yoktur: iptal, tüm tutarların 0 ile güncellenmesidir (DB'de de 0 olur). */
    public ApiResponse<BatchResult> cancel(DeclarationFilterRequest filter, RequestContext context) {
        return runBatch(filter, ProcessStatus.UPDATABLE, OperationType.PUT, true, context);
    }

    /**
     * Filtreye uyan, SBM'de kaydı olan (SENT / COMPLETED) her beyannameyi sorgular; SBM
     * doğrularsa satırları COMPLETED yapar.
     */
    public ApiResponse<BatchResult> queryBatch(DeclarationFilterRequest filter, RequestContext context) {
        Map<String, List<Long>> byFileNo = new LinkedHashMap<>();
        for (DeclarationProcess process : fetchCandidates(safe(filter), ProcessStatus.QUERYABLE)) {
            byFileNo.computeIfAbsent(process.getSbmFileNo(), k -> new ArrayList<>()).add(process.getId());
        }
        log.info("GET batch started: {} declarations, user={}", byFileNo.size(), context.userName());
        List<GroupOutcome> outcomes = new ArrayList<>();
        byFileNo.forEach((fileNo, ids) -> {
            try {
                outcomes.add(querySbm(fileNo, ids, context));
            } catch (SbmIntegrationException ex) {
                outcomes.add(GroupOutcome.rejected(fileNo, UNPROCESSABLE, ex.getErrorCode(), ex.getMessage()));
            }
        });
        return batchResponse(outcomes, OperationType.GET);
    }

    // --- tekli -----------------------------------------------------------------------------

    public SbmReply sendOne(String ysvDosyaNo, RequestContext context) {
        return runOne(ysvDosyaNo, ProcessStatus.SENDABLE, OperationType.POST, false, context);
    }

    public SbmReply cancelOne(String ysvDosyaNo, RequestContext context) {
        return runOne(ysvDosyaNo, ProcessStatus.UPDATABLE, OperationType.PUT, true, context);
    }

    /**
     * Tekli güncelleme: yeni tutarlar DB'ye yazılır; beyanname SBM'ye daha önce gitmişse aynı
     * çağrıda PUT edilir ve SBM'nin cevabı aynen döner. Henüz gitmemişse (NEW / ERROR) yalnızca
     * DB güncellenir; SBM'ye "gönder" ile gider. SBM hata verirse DB yeni değerlerde kalır,
     * istek tekrar atılabilir.
     */
    public SbmReply updateOne(String ysvDosyaNo, DeclarationUpdateRequest request, RequestContext context) {
        List<DeclarationProcess> rows = declarationGroupProcessor.applyUpdate(ysvDosyaNo, request, context.userName());
        List<Long> ids = rows.stream().map(DeclarationProcess::getId).toList();
        boolean atSbm = rows.stream().allMatch(r -> r.getStatus().isUpdatable());
        if (!atSbm) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ysvDosyaNo", ysvDosyaNo);
            data.put("sentToSbm", false);
            data.put("message", "DB güncellendi. Beyanname henüz SBM'ye gönderilmediği için PUT yapılmadı; "
                    + "\"gönder\" ile gönderilebilir.");
            return new SbmReply(OK, jsonUtil.toTree(ApiResponse.ok(OK, data)));
        }
        return reply(declarationGroupProcessor.process(OperationType.PUT, false, ids, context));
    }

    /** Beyannameyi SBM'den sorgular; SBM'nin cevabı aynen döner, doğrulanırsa satırlar COMPLETED olur. */
    public SbmReply query(String ysvDosyaNo, RequestContext context) {
        List<Long> ids = declarationProcessRepository.findBySbmFileNo(ysvDosyaNo).stream()
                .map(DeclarationProcess::getId)
                .toList();
        return reply(querySbm(ysvDosyaNo, ids, context));
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

    /** @throws SbmIntegrationException dosya no SBM kurallarına uymuyorsa (SBM'ye gitmeden, HTTP 422) */
    private GroupOutcome querySbm(String ysvDosyaNo, List<Long> relatedIds, RequestContext context) {
        SbmQueryRequest request = sbmMapper.toQueryRequest(ysvDosyaNo, sbmProperties.getCompanyCode());
        SbmCallResult result = sbmClientService.query(request, context);
        declarationLogService.logCall(relatedIds, OperationType.GET,
                result.isSuccess() ? LogLevel.INFO : LogLevel.ERROR,
                DeclarationGroupProcessor.buildLogMessage(OperationType.GET, ysvDosyaNo, result, context.userName()),
                result.getRequestPayload(), result.getResponsePayload());
        if (result.isSuccess() && !relatedIds.isEmpty()) {
            declarationGroupProcessor.markCompleted(relatedIds, context.userName());
        }
        return GroupOutcome.fromCall(ysvDosyaNo, result);
    }

    /** Tekli işlem: dosya no DB'de yoksa 404; durum uygun değilse 409 (SBM'ye gitmez). */
    private SbmReply runOne(String ysvDosyaNo, Set<ProcessStatus> statuses,
                            OperationType operationType, boolean zeroAmounts, RequestContext context) {
        List<DeclarationProcess> rows = declarationProcessRepository.findBySbmFileNo(ysvDosyaNo);
        if (rows.isEmpty()) {
            throw new DeclarationNotFoundException("Beyanname bulunamadı: " + ysvDosyaNo);
        }
        List<DeclarationProcess> candidates =
                declarationProcessRepository.findCandidatesByFileNos(List.of(ysvDosyaNo), statuses);
        if (candidates.isEmpty()) {
            return reply(GroupOutcome.rejected(ysvDosyaNo, CONFLICT, DeclarationGroupProcessor.STATUS_CONFLICT_CODE,
                    "Beyannamenin durumu (" + rows.get(0).getStatus() + ") bu işlem için uygun değil."));
        }
        List<Long> ids = candidates.stream().map(DeclarationProcess::getId).toList();
        return reply(declarationGroupProcessor.process(operationType, zeroAmounts, ids, context));
    }

    private ApiResponse<BatchResult> runBatch(DeclarationFilterRequest filter,
                                             Set<ProcessStatus> statuses,
                                             OperationType operationType,
                                             boolean zeroAmounts,
                                             RequestContext context) {
        List<DeclarationProcess> candidates = fetchCandidates(safe(filter), statuses);
        Map<DeclarationGroupKey, List<Long>> groups = groupByDeclaration(candidates);
        log.info("{} batch started: {} rows, {} SBM requests, user={}",
                operationType, candidates.size(), groups.size(), context.userName());

        List<GroupOutcome> outcomes = new ArrayList<>();
        for (List<Long> ids : groups.values()) {
            outcomes.add(declarationGroupProcessor.process(operationType, zeroAmounts, ids, context));
        }
        return batchResponse(outcomes, operationType);
    }

    private ApiResponse<BatchResult> batchResponse(List<GroupOutcome> outcomes, OperationType operationType) {
        List<JsonNode> results = new ArrayList<>(outcomes.size());
        int success = 0;
        for (GroupOutcome outcome : outcomes) {
            results.add(item(outcome));
            if (outcome.success()) {
                success++;
            }
        }
        int failed = outcomes.size() - success;
        log.info("{} batch finished: {} groups, {} failures", operationType, outcomes.size(), failed);
        return ApiResponse.of(failed == 0, OK, new BatchResult(outcomes.size(), success, failed, results));
    }

    /** SBM cevap verdiyse onun gövdesi aynen; vermediyse SBM'nin hata biçiminde bizim gövdemiz. */
    private SbmReply reply(GroupOutcome outcome) {
        return new SbmReply(outcome.httpStatus(), body(outcome));
    }

    private JsonNode body(GroupOutcome outcome) {
        JsonNode sbm = jsonUtil.readTree(outcome.sbmResponse());
        if (sbm != null && sbm.isObject()) {
            return sbm;
        }
        return jsonUtil.toTree(ApiResponse.failure(outcome.httpStatus(), outcome.errorCode(), outcome.message()));
    }

    /** Toplu sonuç elemanı: {@code ysvDosyaNo} + SBM'nin cevabı. */
    private JsonNode item(GroupOutcome outcome) {
        ObjectNode item = JsonNodeFactory.instance.objectNode();
        item.put("ysvDosyaNo", outcome.ysvDosyaNo());
        item.setAll((ObjectNode) body(outcome));
        return item;
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

    private static DeclarationFilterRequest safe(DeclarationFilterRequest filter) {
        return filter == null ? DeclarationFilterRequest.none() : filter;
    }
}
