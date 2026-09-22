package tr.com.allianz.ysv.services.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Toplu gönder / güncelle / sorgula / iptal filtresi. {@code ysvDosyaNoList} verilirse diğer
 * alanlar dikkate alınmaz.
 */
@Schema(description = "Beyanname toplu işlem filtresi")
public record DeclarationFilterRequest(

        @Schema(example = "2026")
        @Min(2000) @Max(2099)
        Integer year,

        @Schema(example = "8")
        @Min(1) @Max(12)
        Integer month,

        @Schema(example = "34")
        @Min(1) @Max(81)
        Integer cityCode,

        @Schema(description = "Belirtilirse diğer filtreler dikkate alınmaz. En fazla 1000 dosya no "
                + "(Oracle IN sınırı).", example = "[\"PENTEST260801\"]")
        @Size(max = 1000)
        List<@NotBlank @Size(max = 36) String> ysvDosyaNoList) {

    /** @return çağıran belirli dosya numaralarıyla sınırladıysa {@code true} */
    public boolean hasFileNos() {
        return ysvDosyaNoList != null && !ysvDosyaNoList.isEmpty();
    }

    public static DeclarationFilterRequest none() {
        return new DeclarationFilterRequest(null, null, null, null);
    }

    public static DeclarationFilterRequest ofFileNo(String ysvDosyaNo) {
        return new DeclarationFilterRequest(null, null, null, List.of(ysvDosyaNo));
    }
}
