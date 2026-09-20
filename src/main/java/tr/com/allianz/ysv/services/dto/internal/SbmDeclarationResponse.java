package tr.com.allianz.ysv.services.dto.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class SbmDeclarationResponse {

    private Boolean result;

    private Integer status;

    /** POST: {@code { "ysvDosyaNo": "..." }} — PUT: {@code true} — hata: yok. */
    private JsonNode data;

    private SbmError error;

    /**
     * @return POST cevabındaki {@code data.ysvDosyaNo}, yoksa {@code null}
     */
    public String extractYsvDosyaNo() {
        if (data != null && data.hasNonNull("ysvDosyaNo")) {
            return data.get("ysvDosyaNo").asText();
        }
        return null;
    }
}
