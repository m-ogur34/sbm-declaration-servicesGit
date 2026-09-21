package tr.com.allianz.ysv.services.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tr.com.allianz.ysv.services.dto.internal.SbmQueryResponse;
import tr.com.allianz.ysv.services.dto.request.DeclarationAmountUpdateRequest;
import tr.com.allianz.ysv.services.dto.request.DeclarationFilterRequest;
import tr.com.allianz.ysv.services.dto.response.BatchOperationResponse;
import tr.com.allianz.ysv.services.dto.response.PageResponse;
import tr.com.allianz.ysv.services.dto.response.ProcessView;
import tr.com.allianz.ysv.services.enums.ProcessStatus;
import tr.com.allianz.ysv.services.service.DeclarationService;

@RestController
@RequestMapping("/api/v1/declarations")
@RequiredArgsConstructor
@Tag(name = "Declarations", description = "SBM YSV beyanname gönderim, güncelleme ve sorgulama servisleri")
public class DeclarationController {

    static final String USER_HEADER = "X-User-Name";
    static final String DEFAULT_USER = "SYSTEM";

    private final DeclarationService declarationService;

    @PostMapping("/send")
    @Operation(summary = "Filtreye uyan NEW/ERROR beyannameleri SBM'ye POST eder")
    public ResponseEntity<BatchOperationResponse> send(
            @Valid @RequestBody DeclarationFilterRequest request,
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = DEFAULT_USER) String user) {
        return ResponseEntity.ok(declarationService.send(request, user));
    }

    @PutMapping("/update")
    @Operation(summary = "Filtreye uyan SENT/COMPLETED beyannameleri SBM'de günceller")
    public ResponseEntity<BatchOperationResponse> update(
            @Valid @RequestBody DeclarationFilterRequest request,
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = DEFAULT_USER) String user) {
        return ResponseEntity.ok(declarationService.update(request, user));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Beyanname satırının tutarlarını DB'de günceller (SBM'ye göndermez)")
    public ResponseEntity<ProcessView> updateAmounts(
            @PathVariable Long id,
            @Valid @RequestBody DeclarationAmountUpdateRequest request,
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = DEFAULT_USER) String user) {
        return ResponseEntity.ok(declarationService.updateAmounts(id, request, user));
    }

    @PostMapping("/cancel")
    @Operation(summary = "Tutarları sıfırlayarak SBM'de günceller (SBM'de silme işlemi yoktur)")
    public ResponseEntity<BatchOperationResponse> cancel(
            @Valid @RequestBody DeclarationFilterRequest request,
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = DEFAULT_USER) String user) {
        return ResponseEntity.ok(declarationService.cancel(request, user));
    }

    @GetMapping("/query/{ysvDosyaNo}")
    @Operation(summary = "Beyannameyi SBM'den sorgular; onaylanırsa kayıtları COMPLETED yapar")
    public ResponseEntity<SbmQueryResponse> query(
            @PathVariable String ysvDosyaNo,
            @RequestHeader(value = USER_HEADER, required = false, defaultValue = DEFAULT_USER) String user) {
        return ResponseEntity.ok(declarationService.query(ysvDosyaNo, user));
    }

    @GetMapping("/processes")
    @Operation(summary = "Beyanname kayıtlarını sayfalı listeler")
    public ResponseEntity<PageResponse<ProcessView>> processes(
            @RequestParam(required = false) ProcessStatus status,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer cityCode,
            @PageableDefault(size = 50, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(declarationService.search(status, year, month, cityCode, pageable));
    }
}
