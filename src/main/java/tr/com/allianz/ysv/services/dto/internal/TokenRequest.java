package tr.com.allianz.ysv.services.dto.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * {@code alz-token-management} isteği. Opsiyonel alanlar {@code null} ise hiç gönderilmez:
 * boş string gönderilirse token servisi {@code clientIdentityType} regex'i yüzünden 400 döner.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TokenRequest {

    private String clientName;

    /** Bu SBM çağrısının kimliği; SBM'ye de {@code Transaction-Id} başlığı olarak gider. */
    private String transactionId;

    private String functionName;

    private String companyCode;

    /** İşlemi yapanın kimlik tipi (1/2/4). Yoksa token servisi şirket VKN'sini döner. */
    private String clientIdentityType;

    private String clientIdentityNo;

    /** Token servisi loglarında hangi uygulama için üretildiğini gösterir. */
    private String externalServiceName;

    /** Token servisi loglarında hangi işlem için üretildiğini gösterir (POST/PUT/GET). */
    private String externalFunctionName;
}
