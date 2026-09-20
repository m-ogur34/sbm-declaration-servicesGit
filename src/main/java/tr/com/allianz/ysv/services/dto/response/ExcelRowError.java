package tr.com.allianz.ysv.services.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Excel yüklemede reddedilen satır")
public record ExcelRowError(int rowNumber, String ysvDosyaNo, String code, String message) {
}
