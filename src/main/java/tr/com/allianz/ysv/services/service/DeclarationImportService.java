package tr.com.allianz.ysv.services.service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tr.com.allianz.ysv.services.config.SbmProperties;
import tr.com.allianz.ysv.services.dto.response.ExcelRowError;
import tr.com.allianz.ysv.services.dto.response.ImportResultResponse;
import tr.com.allianz.ysv.services.entity.DeclarationProcess;
import tr.com.allianz.ysv.services.enums.LogLevel;
import tr.com.allianz.ysv.services.enums.OperationType;
import tr.com.allianz.ysv.services.enums.ProcessStatus;
import tr.com.allianz.ysv.services.mapper.ProcessMapper;
import tr.com.allianz.ysv.services.repository.DeclarationProcessRepository;
import tr.com.allianz.ysv.services.service.ExcelDeclarationParser.ParsedRow;
import tr.com.allianz.ysv.services.service.ExcelDeclarationParser.ParsedSheet;
import tr.com.allianz.ysv.services.util.JsonUtil;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeclarationImportService {

    static final String DUPLICATE_CODE = "ALZ-EXCEL-DUPLICATE";
    static final String CONFLICT_CODE = "ALZ-EXCEL-CONFLICT";
    static final String BUSY_CODE = "ALZ-EXCEL-BUSY";

    private final ExcelDeclarationParser parser;
    private final DeclarationProcessRepository repository;
    private final SbmProperties sbmProperties;
    private final DeclarationLogService declarationLogService;
    private final ProcessMapper processMapper;
    private final JsonUtil jsonUtil;

    /**
     * Excel'i {@code ALZ_SBM_DECL_PROCESS}'e <b>upsert</b> eder; anahtar {@code ysvDosyaNo +
     * menkulTipi}. Dosya tek dönem içermelidir (değilse tüm dosya 400).
     *
     * <ul>
     *   <li>Satır DB'de varsa ve tutarlar/ödeme tarihi değiştiyse güncellenir ve öncesi/sonrası
     *       değerleriyle loglanır; {@code COMPLETED} → {@code SENT}. Değer aynıysa dokunulmaz.
     *       SBM'ye taşımak için dönen {@code updatedFileNos} ile {@code PUT /update} çağrılır.</li>
     *   <li>Satır DB'de yoksa {@code NEW} olarak eklenir.</li>
     *   <li>Beyannamenin kimliği (il/ilçe) değiştirilemez; gönderimdeki satır güncellenemez;
     *       başka dönemde kayıtlı dosya no ve SBM'ye gitmiş beyannameye yeni menkul tipi
     *       eklenemez — bunlar satır hatası olarak raporlanır.</li>
     *   <li>Aynı il/ilçe/dönemde DB'de ya da dosyada başka bir dosya no varsa yeni beyanname
     *       eklenmez (SBM yuva başına tek beyanname kabul eder; aynı gruba düşen iki beyannamenin
     *       satırları tek isteğe karışırdı).</li>
     * </ul>
     */
    @Transactional
    public ImportResultResponse importFile(MultipartFile file, String user) {
        ImportPlan plan = plan(file, true);

        LocalDateTime now = LocalDateTime.now();
        Map<Long, String> beforeById = new HashMap<>();
        List<DeclarationProcess> updated = new ArrayList<>(plan.updates().size());
        for (PlannedUpdate update : plan.updates()) {
            DeclarationProcess target = update.target();
            beforeById.put(target.getId(), jsonUtil.toJson(processMapper.toView(target)));
            apply(target, update.row(), now, user);
            updated.add(target);
        }
        List<DeclarationProcess> toInsert = plan.inserts().stream()
                .map(row -> toEntity(row, plan.fileName(), user))
                .toList();

        if (!plan.isEmpty()) {
            repository.saveAll(updated);
            repository.saveAll(toInsert);
        }
        for (DeclarationProcess row : updated) {
            declarationLogService.logCall(List.of(row.getId()), OperationType.LOCAL_UPDATE, LogLevel.INFO,
                    "Excel ile güncellendi (" + plan.fileName() + "). Dosya no: " + row.getSbmFileNo()
                            + ", menkul tipi: " + row.getMovableType() + ", kullanıcı: " + user,
                    beforeById.get(row.getId()), jsonUtil.toJson(processMapper.toView(row)));
        }

        ImportResultResponse result = plan.toResponse();
        log.info("Excel import: file={}, user={}, totalRows={}, inserted={}, updated={}, failed={}",
                plan.fileName(), user, result.totalRows(), result.inserted(), result.updated(), result.failed());
        return result;
    }

    /**
     * Yüklemeden önce kontrol: {@link #importFile} ile <b>aynı</b> kurallar çalışır, ama DB'ye
     * yazılmaz, log tablosuna kayıt düşülmez ve SBM çağrılmaz. Cevap, aynı dosya yüklenseydi
     * {@code upload}'ın döneceği sonuçtur (eklenecek / güncellenecek / hatalı satırlar).
     *
     * <p>Salt okunur transaction'da çalışır ve dönemin satırlarını kilitlemez. Plan DB'den okunan
     * nesneleri değiştirmediği için transaction sonunda DB'ye bir şey yazılmaz.</p>
     */
    @Transactional(readOnly = true)
    public ImportResultResponse validate(MultipartFile file) {
        ImportPlan plan = plan(file, false);
        ImportResultResponse result = plan.toResponse();
        log.info("Excel validate: file={}, totalRows={}, toInsert={}, toUpdate={}, failed={}",
                plan.fileName(), result.totalRows(), result.inserted(), result.updated(), result.failed());
        return result;
    }

    /**
     * Excel'i okuyup DB ile karşılaştırır; hangi satırın ekleneceğine, hangisinin güncelleneceğine
     * ve hangisinin hatalı olduğuna karar verir. <b>Hiçbir şey kaydetmez ve DB'den okunan
     * nesneleri değiştirmez</b> — güncellemeler {@link PlannedUpdate} olarak döner.
     *
     * @param lock {@code true} ise dönemin satırları kilitlenerek okunur (yükleme)
     */
    private ImportPlan plan(MultipartFile file, boolean lock) {
        String fileName = file.getOriginalFilename();
        ParsedSheet sheet;
        try (InputStream in = file.getInputStream()) {
            sheet = parser.parse(in);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Dosya akışı okunamadı.", ex);
        }

        List<ParsedRow> rows = sheet.rows();
        List<ExcelRowError> errors = new ArrayList<>(sheet.errors());
        int totalRows = rows.size() + sheet.errors().size();
        requireSinglePeriod(rows);
        List<ParsedRow> inserts = new ArrayList<>();
        List<PlannedUpdate> updates = new ArrayList<>();
        if (rows.isEmpty()) {
            return new ImportPlan(fileName, totalRows, inserts, updates, errors);
        }

        Integer year = rows.get(0).yil();
        Integer month = rows.get(0).ay();
        List<DeclarationProcess> period = lock
                ? repository.lockByPeriod(year, month)
                : repository.findByPeriod(year, month);
        Map<String, List<DeclarationProcess>> existingByFileNo = new HashMap<>();
        // SBM il-ilçe-dönem başına tek beyanname kabul eder (RISK-HAVUZU-00004): yuva -> dosya no
        Map<String, String> slotOwners = new HashMap<>();
        for (DeclarationProcess existing : period) {
            existingByFileNo.computeIfAbsent(existing.getSbmFileNo(), k -> new ArrayList<>()).add(existing);
            slotOwners.putIfAbsent(slotKey(existing.getCityCode(), existing.getDistrictCode()), existing.getSbmFileNo());
        }

        Set<String> seenKeys = new HashSet<>();
        for (ParsedRow row : rows) {
            if (!seenKeys.add(row.ysvDosyaNo() + "|" + row.menkulTipi())) {
                errors.add(error(row, DUPLICATE_CODE, "Dosyada aynı ysvDosyaNo + menkulTipi iki kez var."));
                continue;
            }
            List<DeclarationProcess> sameFileNo = existingByFileNo.getOrDefault(row.ysvDosyaNo(), List.of());
            DeclarationProcess match = sameFileNo.stream()
                    .filter(p -> p.getMovableType() == row.menkulTipi())
                    .findFirst()
                    .orElse(null);

            if (match != null) {
                String problem = updateProblem(match, row);
                if (problem != null) {
                    errors.add(error(row, problem.startsWith("Satır şu anda") ? BUSY_CODE : CONFLICT_CODE, problem));
                } else if (changes(match, row)) {
                    updates.add(new PlannedUpdate(match, row));
                }
                continue;
            }

            String problem = insertProblem(sameFileNo, row);
            String slot = slotKey(row.ilKodu(), row.ilceKodu());
            String owner = slotOwners.get(slot);
            if (problem == null && owner != null && !owner.equals(row.ysvDosyaNo())) {
                problem = "Bu il/ilçe/dönem için başka bir beyanname kayıtlı (Dosya no: " + owner
                        + "); il-ilçe-dönem başına tek beyanname olabilir.";
            }
            if (problem != null) {
                errors.add(error(row, CONFLICT_CODE, problem));
                continue;
            }
            slotOwners.putIfAbsent(slot, row.ysvDosyaNo());
            inserts.add(row);
        }
        return new ImportPlan(fileName, totalRows, inserts, updates, errors);
    }

    /** Değiştirilecek mevcut satır ve onu değiştirecek Excel satırı. */
    private record PlannedUpdate(DeclarationProcess target, ParsedRow row) {
    }

    /** {@link #plan} sonucu: yükleme bunu uygular, doğrulama yalnızca raporlar. */
    private record ImportPlan(String fileName,
                              int totalRows,
                              List<ParsedRow> inserts,
                              List<PlannedUpdate> updates,
                              List<ExcelRowError> errors) {

        boolean isEmpty() {
            return inserts.isEmpty() && updates.isEmpty();
        }

        ImportResultResponse toResponse() {
            Set<String> insertedFileNos = new LinkedHashSet<>();
            inserts.forEach(row -> insertedFileNos.add(row.ysvDosyaNo()));
            Set<String> updatedFileNos = new LinkedHashSet<>();
            updates.forEach(update -> updatedFileNos.add(update.row().ysvDosyaNo()));
            return ImportResultResponse.of(fileName, totalRows, inserts.size(), updates.size(),
                    List.copyOf(insertedFileNos), List.copyOf(updatedFileNos), errors);
        }
    }

    /** @return mevcut satır bu Excel satırıyla güncellenemiyorsa sebebi, yoksa {@code null} */
    private static String updateProblem(DeclarationProcess existing, ParsedRow row) {
        if (!Objects.equals(existing.getCityCode(), row.ilKodu())
                || !Objects.equals(normalizeDistrict(existing.getDistrictCode()), normalizeDistrict(row.ilceKodu()))) {
            return "ysvDosyaNo mevcut kayıtta farklı il/ilçe ile kayıtlı ("
                    + existing.getCityCode() + "/" + existing.getDistrictCode()
                    + "); beyannamenin kimliği değiştirilemez.";
        }
        if (existing.getStatus() == ProcessStatus.PROCESSING) {
            return "Satır şu anda SBM'ye gönderiliyor (PROCESSING), güncellenemez.";
        }
        return null;
    }

    /** @return bu Excel satırı eklenemiyorsa sebebi, yoksa {@code null} */
    private String insertProblem(List<DeclarationProcess> sameFileNoInPeriod, ParsedRow row) {
        if (sameFileNoInPeriod.isEmpty()) {
            return repository.existsBySbmFileNo(row.ysvDosyaNo())
                    ? "Bu ysvDosyaNo başka bir dönemde kayıtlı."
                    : null;
        }
        DeclarationProcess sibling = sameFileNoInPeriod.get(0);
        if (!Objects.equals(sibling.getCityCode(), row.ilKodu())
                || !Objects.equals(normalizeDistrict(sibling.getDistrictCode()), normalizeDistrict(row.ilceKodu()))) {
            return "ysvDosyaNo mevcut kayıtta farklı il/ilçe ile kayıtlı; beyannamenin kimliği değiştirilemez.";
        }
        boolean atSbm = sameFileNoInPeriod.stream()
                .anyMatch(p -> p.getStatus() != ProcessStatus.NEW && p.getStatus() != ProcessStatus.ERROR);
        return atSbm
                ? "Beyanname SBM'ye gönderilmiş; mevcut beyannameye yeni menkul tipi eklenemez."
                : null;
    }

    private static boolean changes(DeclarationProcess existing, ParsedRow row) {
        return !sameAmount(existing.getReceivedPremiumAmount(), row.alinanPrimTutari())
                || !sameAmount(existing.getCancelledPremiumAmount(), row.iptalPrimTutari())
                || !sameAmount(existing.getTaxAmount(), row.odenecekVergi())
                || !sameAmount(existing.getTaxPremiumAmount(), row.vergiPrimTutari())
                || !Objects.equals(existing.getTaxRatio(), row.vergiOrani())
                || !Objects.equals(existing.getPaymentDate(), row.sonOdemeTarihi())
                || (row.gecmisAyIadeTutari() != null
                        && !sameAmount(existing.getPrevMonthRefundAmount(), row.gecmisAyIadeTutari()));
    }

    private static void apply(DeclarationProcess existing, ParsedRow row, LocalDateTime now, String user) {
        existing.setReceivedPremiumAmount(row.alinanPrimTutari());
        existing.setCancelledPremiumAmount(row.iptalPrimTutari());
        existing.setTaxAmount(row.odenecekVergi());
        existing.setTaxPremiumAmount(row.vergiPrimTutari());
        existing.setTaxRatio(row.vergiOrani());
        existing.setPaymentDate(row.sonOdemeTarihi());
        if (row.gecmisAyIadeTutari() != null) {
            existing.setPrevMonthRefundAmount(row.gecmisAyIadeTutari());
        }
        if (existing.getStatus() == ProcessStatus.COMPLETED) {
            existing.setStatus(ProcessStatus.SENT);
        }
        existing.setDateUpdated(now);
        existing.setUpdatedByUser(user);
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    /** OPUS büyükşehiri 0 ile verir; DB'de null da olabilir — ikisi aynı anlamdadır. */
    private static String slotKey(Integer cityCode, Integer districtCode) {
        return cityCode + "/" + normalizeDistrict(districtCode);
    }

    private static Integer normalizeDistrict(Integer districtCode) {
        return districtCode == null ? Integer.valueOf(0) : districtCode;
    }

    private static ExcelRowError error(ParsedRow row, String code, String message) {
        return new ExcelRowError(row.rowNumber(), row.ysvDosyaNo(), code, message);
    }

    /**
     * Tek dosya = tek ay kuralı. Farklı (yıl, ay) karışımı varsa tüm dosya reddedilir.
     */
    private void requireSinglePeriod(List<ParsedRow> rows) {
        Set<String> periods = new LinkedHashSet<>();
        for (ParsedRow row : rows) {
            periods.add(row.yil() + "-" + row.ay());
        }
        if (periods.size() > 1) {
            throw new IllegalArgumentException(
                    "Excel'de birden fazla dönem var (bir dosya tek yıl/ay içermeli): " + periods);
        }
    }

    private DeclarationProcess toEntity(ParsedRow row, String fileName, String user) {
        return DeclarationProcess.builder()
                .declarationMonth(row.ay())
                .declarationYear(row.yil())
                .cityCode(row.ilKodu())
                .districtCode(row.ilceKodu())
                .companyCode(sbmProperties.getCompanyCode())
                .paymentDate(row.sonOdemeTarihi())
                .sbmFileNo(row.ysvDosyaNo())
                .receivedPremiumAmount(row.alinanPrimTutari())
                .cancelledPremiumAmount(row.iptalPrimTutari())
                .prevMonthRefundAmount(row.gecmisAyIadeTutari())
                .movableType(row.menkulTipi())
                .taxAmount(row.odenecekVergi())
                .taxRatio(row.vergiOrani())
                .taxPremiumAmount(row.vergiPrimTutari())
                .status(ProcessStatus.NEW)
                .dateCreated(LocalDateTime.now())
                .createdByUser(user)
                .sourceFileName(fileName)
                .build();
    }
}
