package tr.com.allianz.ysv.services.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tr.com.allianz.ysv.services.enums.MovableType;
import tr.com.allianz.ysv.services.service.ExcelDeclarationParser.ParsedRow;
import tr.com.allianz.ysv.services.service.ExcelDeclarationParser.ParsedSheet;

class ExcelDeclarationParserTest {

    private static final String[] HEADERS = {
            "ay", "ilKodu", "ilceKodu", "sigortaSirketKodu", "sonOdemeTarihi", "yil", "ysvDosyaNo",
            "alinanPrimTutari", "iptalPrimTutari", "menkulTipi", "odenecekVergi", "vergiOrani",
            "vergiPrimTutari"};

    private final ExcelDeclarationParser parser = new ExcelDeclarationParser();

    @Test
    @DisplayName("bir satır: Excel seri tarih -> LocalDate, menkulTipi 1 -> MENKUL, tutarlar ölçek 2")
    void parse_singleRow_allConversions() throws IOException {
        byte[] xlsx = workbook(rows -> {
            Row row = rows.createRow(1);
            num(row, 0, 1);
            num(row, 1, 34);
            num(row, 2, 0);
            num(row, 3, 2320);
            serialDate(row, 4, LocalDate.of(2026, 1, 20));
            num(row, 5, 2026);
            text(row, 6, "YSV202513491");
            num(row, 7, 7453723.2199999997);
            num(row, 8, 15090.61);
            num(row, 9, 1);
            num(row, 10, 743863.26099999994);
            num(row, 11, 10);
            num(row, 12, 7438632.6099999994);
        });

        ParsedSheet sheet = parser.parse(new ByteArrayInputStream(xlsx));

        assertThat(sheet.errors()).isEmpty();
        assertThat(sheet.rows()).singleElement().satisfies(r -> {
            assertThat(r.ay()).isEqualTo(1);
            assertThat(r.ilKodu()).isEqualTo(34);
            assertThat(r.ilceKodu()).isZero();
            assertThat(r.yil()).isEqualTo(2026);
            assertThat(r.ysvDosyaNo()).isEqualTo("YSV202513491");
            assertThat(r.sonOdemeTarihi()).isEqualTo(LocalDate.of(2026, 1, 20));
            assertThat(r.menkulTipi()).isEqualTo(MovableType.MENKUL);
            assertThat(r.alinanPrimTutari()).isEqualByComparingTo(new BigDecimal("7453723.22"));
            assertThat(r.odenecekVergi()).isEqualByComparingTo(new BigDecimal("743863.26"));
            assertThat(r.vergiOrani()).isEqualTo(10);
            assertThat(r.gecmisAyIadeTutari()).isNull();
        });
    }

