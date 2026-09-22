package tr.com.allianz.ysv.services.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Toplu işlem sonucu ({@link ApiResponse#data()}).
 *
 * @param results beyanname başına SBM'nin cevabı, önüne {@code ysvDosyaNo} eklenmiş hâliyle:
 *                {@code { "ysvDosyaNo": "...", "result": true, "status": 201, "data": {...} }};
 *                SBM'ye gitmeden reddedilenler SBM'nin hata biçiminde
 */
public record BatchResult(int totalGroups, int successCount, int failCount, List<JsonNode> results) {
}
