package tr.com.allianz.ysv.services.service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import tr.com.allianz.ysv.services.dto.response.ExcelRowError;
import tr.com.allianz.ysv.services.enums.MovableType;
import tr.com.allianz.ysv.services.mapper.SbmMapper;

@Slf4j
@Component
public class ExcelDeclarationParser {

    /** Bir dosyada işlenecek en fazla veri satırı (kaba kuvvet / bellek koruması). */
    static final int MAX_DATA_ROWS = 20_000;

    private static final Pattern FILE_NO = Pattern.compile(SbmMapper.YSV_DOSYA_NO_PATTERN);

    private static final int SCALE = 2;
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"));

    private static final String COL_AY = "ay";
    private static final String COL_IL = "ilkodu";
    private static final String COL_ILCE = "ilcekodu";
    private static final String COL_SIRKET = "sigortasirketkodu";
    private static final String COL_ODEME = "sonodemetarihi";
    private static final String COL_YIL = "yil";
    private static final String COL_DOSYA = "ysvdosyano";
    private static final String COL_ALINAN = "alinanprimtutari";
    private static final String COL_IPTAL = "iptalprimtutari";
    private static final String COL_MENKUL = "menkultipi";
    private static final String COL_VERGI = "odenecekvergi";
    private static final String COL_ORAN = "vergiorani";
    private static final String COL_VERGI_PRIM = "vergiprimtutari";
    private static final String COL_GECMIS = "gecmisayiadetutari";

    private static final List<String> REQUIRED_COLUMNS = List.of(
            COL_AY, COL_IL, COL_SIRKET, COL_ODEME, COL_YIL, COL_DOSYA,
            COL_ALINAN, COL_IPTAL, COL_MENKUL, COL_VERGI, COL_ORAN, COL_VERGI_PRIM);


    public ParsedSheet parse(InputStream in) {
        try (Workbook workbook = WorkbookFactory.create(in)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException("Excel dosyasında sheet bulunamadı.");
            }
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) {
                throw new IllegalArgumentException("Excel başlık satırı (1. satır) boş.");
            }
            Map<String, Integer> columns = readHeader(header);
            requireColumns(columns);

            List<ParsedRow> rows = new ArrayList<>();
            List<ExcelRowError> errors = new ArrayList<>();
            int dataRows = 0;

