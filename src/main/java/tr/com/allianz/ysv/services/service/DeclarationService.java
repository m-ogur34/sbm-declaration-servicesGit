package tr.com.allianz.ysv.services.service;

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
import tr.com.allianz.ysv.services.dto.internal.SbmCallResult;
import tr.com.allianz.ysv.services.dto.internal.SbmQueryRequest;
import tr.com.allianz.ysv.services.dto.internal.SbmQueryResponse;
import tr.com.allianz.ysv.services.dto.request.DeclarationFilterRequest;
import tr.com.allianz.ysv.services.dto.response.BatchOperationResponse;
import tr.com.allianz.ysv.services.dto.response.FailureDetail;
import tr.com.allianz.ysv.services.dto.response.PageResponse;
import tr.com.allianz.ysv.services.dto.response.ProcessView;
import tr.com.allianz.ysv.services.entity.DeclarationProcess;
import tr.com.allianz.ysv.services.enums.LogLevel;
import tr.com.allianz.ysv.services.enums.OperationType;
import tr.com.allianz.ysv.services.enums.ProcessStatus;
import tr.com.allianz.ysv.services.enums.SbmErrorCode;
import tr.com.allianz.ysv.services.exception.SbmIntegrationException;
import tr.com.allianz.ysv.services.mapper.ProcessMapper;
import tr.com.allianz.ysv.services.mapper.SbmMapper;
import tr.com.allianz.ysv.services.repository.DeclarationProcessRepository;
import tr.com.allianz.ysv.services.util.JsonUtil;


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


    public BatchOperationResponse send(DeclarationFilterRequest filter, String user) {
        return runBatch(filter, ProcessStatus.SENDABLE, OperationType.POST, false, user);
    }


    public BatchOperationResponse update(DeclarationFilterRequest filter, String user) {
        return runBatch(filter, ProcessStatus.UPDATABLE, OperationType.PUT, false, user);
    }


    public BatchOperationResponse cancel(DeclarationFilterRequest filter, String user) {
        return runBatch(filter, ProcessStatus.UPDATABLE, OperationType.PUT, true, user);
    }

    public SbmQueryResponse query(String ysvDosyaNo, String user) {
        SbmQueryRequest request = sbmMapper.toQueryRequest(ysvDosyaNo, sbmProperties.getCompanyCode());
        List<Long> relatedIds = declarationProcessRepository.findBySbmFileNo(ysvDosyaNo).stream()
                .map(DeclarationProcess::getId)
                .toList();

        SbmCallResult result = sbmClientService.query(request);
        declarationLogService.logCall(relatedIds, OperationType.GET,
                result.isSuccess() ? LogLevel.INFO : LogLevel.ERROR,
                "GET " + ysvDosyaNo + (result.isSuccess() ? " başarılı" : " başarısız")
                        + " (HTTP " + result.getHttpStatus()
                        + ", Transaction-Id: " + result.getTransactionId() + ")",
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
            declarationGroupProcessor.markCompleted(relatedIds, user);
        }
        return response;
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

    private BatchOperationResponse runBatch(DeclarationFilterRequest filter,
                                            Set<ProcessStatus> statuses,
                                            OperationType operationType,
                                            boolean zeroAmounts,
                                            String user) {
        DeclarationFilterRequest safeFilter =
                filter == null ? new DeclarationFilterRequest(null, null, null, null) : filter;
        List<DeclarationProcess> candidates = fetchCandidates(safeFilter, statuses);
        Map<DeclarationGroupKey, List<Long>> groups = groupByDeclaration(candidates);

        log.info("{} batch started: {} rows, {} SBM requests, user={}",
                operationType, candidates.size(), groups.size(), user);

        List<FailureDetail> failures = new ArrayList<>();
        for (Map.Entry<DeclarationGroupKey, List<Long>> entry : groups.entrySet()) {
            declarationGroupProcessor
                    .process(operationType, zeroAmounts, entry.getValue(), user)
                    .ifPresent(failures::add);
        }

        log.info("{} batch finished: {} groups, {} failures", operationType, groups.size(), failures.size());
        return BatchOperationResponse.of(groups.size(), failures);
    }

    private List<DeclarationProcess> fetchCandidates(DeclarationFilterRequest filter,
                                                     Collection<ProcessStatus> statuses) {
        if (filter.hasProcessIds()) {
            return declarationProcessRepository.findCandidatesByIds(filter.processIds(), statuses);
        }
        return declarationProcessRepository.findCandidates(statuses,
                filter.year(), filter.month(), filter.cityCode());
    }

    private Map<DeclarationGroupKey, List<Long>> groupByDeclaration(List<DeclarationProcess> candidates) {
        Map<DeclarationGroupKey, List<Long>> groups = new LinkedHashMap<>();
        for (DeclarationProcess process : candidates) {
            groups.computeIfAbsent(DeclarationGroupKey.of(process), key -> new ArrayList<>())
                    .add(process.getId());
        }
        return groups;
    }
}
