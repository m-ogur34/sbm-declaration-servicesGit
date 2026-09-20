package tr.com.allianz.ysv.services.service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tr.com.allianz.ysv.services.config.SbmProperties;
import tr.com.allianz.ysv.services.dto.internal.SbmCallResult;
import tr.com.allianz.ysv.services.dto.internal.SbmDeclarationRequest;
import tr.com.allianz.ysv.services.dto.response.FailureDetail;
import tr.com.allianz.ysv.services.entity.DeclarationProcess;
import tr.com.allianz.ysv.services.enums.LogLevel;
import tr.com.allianz.ysv.services.enums.OperationType;
import tr.com.allianz.ysv.services.enums.ProcessStatus;
import tr.com.allianz.ysv.services.enums.SbmErrorCode;
import tr.com.allianz.ysv.services.exception.SbmIntegrationException;
import tr.com.allianz.ysv.services.exception.TokenException;
import tr.com.allianz.ysv.services.mapper.SbmMapper;
import tr.com.allianz.ysv.services.repository.DeclarationProcessRepository;
import tr.com.allianz.ysv.services.util.JsonUtil;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeclarationGroupProcessor {

    /** Reported when a row changed status between selection and locking. */
    static final String STATUS_CONFLICT_CODE = "ALZ-STATUS-CONFLICT";

    private final DeclarationProcessRepository declarationProcessRepository;
    private final DeclarationLogService declarationLogService;
    private final SbmClientService sbmClientService;
    private final SbmMapper sbmMapper;
    private final SbmProperties sbmProperties;

    @Transactional
    public Optional<FailureDetail> process(OperationType operationType,
                                           boolean zeroAmounts,
                                           List<Long> processIds,
                                           String user) {
        List<DeclarationProcess> group = declarationProcessRepository.lockByIds(processIds);
        if (group.isEmpty()) {
            return Optional.of(new FailureDetail(null, SbmErrorCode.CORE_01001.getCode(),
                    "Beyanname satırları bulunamadı: " + processIds));
        }

        String fileNo = group.get(0).getSbmFileNo();
        if (!statusAllows(operationType, group)) {
            log.warn("Declaration group {} skipped: status is not eligible for {}", fileNo, operationType);
            return Optional.of(new FailureDetail(fileNo, STATUS_CONFLICT_CODE,
                    "Kayıtların durumu bu işlem için uygun değil. Dosya no: " + fileNo));
        }

        markProcessing(group);

        try {
            SbmDeclarationRequest request = buildRequest(operationType, zeroAmounts, group);
            SbmCallResult result = operationType == OperationType.POST
                    ? sbmClientService.send(request)
                    : sbmClientService.update(request);

            declarationLogService.logCall(processIds, operationType,
                    result.isSuccess() ? LogLevel.INFO : LogLevel.ERROR,
                    buildLogMessage(operationType, fileNo, result),
                    result.getRequestPayload(), result.getResponsePayload());

            if (result.isSuccess()) {
                markSent(group, operationType, user);
                return Optional.empty();
            }

            markError(group, result.getErrorMessage(), user);
            return Optional.of(new FailureDetail(fileNo, result.getErrorCode(), result.getErrorMessage()));

        } catch (SbmIntegrationException ex) {
            // Pre-flight validation: nothing was sent to SBM.
            return fail(processIds, operationType, group, fileNo, ex.getErrorCode(), ex.getMessage(), user);
        } catch (TokenException ex) {
            return fail(processIds, operationType, group, fileNo,
                    SbmErrorCode.SEC_00001.getCode(), ex.getMessage(), user);
        }
    }


    @Transactional
    public void markCompleted(Collection<Long> processIds, String user) {
        List<DeclarationProcess> rows = declarationProcessRepository.lockByIds(processIds);
        LocalDateTime now = LocalDateTime.now();
        for (DeclarationProcess process : rows) {
            if (process.getStatus() == ProcessStatus.SENT) {
                process.setStatus(ProcessStatus.COMPLETED);
                process.setDateUpdated(now);
                process.setUpdatedByUser(user);
            }
        }
        declarationProcessRepository.saveAll(rows);
    }

    private SbmDeclarationRequest buildRequest(OperationType operationType,
                                               boolean zeroAmounts,
                                               List<DeclarationProcess> group) {
        String companyCode = sbmProperties.getCompanyCode();
        if (operationType == OperationType.POST) {
            return sbmMapper.toSendRequest(group, companyCode);
        }
        return sbmMapper.toUpdateRequest(group, companyCode, zeroAmounts);
    }

    private Optional<FailureDetail> fail(List<Long> processIds,
                                         OperationType operationType,
                                         List<DeclarationProcess> group,
                                         String fileNo,
                                         String errorCode,
                                         String message,
                                         String user) {
        log.error("Declaration group {} failed before/while calling SBM: {} - {}",
                fileNo, errorCode, message);
        declarationLogService.logCall(processIds, operationType, LogLevel.ERROR, message, null, null);
        markError(group, message, user);
        return Optional.of(new FailureDetail(fileNo, errorCode, message));
    }

    private boolean statusAllows(OperationType operationType, List<DeclarationProcess> group) {
        for (DeclarationProcess process : group) {
            ProcessStatus status = process.getStatus();
            if (status == null) {
                return false;
            }
            boolean allowed = operationType == OperationType.POST
                    ? status.isSendable()
                    : status.isUpdatable();
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private void markProcessing(List<DeclarationProcess> group) {
        for (DeclarationProcess process : group) {
            process.setStatus(ProcessStatus.PROCESSING);
        }
        declarationProcessRepository.saveAll(group);
    }

    private void markSent(List<DeclarationProcess> group, OperationType operationType, String user) {
        LocalDateTime now = LocalDateTime.now();
        for (DeclarationProcess process : group) {
            process.setStatus(ProcessStatus.SENT);
            process.setErrorDetails(null);
            if (operationType == OperationType.POST) {
                process.setDateSent(now);
                process.setSentByUser(user);
            } else {
                process.setDateUpdated(now);
                process.setUpdatedByUser(user);
            }
        }
        declarationProcessRepository.saveAll(group);
    }

    private void markError(List<DeclarationProcess> group, String message, String user) {
        LocalDateTime now = LocalDateTime.now();
        String details = JsonUtil.truncate(message, JsonUtil.ERROR_DETAILS_MAX_LENGTH);
        for (DeclarationProcess process : group) {
            process.setStatus(ProcessStatus.ERROR);
            process.setErrorDetails(details);
            process.setDateUpdated(now);
            process.setUpdatedByUser(user);
        }
        declarationProcessRepository.saveAll(group);
    }

    private String buildLogMessage(OperationType operationType, String fileNo, SbmCallResult result) {
        String outcome = result.isSuccess() ? "başarılı" : "başarısız";
        return operationType + " " + fileNo + " " + outcome
                + " (HTTP " + result.getHttpStatus() + ", Transaction-Id: " + result.getTransactionId() + ")";
    }
}
