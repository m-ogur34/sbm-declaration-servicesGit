package tr.com.allianz.ysv.services.dto.internal;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Token cevabındaki kimlik; SBM'ye {@code Requester-ID-Type/No} başlıkları olarak gider.
 *
 * <p>Token dokümanı alanı {@code clientIdentityNo} (tip String) olarak tanımlıyor; ortamdaki
 * mevcut sürüm {@code clientIdNumber} (tip sayı) dönüyor. İki ad da kabul edilir ki token
 * servisi güncellendiğinde çağrılar durmasın.</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClientCredentials {

    private String clientIdentityType;

    @JsonAlias("clientIdNumber")
    private String clientIdentityNo;
}
