package tr.com.allianz.ysv.services.dto.internal;

import com.fasterxml.jackson.databind.JsonNode;

/** Tekli uçların cevabı: SBM'nin (ya da SBM biçimindeki) gövdesi ve HTTP kodu. */
public record SbmReply(int httpStatus, JsonNode body) {
}