    @Test
    @DisplayName("opsiyonel gecmisAyIadeTutari kolonu okunur ve negatif olabilir; metin menkulTipi kabul edilir")
    void parse_optionalRefundColumnAndTextMovableType() throws IOException {
        String[] headers = new String[HEADERS.length + 1];
        System.arraycopy(HEADERS, 0, headers, 0, HEADERS.length);
        headers[HEADERS.length] = "gecmisAyIadeTutari";

        byte[] xlsx = workbook(headers, rows -> {
            Row row = rows.createRow(1);
            num(row, 0, 7);
            num(row, 1, 35);
            // ilceKodu boş
            text(row, 3, "2320");
            text(row, 4, "2026-08-31");
            num(row, 5, 2026);
            text(row, 6, "YSV-T-1");
            num(row, 7, 1);
            num(row, 8, 1);
            text(row, 9, "gayrimenkul");
            num(row, 10, 25000);
            num(row, 11, 10);
            num(row, 12, 250000);
            num(row, 13, -1100.5);
        });

        ParsedSheet sheet = parser.parse(new ByteArrayInputStream(xlsx));

        assertThat(sheet.errors()).isEmpty();
        ParsedRow r = sheet.rows().get(0);
        assertThat(r.ilceKodu()).isNull();
        assertThat(r.menkulTipi()).isEqualTo(MovableType.GAYRIMENKUL);
        assertThat(r.sonOdemeTarihi()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(r.gecmisAyIadeTutari()).isEqualByComparingTo(new BigDecimal("-1100.50"));
    }

    @Test
    @DisplayName("hatalı satır rapor edilir, sağlam satırlar okunmaya devam eder")
    void parse_badRowIsReportedButOthersSurvive() throws IOException {
        byte[] xlsx = workbook(rows -> {
            fullValidRow(rows.createRow(1), "YSV-OK");
            Row bad = rows.createRow(2);
            text(bad, 0, "abc");          // ay sayısal değil
            num(bad, 1, 34);
            text(bad, 4, "2026-08-31");
            num(bad, 5, 2026);
            text(bad, 6, "YSV-BAD");
            num(bad, 7, 1);
            num(bad, 8, 1);
            text(bad, 9, "MENKUL");
            num(bad, 10, 1);
            num(bad, 11, 10);
            num(bad, 12, 1);
        });

        ParsedSheet sheet = parser.parse(new ByteArrayInputStream(xlsx));

        assertThat(sheet.rows()).extracting(ParsedRow::ysvDosyaNo).containsExactly("YSV-OK");
        assertThat(sheet.errors()).singleElement().satisfies(e -> {
            assertThat(e.rowNumber()).isEqualTo(3);
            assertThat(e.ysvDosyaNo()).isEqualTo("YSV-BAD");
            assertThat(e.code()).isEqualTo("ALZ-EXCEL-FIELD");
            assertThat(e.message()).contains("ay");
        });
    }

    @Test
    @DisplayName("ysvDosyaNo yalnız harf, rakam, '-' ve '_' içerebilir (SBM sorgu URL'ine parametre eklenemesin)")
    void parse_fileNoWithUrlCharacters_isRowError() throws IOException {
        byte[] xlsx = workbook(rows -> {
            fullValidRow(rows.createRow(1), "YSV2027776");
            fullValidRow(rows.createRow(2), "X&sigortaSirketKodu=999");
            fullValidRow(rows.createRow(3), "YSV 1");
            fullValidRow(rows.createRow(4), "YSV_2027-A");
        });

        ParsedSheet sheet = parser.parse(new ByteArrayInputStream(xlsx));

        assertThat(sheet.rows()).extracting(ParsedRow::ysvDosyaNo).containsExactly("YSV2027776", "YSV_2027-A");
        assertThat(sheet.errors()).extracting(e -> e.rowNumber()).containsExactly(3, 4);
        assertThat(sheet.errors()).allSatisfy(e ->
                assertThat(e.message()).contains("yalnızca harf, rakam, '-' ve '_'"));
    }

    @Test
    void parse_missingRequiredHeader_rejectsWholeFile() throws IOException {
        String[] shortHeaders = {"ay", "ilKodu", "yil", "ysvDosyaNo"};
        byte[] xlsx = workbook(shortHeaders, rows -> rows.createRow(1).createCell(0).setCellValue(1));

        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(xlsx)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zorunlu kolon");
    }

    @Test
    void parse_notAnXlsx_isRejected() {
        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream("bu bir excel değil".getBytes())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("çeşitli alan hataları tek mesajda toplanır")
    void parse_fieldErrors_areAllReported() throws IOException {
        byte[] xlsx = workbook(rows -> {
            Row row = rows.createRow(1);
            text(row, 0, "");                       // ay boş
            text(row, 1, "x");                      // ilKodu sayısal değil
            text(row, 2, "y");                      // ilceKodu sayısal değil
            // sonOdemeTarihi hücresi hiç oluşturulmadı -> "boş olamaz"
            text(row, 5, "2026");
            text(row, 6, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"); // > 36
            text(row, 7, "abc");                    // alinanPrimTutari sayısal değil
            // iptalPrimTutari boş -> zorunlu
            num(row, 10, 1);
            text(row, 11, "z");                     // vergiOrani sayısal değil
            num(row, 12, 1);
            text(row, 9, "TASIT");                  // menkulTipi geçersiz
        });

        ParsedSheet sheet = parser.parse(new ByteArrayInputStream(xlsx));

        assertThat(sheet.rows()).isEmpty();
        String msg = sheet.errors().get(0).message();
        assertThat(msg).contains("ay boş", "ilKodu sayısal değil", "ilceKodu sayısal değil",
                "36 karakter", "sonOdemeTarihi boş olamaz", "alinanPrimTutari sayısal değil",
                "iptalPrimTutari boş", "vergiOrani sayısal değil", "menkulTipi");
    }

    @Test
    @DisplayName("ay 1-12 dışında ise satır reddedilir")
    void parse_monthOutOfRange() throws IOException {
        byte[] xlsx = workbook(rows -> {
            Row row = rows.createRow(1);
            num(row, 0, 13);
            num(row, 1, 34);
            text(row, 4, "2026-08-31");
            num(row, 5, 2026);
            text(row, 6, "YSV-M");
            num(row, 7, 1);
            num(row, 8, 1);
            text(row, 9, "MENKUL");
            num(row, 10, 1);
            num(row, 11, 10);
            num(row, 12, 1);
        });

        ParsedSheet sheet = parser.parse(new ByteArrayInputStream(xlsx));

        assertThat(sheet.errors()).singleElement()
                .satisfies(e -> assertThat(e.message()).contains("ay 1-12"));
    }

    @Test
    @DisplayName("sonOdemeTarihi seri sayı (tarih formatsız) ve dd.MM.yyyy metni de çözülür")
    void parse_dateAsSerialNumberAndDottedText() throws IOException {
        byte[] serial = workbook(rows -> {
            Row row = fullRowNoDate(rows.createRow(1), "YSV-S");
            num(row, 4, 46042); // 2026-01-20
        });
        assertThat(parser.parse(new ByteArrayInputStream(serial)).rows().get(0).sonOdemeTarihi())
                .isEqualTo(LocalDate.of(2026, 1, 20));

        byte[] dotted = workbook(rows -> {
            Row row = fullRowNoDate(rows.createRow(1), "YSV-D");
            text(row, 4, "20.01.2026");
        });
        assertThat(parser.parse(new ByteArrayInputStream(dotted)).rows().get(0).sonOdemeTarihi())
                .isEqualTo(LocalDate.of(2026, 1, 20));

        byte[] garbage = workbook(rows -> {
            Row row = fullRowNoDate(rows.createRow(1), "YSV-G");
            text(row, 4, "yirmi ocak");
        });
        assertThat(parser.parse(new ByteArrayInputStream(garbage)).errors()).singleElement()
                .satisfies(e -> assertThat(e.message()).contains("sonOdemeTarihi çözümlenemedi"));
    }

    @Test
    @DisplayName("boolean ve boş başlık hücreleri okuma sırasında sorun çıkarmaz")
    void parse_booleanCellAndBlankHeaderCell() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Sheet1");
            Row header = sheet.createRow(0);
            for (int c = 0; c < HEADERS.length; c++) {
                header.createCell(c).setCellValue(HEADERS[c]);
            }
            header.createCell(HEADERS.length).setBlank();               // boş başlık hücresi
            header.createCell(HEADERS.length + 1).setCellValue(true);   // boolean başlık hücresi -> "true"
            Row row = fullRowNoDate(sheet.createRow(1), "YSV-B");
            text(row, 4, "2026-08-31");
            wb.write(out);

            ParsedSheet parsed = parser.parse(new ByteArrayInputStream(out.toByteArray()));
            assertThat(parsed.rows()).hasSize(1);
        }
    }

    @Test
    void parse_blankRowsAreSkipped() throws IOException {
        byte[] xlsx = workbook(rows -> {
            fullValidRow(rows.createRow(1), "YSV-1");
            rows.createRow(2); // tamamen boş
            fullValidRow(rows.createRow(3), "YSV-2");
        });

        ParsedSheet sheet = parser.parse(new ByteArrayInputStream(xlsx));

        assertThat(sheet.rows()).extracting(ParsedRow::ysvDosyaNo).containsExactly("YSV-1", "YSV-2");
    }

    // --- workbook kurma yardımcıları ------------------------------------------------------

    private interface RowFiller {
        void fill(Sheet sheet);
    }

    private byte[] workbook(RowFiller filler) throws IOException {
        return workbook(HEADERS, filler);
    }

    private byte[] workbook(String[] headers, RowFiller filler) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Sheet2");
            Row header = sheet.createRow(0);
            for (int c = 0; c < headers.length; c++) {
                header.createCell(c).setCellValue(headers[c]);
            }
            filler.fill(sheet);
            wb.write(out);
            return out.toByteArray();
        }
    }

    private void fullValidRow(Row row, String fileNo) {
        fullRowNoDate(row, fileNo);
        text(row, 4, "2026-08-31");
    }

    /** 4. kolon (sonOdemeTarihi) hariç geçerli bir satır; tarihi test kendi kurar. */
    private Row fullRowNoDate(Row row, String fileNo) {
        num(row, 0, 8);
        num(row, 1, 34);
        num(row, 2, 0);
        text(row, 3, "2320");
        num(row, 5, 2026);
        text(row, 6, fileNo);
        num(row, 7, 1000);
        num(row, 8, 0);
        text(row, 9, "MENKUL");
        num(row, 10, 100);
        num(row, 11, 10);
        num(row, 12, 1000);
        return row;
    }

    private static void num(Row row, int col, double value) {
        row.createCell(col).setCellValue(value);
    }

    private static void text(Row row, int col, String value) {
        row.createCell(col).setCellValue(value);
    }

    private static void serialDate(Row row, int col, LocalDate date) {
        Cell cell = row.createCell(col);
        cell.setCellValue(date);
        CreationHelper helper = row.getSheet().getWorkbook().getCreationHelper();
        CellStyle style = row.getSheet().getWorkbook().createCellStyle();
        style.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));
        cell.setCellStyle(style);
    }
}
