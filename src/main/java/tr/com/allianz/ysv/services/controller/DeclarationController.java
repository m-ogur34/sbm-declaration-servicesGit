package tr.com.allianz.ysv.services.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tr.com.allianz.ysv.services.dto.internal.SbmQueryResponse;
import tr.com.allianz.ysv.services.dto.request.DeclarationFilterRequest;
import tr.com.allianz.ysv.services.dto.request.DeclarationUpdateRequest;
import tr.com.allianz.ysv.services.dto.request.RequestContext;
import tr.com.allianz.ysv.services.dto.response.BatchOperationResponse;
import tr.com.allianz.ysv.services.dto.response.DeclarationUpdateResponse;
import tr.com.allianz.ysv.services.dto.response.PageResponse;
import tr.com.allianz.ysv.services.dto.response.ProcessView;
import tr.com.allianz.ysv.services.enums.ProcessStatus;
import tr.com.allianz.ysv.services.service.DeclarationService;

/**
 * Gönder / güncelle / sorgula / iptal — hepsi hem toplu (filtre) hem tekli ({@code ysvDosyaNo}).
 * İsteği başlatanın bilgileri {@link RequestContext} olarak header'lardan gelir.
 */
@RestController
@RequestMapping("/api/v1/declarations")
@RequiredArgsConstructor
@Tag(name = "Declarations", description = "SBM YSV beyanname gönderim, güncelleme ve sorgulama servisleri")
public class DeclarationController {

    private final DeclarationService declarationService;

    // --- toplu ---------------------------------------------------------------------------

    @PostMapping("/send")
    @Operation(summary = "Toplu gönder: filtreye uyan NEW/ERROR beyannameleri SBM'ye POST eder")
    public ResponseEntity<BatchOperationResponse> send(@Valid @RequestBody DeclarationFilterRequest filter,
                                                       RequestContext context) {
        return ResponseEntity.ok(declarationService.send(filter, context));
    }

    @PutMapping("/update")
    @Operation(summary = "Toplu güncelle: filtreye uyan SENT/COMPLETED beyannamelerin DB'deki değerlerini SBM'ye PUT eder")
    public ResponseEntity<BatchOperationResponse> update(@Valid @RequestBody DeclarationFilterRequest filter,
                                                         RequestContext context) {
        return ResponseEntity.ok(declarationService.update(filter, context));
    }

    @PostMapping("/query")
    @Operation(summary = "Toplu sorgula: filtreye uyan SENT/COMPLETED beyannameleri SBM'den doğrular, COMPLETED yapar")
    public ResponseEntity<BatchOperationResponse> queryBatch(@Valid @RequestBody DeclarationFilterRequest filter,
                                                             RequestContext context) {
        return ResponseEntity.ok(declarationService.queryBatch(filter, context));
    }

    @PostMapping("/cancel")
    @Operation(summary = "Toplu iptal: tutarları 0 ile SBM'de günceller (SBM'de silme yoktur)")
    public ResponseEntity<BatchOperationResponse> cancel(@Valid @RequestBody DeclarationFilterRequest filter,
                                                         RequestContext context) {
        return ResponseEntity.ok(declarationService.cancel(filter, context));
    }

    // --- tekli ---------------------------------------------------------------------------

    @PostMapping("/{ysvDosyaNo}/send")
    @Operation(summary = "Tekli gönder: beyannameyi SBM'ye POST eder")
    public ResponseEntity<BatchOperationResponse> sendOne(@PathVariable @NotBlank @Size(max = 36) String ysvDosyaNo,
                                                          RequestContext context) {
        return ResponseEntity.ok(declarationService.sendOne(ysvDosyaNo, context));
    }

    @PutMapping("/{ysvDosyaNo}")
    @Operation(summary = "Tekli güncelle: yeni tutarları DB'ye yazar; beyanname SBM'deyse aynı çağrıda PUT eder")
    public ResponseEntity<DeclarationUpdateResponse> updateOne(@PathVariable @NotBlank @Size(max = 36) String ysvDosyaNo,
                                                               @Valid @RequestBody DeclarationUpdateRequest request,
                                                               RequestContext context) {
        return ResponseEntity.ok(declarationService.updateOne(ysvDosyaNo, request, context));
    }

    @GetMapping("/{ysvDosyaNo}")
    @Operation(summary = "Tekli sorgula: beyannameyi SBM'den sorgular; doğrulanırsa COMPLETED yapar")
    public ResponseEntity<SbmQueryResponse> query(@PathVariable @NotBlank @Size(max = 36) String ysvDosyaNo,
                                                  RequestContext context) {
        return ResponseEntity.ok(declarationService.query(ysvDosyaNo, context));
    }

    @PostMapping("/{ysvDosyaNo}/cancel")
    @Operation(summary = "Tekli iptal: beyannamenin tutarlarını 0 ile SBM'de günceller")
    public ResponseEntity<BatchOperationResponse> cancelOne(@PathVariable @NotBlank @Size(max = 36) String ysvDosyaNo,
                                                            RequestContext context) {
        return ResponseEntity.ok(declarationService.cancelOne(ysvDosyaNo, context));
    }

    // --- listeleme -----------------------------------------------------------------------

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
