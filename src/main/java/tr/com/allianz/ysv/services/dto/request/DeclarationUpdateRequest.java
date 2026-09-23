package tr.com.allianz.ysv.services.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import tr.com.allianz.ysv.services.enums.MovableType;

/**
 * Tekli güncelleme gövdesi — SBM'nin PUT gövdesiyle aynı yapıda. Beyanname {@code ysvDosyaNo}
 * ile (path'te), satırı {@code menkulTipi} ile bulunur.
 *
 * <p>Beyannamenin kimliğini kuran alanlar (yıl, ay, il, ilçe) burada yoktur ve
 * değiştirilemez: SBM de PUT'ta bu alanları kabul etmez. Listede olmayan menkul tipinin
 * tutarları değişmez.</p>
 */
@Schema(description = "Beyanname güncelleme (SBM PUT gövdesiyle aynı yapı)")
public record DeclarationUpdateRequest(

        @Schema(description = "Opsiyonel; verilirse beyannamenin tüm satırlarında güncellenir.",
                example = "2026-09-20")
        LocalDate sonOdemeTarihi,

        @NotEmpty @Size(max = 2) @Valid
        List<AmountLine> ysvTutarList) {

    @Schema(description = "Bir menkul tipine ait tutarlar")
    public record AmountLine(

            @Schema(example = "MENKUL")
            @NotNull
            MovableType menkulTipi,

            @Schema(example = "2000000.00") @NotNull @DecimalMin("0.00")
            BigDecimal alinanPrimTutari,

            @Schema(example = "50000.00") @NotNull @DecimalMin("0.00")
            BigDecimal iptalPrimTutari,

            @Schema(example = "195000.00", description = "Negatif olabilir (iptal edilen prim alınandan büyükse).")
            @NotNull
            BigDecimal odenecekVergi,

            @Schema(example = "10") @NotNull @Min(0) @Max(100)
            Integer vergiOrani,

            @Schema(example = "1950000.00", description = "Negatif olabilir (iptal edilen prim alınandan büyükse).")
            @NotNull
            BigDecimal vergiPrimTutari,

            @Schema(description = "Opsiyonel; negatif olabilir. Verilmezse mevcut değer korunur.")
            BigDecimal gecmisAyIadeTutari) {
    }
}
