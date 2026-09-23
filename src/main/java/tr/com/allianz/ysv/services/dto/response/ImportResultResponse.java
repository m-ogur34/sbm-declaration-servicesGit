package tr.com.allianz.ysv.services.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;


@Schema(description = "Excel yükleme / doğrulama sonucu")
public record ImportResultResponse(String sourceFileName,
                                   int totalRows,
                                   int inserted,
                                   int updated,
                                   @Schema(description = "Yeni eklenen (doğrulamada: eklenecek) beyannameler.")
                                   List<String> insertedFileNos,
                                   @Schema(description = "Tutarı değişen beyannameler; SBM'ye taşımak için "
                                           + "PUT /update gövdesinde ysvDosyaNoList olarak verilebilir.")
                                   List<String> updatedFileNos,
                                   int failed,
                                   List<ExcelRowError> errors) {

    public static ImportResultResponse of(String sourceFileName, int totalRows, int inserted, int updated,
                                          List<String> insertedFileNos, List<String> updatedFileNos,
                                          List<ExcelRowError> errors) {
        List<ExcelRowError> safe = errors == null ? List.of() : List.copyOf(errors);
        return new ImportResultResponse(sourceFileName, totalRows, inserted, updated,
                copy(insertedFileNos), copy(updatedFileNos), safe.size(), safe);
    }

    private static List<String> copy(List<String> fileNos) {
        return fileNos == null ? List.of() : List.copyOf(fileNos);
    }
}
