package tr.com.allianz.ysv.services.dto.internal;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SbmQueryData {

    private Integer ay;

    private Integer yil;

    private Integer ilKodu;

    /** Büyükşehirlerde {@code null} döner. */
    private Integer ilceKodu;

    private String sigortaSirketKodu;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate sonOdemeTarihi;

    private String ysvDosyaNo;

    private List<SbmAmountItem> ysvTutarList;

    /** Dökümanda yok; SBM gerçek cevapta döndürüyor. */
    private String telefon;

    /** Dökümanda yok; SBM gerçek cevapta döndürüyor (Allianz VKN). */
    private String vkn;

    /** Dökümanda yok; SBM gerçek cevapta döndürüyor. */
    private String adres;

    /** Dökümanda yok; SBM gerçek cevapta döndürüyor (Allianz unvanı). */
    private String unvan;
}
