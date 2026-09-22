package tr.com.allianz.ysv.services.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tr.com.allianz.ysv.services.config.SbmProperties;
import tr.com.allianz.ysv.services.dto.internal.SbmCallResult;
import tr.com.allianz.ysv.services.dto.internal.SbmDeclarationRequest;
import tr.com.allianz.ysv.services.dto.request.DeclarationUpdateRequest;
import tr.com.allianz.ysv.services.dto.request.RequestContext;
import tr.com.allianz.ysv.services.dto.response.FailureDetail;
import tr.com.allianz.ysv.services.entity.DeclarationProcess;
import tr.com.allianz.ysv.services.enums.LogLevel;
import tr.com.allianz.ysv.services.enums.MovableType;
import tr.com.allianz.ysv.services.enums.OperationType;
import tr.com.allianz.ysv.services.enums.ProcessStatus;
import tr.com.allianz.ysv.services.enums.SbmErrorCode;
import tr.com.allianz.ysv.services.exception.DeclarationNotFoundException;
import tr.com.allianz.ysv.services.exception.SbmIntegrationException;
import tr.com.allianz.ysv.services.exception.TokenException;
import tr.com.allianz.ysv.services.mapper.ProcessMapper;
import tr.com.allianz.ysv.services.mapper.SbmMapper;
import tr.com.allianz.ysv.services.repository.DeclarationProcessRepository;
import tr.com.allianz.ysv.services.util.JsonUtil;

