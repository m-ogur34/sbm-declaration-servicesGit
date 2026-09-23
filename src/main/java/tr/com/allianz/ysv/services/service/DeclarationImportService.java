package tr.com.allianz.ysv.services.service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
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
import tr.com.allianz.ysv.services.enums.MovableType;
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
    /** Oracle'da bir IN listesine verilebilecek en fazla değer. */
    static final int IN_LIMIT = 1000;
    /** SBM'nin il/ilçe gerekçeli redleri: il yok, büyükşehirde ilçe, büyükşehir değilse ilçe yok, ilçe yok. */
    private static final Pattern LOCATION_REJECTION = Pattern.compile("RISK-HAVUZU-0000[6-9]");

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
            apply(target, update.row(), update.relocate(), now, user);
            updated.add(target);
        }
        List<DeclarationProcess> toInsert = plan.inserts().stream()
                .map(row -> toEntity(row, plan.fileName(), user))
                .toList();

        if (!plan.isEmpty()) {
            repository.saveAll(updated);
            repository.saveAll(toInsert);
        }
        Set<Long> relocatedIds = new HashSet<>();
        plan.updates().stream().filter(PlannedUpdate::relocate).forEach(u -> relocatedIds.add(u.target().getId()));
        for (DeclarationProcess row : updated) {
            declarationLogService.logCall(List.of(row.getId()), OperationType.LOCAL_UPDATE, LogLevel.INFO,
                    "Excel ile güncellendi" + (relocatedIds.contains(row.getId()) ? ", il/ilçe düzeltildi" : "")
                            + " (" + plan.fileName() + "). Dosya no: " + row.getSbmFileNo()
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

        Set<String> registeredElsewhere = registeredInOtherPeriods(rows, existingByFileNo.keySet());
        Map<String, String> relocationProblems = new HashMap<>();
        Set<String> relocated = planRelocations(rows, existingByFileNo, slotOwners, relocationProblems);

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

            boolean relocate = relocated.contains(row.ysvDosyaNo());
            if (match != null) {
                String problem = relocate ? null : updateProblem(match, row, relocationProblems);
                if (problem != null) {
                    errors.add(error(row, problem.startsWith("Satır şu anda") ? BUSY_CODE : CONFLICT_CODE, problem));
                } else if (relocate || changes(match, row)) {
                    updates.add(new PlannedUpdate(match, row, relocate));
                }
                continue;
            }

            String problem = insertProblem(sameFileNo, registeredElsewhere, relocate, row);
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

    /**
     * Değiştirilecek mevcut satır ve onu değiştirecek Excel satırı. {@code relocate}: satırın
     * il/ilçesi de Excel'dekiyle değişir (bkz. {@link #planRelocations}).
     */
    private record PlannedUpdate(DeclarationProcess target, ParsedRow row, boolean relocate) {
    }

    /**
     * SBM'ye hiç ulaşmamış bir beyannamenin il/ilçesinin Excel ile düzeltilmesi. Excel'de il/ilçesi
     * DB'dekinden farklı gelen her dosya no için, şu koşulların hepsi sağlanırsa dosya no döner:
     * <ol>
     *   <li>DB'deki tüm satırları {@code NEW} ya da SBM'nin il/ilçe gerekçesiyle
     *       ({@code RISK-HAVUZU-00006..00009}) reddettiği {@code ERROR} — yani SBM'de kaydı yok.
     *       Zaman aşımı / 5xx sonrası {@code ERROR} olan satır dahil değil: SBM kaydı almış olabilir.
     *       Aynı il/ilçe SBM'de reddedildiyse önceki denemelerde de kabul edilmiş olamaz.</li>
     *   <li>Beyanname bölünmüyor: DB'deki her menkul satırı Excel'de var ve Excel'deki tüm
     *       satırları aynı yeni il/ilçeyi taşıyor.</li>
     *   <li>Yeni il/ilçe/dönem yuvası başka bir dosya no'ya ait değil (RISK-HAVUZU-00004).</li>
     * </ol>
     * Kabul edilen dosya no için yuva sahipliği yeni yuvaya taşınır; reddedilenlerin sebebi
     * {@code problems}'a yazılır.
     */
    private static Set<String> planRelocations(List<ParsedRow> rows,
                                               Map<String, List<DeclarationProcess>> existingByFileNo,
                                               Map<String, String> slotOwners,
                                               Map<String, String> problems) {
        Map<String, List<ParsedRow>> excelByFileNo = new LinkedHashMap<>();
        for (ParsedRow row : rows) {
            if (existingByFileNo.containsKey(row.ysvDosyaNo())) {
                excelByFileNo.computeIfAbsent(row.ysvDosyaNo(), k -> new ArrayList<>()).add(row);
            }
        }
        Set<String> relocated = new HashSet<>();
        excelByFileNo.forEach((fileNo, excelRows) -> {
            List<DeclarationProcess> dbRows = existingByFileNo.get(fileNo);
            String oldSlot = slotKey(dbRows.get(0).getCityCode(), dbRows.get(0).getDistrictCode());
            Set<String> newSlots = new HashSet<>();
            excelRows.forEach(r -> newSlots.add(slotKey(r.ilKodu(), r.ilceKodu())));
            if (newSlots.size() != 1 || newSlots.contains(oldSlot)) {
                return;
            }
            String newSlot = newSlots.iterator().next();
            String owner = slotOwners.get(newSlot);
            Set<MovableType> inExcel = EnumSet.noneOf(MovableType.class);
            excelRows.forEach(r -> inExcel.add(r.menkulTipi()));
            if (!dbRows.stream().allMatch(DeclarationImportService::neverReachedSbm)) {
                problems.put(fileNo, "ysvDosyaNo mevcut kayıtta farklı il/ilçe ile kayıtlı ("
                        + dbRows.get(0).getCityCode() + "/" + dbRows.get(0).getDistrictCode()
                        + "); beyanname SBM'ye gönderilmiş olabileceği için il/ilçe değiştirilemez. "
                        + "Yalnız NEW ya da SBM'nin il/ilçe hatasıyla (RISK-HAVUZU-00006..00009) reddettiği "
                        + "beyannamenin il/ilçesi düzeltilebilir.");
            } else if (!dbRows.stream().allMatch(p -> inExcel.contains(p.getMovableType()))) {
                problems.put(fileNo, "İl/ilçe düzeltmesinde beyannamenin tüm menkul satırları aynı yeni "
                        + "il/ilçe ile Excel'de olmalı.");
            } else if (owner != null && !owner.equals(fileNo)) {
                problems.put(fileNo, "Yeni il/ilçe/dönem için başka bir beyanname kayıtlı (Dosya no: " + owner
                        + "); il-ilçe-dönem başına tek beyanname olabilir.");
            } else {
                relocated.add(fileNo);
                slotOwners.remove(oldSlot, fileNo);
                slotOwners.put(newSlot, fileNo);
            }
        });
        return relocated;
    }

    /** {@code NEW} ya da SBM'nin il/ilçe gerekçesiyle reddettiği {@code ERROR}: SBM'de kaydı yok. */
    private static boolean neverReachedSbm(DeclarationProcess process) {
        return process.getStatus() == ProcessStatus.NEW
                || (process.getStatus() == ProcessStatus.ERROR
                        && process.getErrorDetails() != null
                        && LOCATION_REJECTION.matcher(process.getErrorDetails()).find());
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
    private static String updateProblem(DeclarationProcess existing, ParsedRow row,
                                        Map<String, String> relocationProblems) {
        if (!Objects.equals(existing.getCityCode(), row.ilKodu())
                || !Objects.equals(normalizeDistrict(existing.getDistrictCode()), normalizeDistrict(row.ilceKodu()))) {
            String relocationProblem = relocationProblems.get(row.ysvDosyaNo());
            if (relocationProblem != null) {
                return relocationProblem;
            }
            return "ysvDosyaNo mevcut kayıtta farklı il/ilçe ile kayıtlı ("
                    + existing.getCityCode() + "/" + existing.getDistrictCode()
                    + "); beyannamenin kimliği değiştirilemez.";
        }
        if (existing.getStatus() == ProcessStatus.PROCESSING) {
            return "Satır şu anda SBM'ye gönderiliyor (PROCESSING), güncellenemez.";
        }
        return null;
    }

    /**
     * Dosyadaki, bu dönemde olmayan dosya numaralarından DB'de kayıtlı olanlar — yani başka bir
     * dönemde kayıtlı olanlar. Satır başına sorgu yerine 1000'lik gruplarla tek sorgu atılır
     * (Oracle IN sınırı).
     */
    private Set<String> registeredInOtherPeriods(List<ParsedRow> rows, Set<String> inPeriod) {
        List<String> candidates = rows.stream()
                .map(ParsedRow::ysvDosyaNo)
                .filter(fileNo -> !inPeriod.contains(fileNo))
                .distinct()
                .toList();
        Set<String> registered = new HashSet<>();
        for (int from = 0; from < candidates.size(); from += IN_LIMIT) {
            List<String> chunk = candidates.subList(from, Math.min(from + IN_LIMIT, candidates.size()));
            registered.addAll(repository.findExistingFileNos(chunk));
        }
        return registered;
    }

    /** @return bu Excel satırı eklenemiyorsa sebebi, yoksa {@code null} */
    private static String insertProblem(List<DeclarationProcess> sameFileNoInPeriod,
                                        Set<String> registeredElsewhere,
                                        boolean relocated,
                                        ParsedRow row) {
        if (sameFileNoInPeriod.isEmpty()) {
            return registeredElsewhere.contains(row.ysvDosyaNo())
                    ? "Bu ysvDosyaNo başka bir dönemde kayıtlı."
                    : null;
        }
        DeclarationProcess sibling = sameFileNoInPeriod.get(0);
        if (!relocated && (!Objects.equals(sibling.getCityCode(), row.ilKodu())
                || !Objects.equals(normalizeDistrict(sibling.getDistrictCode()), normalizeDistrict(row.ilceKodu())))) {
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

    private static void apply(DeclarationProcess existing, ParsedRow row, boolean relocate,
                              LocalDateTime now, String user) {
        if (relocate) {
            existing.setCityCode(row.ilKodu());
            existing.setDistrictCode(row.ilceKodu());
            existing.setStatus(ProcessStatus.NEW);
            existing.setErrorDetails(null);
        }
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