            for (int r = header.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (isBlank(row)) {
                    continue;
                }
                if (++dataRows > MAX_DATA_ROWS) {
                    throw new IllegalArgumentException(
                            "Excel'de en fazla " + MAX_DATA_ROWS + " veri satırı işlenebilir.");
                }
                parseRow(row, columns, rows, errors);
            }
            return new ParsedSheet(rows, errors);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Excel dosyası okunamadı: " + ex.getMessage(), ex);
        }
    }

    private Map<String, Integer> readHeader(Row header) {
        Map<String, Integer> columns = new HashMap<>();
        for (int c = header.getFirstCellNum(); c < header.getLastCellNum(); c++) {
            Cell cell = header.getCell(c);
            String name = asRawString(cell);
            if (name != null && !name.isBlank()) {
                columns.putIfAbsent(name.trim().toLowerCase(Locale.ROOT), c);
            }
        }
        return columns;
    }

    private void requireColumns(Map<String, Integer> columns) {
        List<String> missing = REQUIRED_COLUMNS.stream().filter(c -> !columns.containsKey(c)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "Excel başlık satırında zorunlu kolon(lar) eksik: " + String.join(", ", missing));
        }
    }

    private void parseRow(Row row, Map<String, Integer> columns,
                          List<ParsedRow> rows, List<ExcelRowError> errors) {
        int rowNumber = row.getRowNum() + 1;
        RowErrors err = new RowErrors();

        String ysvDosyaNo = trimToNull(readString(row, columns, COL_DOSYA));
        if (ysvDosyaNo == null) {
            err.add("ysvDosyaNo boş olamaz");
        } else if (ysvDosyaNo.length() > 36) {
            err.add("ysvDosyaNo en fazla 36 karakter olabilir");
        } else if (!FILE_NO.matcher(ysvDosyaNo).matches()) {
            err.add(SbmMapper.YSV_DOSYA_NO_MESSAGE);
        }

        Integer ay = readInt(row, columns, COL_AY, err, "ay");
        if (ay != null && (ay < 1 || ay > 12)) {
            err.add("ay 1-12 aralığında olmalı");
        }
        Integer ilKodu = readInt(row, columns, COL_IL, err, "ilKodu");
        Integer yil = readInt(row, columns, COL_YIL, err, "yil");
        Integer ilceKodu = readOptionalInt(row, columns, COL_ILCE, err);

        LocalDate sonOdemeTarihi = readDate(row, columns, err);

        MovableType menkulTipi = null;
        String menkulRaw = readString(row, columns, COL_MENKUL);
        try {
            menkulTipi = MovableType.fromExcel(menkulRaw);
        } catch (IllegalArgumentException ex) {
            err.add(ex.getMessage());
        }

        BigDecimal alinan = readAmount(row, columns, COL_ALINAN, err, "alinanPrimTutari", true);
        BigDecimal iptal = readAmount(row, columns, COL_IPTAL, err, "iptalPrimTutari", true);
        BigDecimal odenecek = readAmount(row, columns, COL_VERGI, err, "odenecekVergi", true);
        BigDecimal vergiPrim = readAmount(row, columns, COL_VERGI_PRIM, err, "vergiPrimTutari", true);
        Integer vergiOrani = readInt(row, columns, COL_ORAN, err, "vergiOrani");
        BigDecimal gecmisAyIade = readAmount(row, columns, COL_GECMIS, err, "gecmisAyIadeTutari", false);

        if (err.hasErrors()) {
            errors.add(new ExcelRowError(rowNumber, ysvDosyaNo, "ALZ-EXCEL-FIELD", err.message()));
            return;
        }
        rows.add(new ParsedRow(rowNumber, ay, ilKodu, ilceKodu, yil, ysvDosyaNo, sonOdemeTarihi,
                menkulTipi, alinan, iptal, odenecek, vergiOrani, vergiPrim, gecmisAyIade));
    }

    // ---- hücre okuma yardımcıları -------------------------------------------------

    private String readString(Row row, Map<String, Integer> columns, String column) {
        Integer idx = columns.get(column);
        return idx == null ? null : asRawString(row.getCell(idx));
    }

    private Integer readInt(Row row, Map<String, Integer> columns, String column,
                            RowErrors err, String label) {
        Integer idx = columns.get(column);
        String raw = idx == null ? null : asRawString(row.getCell(idx));
        if (trimToNull(raw) == null) {
            err.add(label + " boş olamaz");
            return null;
        }
        try {
            return Integer.valueOf(normalizeInt(raw));
        } catch (NumberFormatException ex) {
            err.add(label + " sayısal değil: " + raw);
            return null;
        }
    }

    private Integer readOptionalInt(Row row, Map<String, Integer> columns, String column, RowErrors err) {
        Integer idx = columns.get(column);
        if (idx == null) {
            return null;
        }
        String raw = trimToNull(asRawString(row.getCell(idx)));
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(normalizeInt(raw));
        } catch (NumberFormatException ex) {
            err.add("ilceKodu sayısal değil: " + raw);
            return null;
        }
    }

    private BigDecimal readAmount(Row row, Map<String, Integer> columns, String column,
                                 RowErrors err, String label, boolean required) {
        Integer idx = columns.get(column);
        String raw = idx == null ? null : trimToNull(asRawString(row.getCell(idx)));
        if (raw == null) {
            if (required) {
                err.add(label + " boş olamaz");
            }
            return null;
        }
        try {
            return new BigDecimal(raw.replace(',', '.')).setScale(SCALE, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            err.add(label + " sayısal değil: " + raw);
            return null;
        }
    }

    private LocalDate readDate(Row row, Map<String, Integer> columns, RowErrors err) {
        Integer idx = columns.get(COL_ODEME);
        Cell cell = idx == null ? null : row.getCell(idx);
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            err.add("sonOdemeTarihi boş olamaz");
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            double serial = cell.getNumericCellValue();
            if (DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toLocalDate();
            }
            return DateUtil.getLocalDateTime(serial).toLocalDate();
        }
        String text = trimToNull(asRawString(cell));
        if (text != null) {
            for (DateTimeFormatter fmt : DATE_FORMATS) {
                try {
                    return LocalDate.parse(text, fmt);
                } catch (Exception ignored) {
                    // sıradaki formatı dene
                }
            }
        }
        err.add("sonOdemeTarihi çözümlenemedi: " + text);
        return null;
    }

    private static String asRawString(Cell cell) {
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                yield BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            }
            case FORMULA -> {
                try {
                    yield cell.getStringCellValue();
                } catch (IllegalStateException ex) {
                    yield BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
                }
            }
            default -> null;
        };
    }

    private static String normalizeInt(String raw) {
        String value = raw.trim().replace(',', '.');
        int dot = value.indexOf('.');
        return dot >= 0 ? value.substring(0, dot) : value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isBlank(Row row) {
        if (row == null) {
            return true;
        }
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            if (trimToNull(asRawString(row.getCell(c))) != null) {
                return false;
            }
        }
        return true;
    }

    /** Bir satırın tüm alan hatalarını biriktirir. */
    private static final class RowErrors {
        private final List<String> messages = new ArrayList<>();

        void add(String message) {
            messages.add(message);
        }

        boolean hasErrors() {
            return !messages.isEmpty();
        }

        String message() {
            return String.join("; ", messages);
        }
    }

    /**
     * @param rows tipli, satır bazında doğrulanmış satırlar
     * @param errors satır bazlı hatalar (tip/format)
     */
    public record ParsedSheet(List<ParsedRow> rows, List<ExcelRowError> errors) {
    }

    /**
     * Excel'den okunmuş tek satır (tipleri çözülmüş). {@code sigortaSirketKodu} bilerek yok
     * — SBM'ye ve DB'ye daima {@code 045} gider.
     */
    public record ParsedRow(int rowNumber,
                            Integer ay,
                            Integer ilKodu,
                            Integer ilceKodu,
                            Integer yil,
                            String ysvDosyaNo,
                            LocalDate sonOdemeTarihi,
                            MovableType menkulTipi,
                            BigDecimal alinanPrimTutari,
                            BigDecimal iptalPrimTutari,
                            BigDecimal odenecekVergi,
                            Integer vergiOrani,
                            BigDecimal vergiPrimTutari,
                            BigDecimal gecmisAyIadeTutari) {
    }
}
