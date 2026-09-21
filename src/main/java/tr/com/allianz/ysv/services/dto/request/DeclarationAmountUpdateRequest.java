package tr.com.allianz.ysv.services.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Tek bir beyanname satırının tutarlarını güncellemek için gövde.
 *
 * <p>Prod DB'de manuel UPDATE yasak olduğundan düzeltme bu uçtan yapılır. Beyannamenin
 * kimliğini belirleyen alanlar (yıl, ay, il, ilçe, ysvDosyaNo, menkulTipi) burada
 * <b>değiştirilemez</b>; değişirlerse SBM'deki kayıtla bağ kopar.</p>
 */
@Schema(description = "Beyanname satırı tutar güncelleme")
public record DeclarationAmountUpdateRequest(

        @Schema(example = "1250000.00")
        @NotNull @DecimalMin("0.00")
        BigDecimal alinanPrimTutari,

        @Schema(example = "45000.00")
        @NotNull @DecimalMin("0.00")
        BigDecimal iptalPrimTutari,

        @Schema(example = "115500.00")
        @NotNull @DecimalMin("0.00")
        BigDecimal odenecekVergi,

        @Schema(example = "1155000.00")
        @NotNull @DecimalMin("0.00")
        BigDecimal vergiPrimTutari,

        @Schema(example = "10")
        @NotNull @Min(0) @Max(100)
        Integer vergiOrani,

        @Schema(description = "Opsiyonel; negatif olabilir. Gönderilmezse mevcut değer korunur.")
        BigDecimal gecmisAyIadeTutari,

        @Schema(description = "Opsiyonel; gönderilmezse mevcut son ödeme tarihi korunur.", example = "2026-09-20")
        LocalDate sonOdemeTarihi) {
}
