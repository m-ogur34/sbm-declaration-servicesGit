package tr.com.allianz.ysv.services.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;


@Schema(description = "Excel yükleme sonucu")
public record ImportResultResponse(String sourceFileName,
                                   int totalRows,
                                   int inserted,
                                   int failed,
                                   List<ExcelRowError> errors) {

    public static ImportResultResponse of(String sourceFileName, int totalRows, int inserted,
                                          List<ExcelRowError> errors) {
        List<ExcelRowError> safe = errors == null ? List.of() : List.copyOf(errors);
        return new ImportResultResponse(sourceFileName, totalRows, inserted, safe.size(), safe);
    }
}
