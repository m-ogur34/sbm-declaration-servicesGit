package tr.com.allianz.ysv.services.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Toplu gönder / güncelle / sorgula / iptal filtresi. {@code ysvDosyaNoList} verilirse diğer
 * alanlar dikkate alınmaz. Ya {@code ysvDosyaNoList} ya da {@code year + month} zorunludur:
 * boş filtre ({@code {}}) tüm dönemlerin kayıtlarını işlerdi (ör. {@code /cancel} hepsini 0'lardı).
 */
@Schema(description = "Beyanname toplu işlem filtresi. ysvDosyaNoList ya da year + month zorunlu.")
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

    /** Bean Validation: dönem ya da dosya listesi yoksa istek 400 {@code ALZ-VALIDATION} ile reddedilir. */
    @JsonIgnore
    @AssertTrue(message = "year ve month birlikte ya da ysvDosyaNoList verilmelidir.")
    public boolean isFilter() {
        return hasFileNos() || (year != null && month != null);
    }

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
