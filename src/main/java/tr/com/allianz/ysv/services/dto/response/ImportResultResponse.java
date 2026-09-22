package tr.com.allianz.ysv.services.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;


@Schema(description = "Excel yükleme sonucu")
public record ImportResultResponse(String sourceFileName,
                                   int totalRows,
                                   int inserted,
                                   int updated,
                                   @Schema(description = "Tutarı değişen beyannameler; SBM'ye taşımak için "
                                           + "PUT /update gövdesinde ysvDosyaNoList olarak verilebilir.")
                                   List<String> updatedFileNos,
                                   int failed,
                                   List<ExcelRowError> errors) {

    public static ImportResultResponse of(String sourceFileName, int totalRows, int inserted, int updated,
                                          List<String> updatedFileNos, List<ExcelRowError> errors) {
        List<ExcelRowError> safe = errors == null ? List.of() : List.copyOf(errors);
        List<String> fileNos = updatedFileNos == null ? List.of() : List.copyOf(updatedFileNos);
        return new ImportResultResponse(sourceFileName, totalRows, inserted, updated, fileNos, safe.size(), safe);
    }
}
