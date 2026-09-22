package tr.com.allianz.ysv.services.dto.response;

import java.util.List;

/**
 * Tekli güncelleme sonucu.
 *
 * @param sentToSbm SBM'ye PUT gönderildiyse {@code true}; beyanname henüz SBM'ye gitmemişse
 *                  (NEW / ERROR) yalnızca DB güncellenir ve {@code false} olur
 * @param success   DB güncellendi ve (gönderildiyse) SBM kabul etti
 * @param rows      beyannamenin güncel satırları
 */
public record DeclarationUpdateResponse(String ysvDosyaNo,
                                        boolean sentToSbm,
                                        boolean success,
                                        String errorCode,
                                        String message,
                                        List<ProcessView> rows) {
}