/**
 * Tek bir beyanname grubunu (tek SBM isteği) işler ve durum geçişlerinin sahibidir.
 *
 * <p>Her grup kendi transaction'ında, satır kilidiyle işlenir; aynı grubun iki kez
 * gönderilmesini kilit engeller. Batch'in kendisi transaction'sız
 * ({@link DeclarationService}), böylece uzun uzak çağrılar boyunca kilit tutulmaz.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeclarationGroupProcessor {

    /** Reported when a row changed status between selection and locking. */
    static final String STATUS_CONFLICT_CODE = "ALZ-STATUS-CONFLICT";

    private static final int AMOUNT_SCALE = 2;

    private final DeclarationProcessRepository declarationProcessRepository;
    private final DeclarationLogService declarationLogService;
    private final SbmClientService sbmClientService;
    private final SbmMapper sbmMapper;
    private final SbmProperties sbmProperties;
    private final ProcessMapper processMapper;
    private final JsonUtil jsonUtil;

    @Transactional
    public Optional<FailureDetail> process(OperationType operationType,
                                           boolean zeroAmounts,
                                           List<Long> processIds,
                                           RequestContext context) {
        List<DeclarationProcess> group = declarationProcessRepository.lockByIds(processIds);
        if (group.isEmpty()) {
            return Optional.of(new FailureDetail(null, SbmErrorCode.CORE_01001.getCode(),
                    "Beyanname satırları bulunamadı."));
        }

        String fileNo = group.get(0).getSbmFileNo();
        if (!statusAllows(operationType, group)) {
            log.warn("Declaration group {} skipped: status is not eligible for {}", fileNo, operationType);
            return Optional.of(new FailureDetail(fileNo, STATUS_CONFLICT_CODE,
                    "Kayıtların durumu bu işlem için uygun değil. Dosya no: " + fileNo));
        }

        List<ProcessStatus> previousStatuses = group.stream()
                .map(DeclarationProcess::getStatus)
                .toList();
        markProcessing(group);
        String user = context.userName();

        try {
            SbmDeclarationRequest request = buildRequest(operationType, zeroAmounts, group);
            SbmCallResult result = operationType == OperationType.POST
                    ? sbmClientService.send(request, context)
                    : sbmClientService.update(request, context);

            declarationLogService.logCall(processIds, operationType,
                    result.isSuccess() ? LogLevel.INFO : LogLevel.ERROR,
                    buildLogMessage(operationType, fileNo, result, user),
                    result.getRequestPayload(), result.getResponsePayload());

            if (result.isSuccess()) {
                markSent(group, operationType, user);
                return Optional.empty();
            }

            markFailure(operationType, group, previousStatuses,
                    result.getErrorCode(), result.getErrorMessage(), user);
            return Optional.of(new FailureDetail(fileNo, result.getErrorCode(), result.getErrorMessage()));

        } catch (SbmIntegrationException ex) {
            // Pre-flight validation: nothing was sent to SBM.
            return fail(processIds, operationType, group, previousStatuses, fileNo,
                    ex.getErrorCode(), ex.getMessage(), user);
        } catch (TokenException ex) {
            return fail(processIds, operationType, group, previousStatuses, fileNo,
                    SbmErrorCode.SEC_00001.getCode(), ex.getMessage(), user);
        }
    }

    /**
     * Tekli güncellemenin DB kısmı: beyannamenin satırlarını kilitler, yeni tutarları yazar ve
     * değişikliği öncesi/sonrası değerleriyle loglar. SBM'ye göndermez — çağıran
     * ({@link DeclarationService}) gerekiyorsa bu transaction bittikten sonra {@link #process}
     * ile ayrı bir transaction'da gönderir.
     *
     * <p>Beyanname SBM'ye daha önce gitmişse ({@code COMPLETED}) durum {@code SENT}'e çekilir:
     * yereldeki veri artık SBM'dekinden farklıdır ve yeniden gönderilip doğrulanmalıdır.</p>
     *
     * @return beyannamenin güncel satırları
     * @throws DeclarationNotFoundException dosya no DB'de yoksa (HTTP 404)
     * @throws IllegalArgumentException satır o anda gönderimdeyse ya da istekteki menkul tipi
     *         beyannamede yoksa / tekrarlıyorsa (HTTP 400)
     */
    @Transactional
    public List<DeclarationProcess> applyUpdate(String ysvDosyaNo,
                                                DeclarationUpdateRequest request,
                                                String user) {
        List<DeclarationProcess> rows = declarationProcessRepository.lockBySbmFileNo(ysvDosyaNo);
        if (rows.isEmpty()) {
            throw new DeclarationNotFoundException("Beyanname bulunamadı: " + ysvDosyaNo);
        }
        if (rows.stream().anyMatch(r -> r.getStatus() == ProcessStatus.PROCESSING)) {
            throw new IllegalArgumentException(
                    "Beyanname şu anda SBM'ye gönderiliyor (PROCESSING), güncellenemez: " + ysvDosyaNo);
        }

        Map<MovableType, DeclarationProcess> byType = new EnumMap<>(MovableType.class);
        rows.forEach(r -> byType.put(r.getMovableType(), r));
        Map<MovableType, DeclarationUpdateRequest.AmountLine> lines = new EnumMap<>(MovableType.class);
        for (DeclarationUpdateRequest.AmountLine line : request.ysvTutarList()) {
            if (lines.put(line.menkulTipi(), line) != null) {
                throw new IllegalArgumentException("ysvTutarList'te menkul tipi tekrarlanamaz: " + line.menkulTipi());
            }
            if (!byType.containsKey(line.menkulTipi())) {
                throw new IllegalArgumentException("Beyannamede " + line.menkulTipi()
                        + " satırı yok; güncelleme yeni menkul tipi ekleyemez. Dosya no: " + ysvDosyaNo);
            }
        }

        String before = jsonUtil.toJson(processMapper.toViews(rows));
        LocalDateTime now = LocalDateTime.now();
        for (DeclarationProcess row : rows) {
            DeclarationUpdateRequest.AmountLine line = lines.get(row.getMovableType());
            if (line != null) {
                row.setReceivedPremiumAmount(scaled(line.alinanPrimTutari()));
                row.setCancelledPremiumAmount(scaled(line.iptalPrimTutari()));
                row.setTaxAmount(scaled(line.odenecekVergi()));
                row.setTaxPremiumAmount(scaled(line.vergiPrimTutari()));
                row.setTaxRatio(line.vergiOrani());
                if (line.gecmisAyIadeTutari() != null) {
                    row.setPrevMonthRefundAmount(scaled(line.gecmisAyIadeTutari()));
                }
            }
            if (request.sonOdemeTarihi() != null) {
                row.setPaymentDate(request.sonOdemeTarihi());
            }
            if (row.getStatus() == ProcessStatus.COMPLETED) {
                row.setStatus(ProcessStatus.SENT);
            }
            row.setDateUpdated(now);
            row.setUpdatedByUser(user);
        }
        declarationProcessRepository.saveAll(rows);

        declarationLogService.logCall(rows.stream().map(DeclarationProcess::getId).toList(),
                OperationType.LOCAL_UPDATE, LogLevel.INFO,
                "Beyanname tutarları güncellendi. Dosya no: " + ysvDosyaNo + ", kullanıcı: " + user,
                before, jsonUtil.toJson(processMapper.toViews(rows)));
        log.info("Declaration {} amounts updated by {} ({} rows)", ysvDosyaNo, user, rows.size());
        return rows;
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
                                         List<ProcessStatus> previousStatuses,
                                         String fileNo,
                                         String errorCode,
                                         String message,
                                         String user) {
        log.error("Declaration group {} failed before/while calling SBM: {} - {}",
                fileNo, errorCode, message);
        declarationLogService.logCall(processIds, operationType, LogLevel.ERROR, message, null, null);
        markFailure(operationType, group, previousStatuses, errorCode, message, user);
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

    /**
     * POST hatası → {@code ERROR} (kayıt SBM'ye girmedi). PUT hatası → satır önceki durumunda
     * kalır; {@code ERROR} yazılsaydı sonraki "gönder" kaydı tekrar POST eder, SBM'de mükerrer
     * beyanname riski doğardı. {@code RISK-HAVUZU-00004} → beyanname SBM'de zaten var, satır
     * {@code SENT}'e alınır ki güncelleme ile yönetilebilsin.
     */
    private void markFailure(OperationType operationType,
                             List<DeclarationProcess> group,
                             List<ProcessStatus> previousStatuses,
                             String errorCode,
                             String message,
                             String user) {
        LocalDateTime now = LocalDateTime.now();
        String details = JsonUtil.truncate(message, JsonUtil.ERROR_DETAILS_MAX_LENGTH);
        boolean alreadyAtSbm = SbmErrorCode.RISK_HAVUZU_00004.getCode().equals(errorCode);
        for (int i = 0; i < group.size(); i++) {
            DeclarationProcess process = group.get(i);
            if (alreadyAtSbm) {
                process.setStatus(ProcessStatus.SENT);
            } else if (operationType == OperationType.POST) {
                process.setStatus(ProcessStatus.ERROR);
            } else {
                process.setStatus(previousStatuses.get(i));
            }
            process.setErrorDetails(details);
            process.setDateUpdated(now);
            process.setUpdatedByUser(user);
        }
        declarationProcessRepository.saveAll(group);
    }

    /**
     * {@code ALZ_SBM_DECL_LOG.LOG_MESSAGE}: SBM destek talebi için Transaction-Id, kimin adına
     * gönderildiği (kimlik maskeli) ve tetikleyen kullanıcı.
     */
    static String buildLogMessage(OperationType operationType, String fileNo, SbmCallResult result, String user) {
        String outcome = result.isSuccess() ? "başarılı" : "başarısız";
        return operationType + " " + fileNo + " " + outcome
                + " (HTTP " + result.getHttpStatus()
                + ", Transaction-Id: " + result.getTransactionId()
                + ", Requester: " + result.getRequesterIdType() + "/" + result.getRequesterIdNo()
                + ", kullanıcı: " + user + ")";
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value == null ? null : value.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }
}
