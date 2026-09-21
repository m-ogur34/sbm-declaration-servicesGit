package tr.com.allianz.ysv.services.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
import tr.com.allianz.ysv.services.enums.ProcessStatus;
import tr.com.allianz.ysv.services.repository.DeclarationProcessRepository;
import tr.com.allianz.ysv.services.service.ExcelDeclarationParser.ParsedRow;
import tr.com.allianz.ysv.services.service.ExcelDeclarationParser.ParsedSheet;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeclarationImportService {

    private final ExcelDeclarationParser parser;
    private final DeclarationProcessRepository repository;
    private final SbmProperties sbmProperties;


    @Transactional
    public ImportResultResponse importFile(MultipartFile file, String user) {
        String fileName = file.getOriginalFilename();
        ParsedSheet sheet;
        try (InputStream in = file.getInputStream()) {
            sheet = parser.parse(in);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Dosya akışı okunamadı: " + ex.getMessage(), ex);
        }

        List<ParsedRow> rows = sheet.rows();
        List<ExcelRowError> errors = new ArrayList<>(sheet.errors());
        int totalRows = rows.size() + sheet.errors().size();

        requireSinglePeriod(rows);

        List<DeclarationProcess> toInsert = new ArrayList<>();
        for (ParsedRow row : rows) {
            if (repository.existsBySbmFileNo(row.ysvDosyaNo())) {
                errors.add(new ExcelRowError(row.rowNumber(), row.ysvDosyaNo(),
                        "ALZ-EXCEL-DUPLICATE",
                        "Bu ysvDosyaNo veritabanında zaten var: " + row.ysvDosyaNo()));
                continue;
            }
            toInsert.add(toEntity(row, fileName, user));
        }

        repository.saveAll(toInsert);
        log.info("Excel import: file={}, user={}, totalRows={}, inserted={}, failed={}",
                fileName, user, totalRows, toInsert.size(), errors.size());
        return ImportResultResponse.of(fileName, totalRows, toInsert.size(), errors);
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
