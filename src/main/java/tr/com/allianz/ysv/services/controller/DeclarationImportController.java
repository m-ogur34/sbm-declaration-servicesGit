package tr.com.allianz.ysv.services.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tr.com.allianz.ysv.services.dto.request.RequestContext;
import tr.com.allianz.ysv.services.dto.response.ImportResultResponse;
import tr.com.allianz.ysv.services.service.DeclarationImportService;

@RestController
@RequestMapping("/api/v1/declarations")
@RequiredArgsConstructor
@Tag(name = "Declaration Import", description = "YSV beyanname Excel yükleme (1. aşama)")
public class DeclarationImportController {

    private final DeclarationImportService declarationImportService;

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "YSV beyanname Excel'ini (.xlsx) yükler; geçerli satırları NEW olarak yazar")
    public ResponseEntity<ImportResultResponse> upload(
            @RequestParam("file") MultipartFile file,
            RequestContext context) {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Yüklenecek dosya boş.");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IllegalArgumentException("Sadece .xlsx dosyası yüklenebilir. Gelen: " + name);
        }
        return ResponseEntity.ok(declarationImportService.importFile(file, context.userName()));
    }
}
