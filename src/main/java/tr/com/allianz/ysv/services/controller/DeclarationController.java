package tr.com.allianz.ysv.services.controller;

import static tr.com.allianz.ysv.services.mapper.SbmMapper.YSV_DOSYA_NO_MESSAGE;
import static tr.com.allianz.ysv.services.mapper.SbmMapper.YSV_DOSYA_NO_PATTERN;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.fasterxml.jackson.databind.JsonNode;
import tr.com.allianz.ysv.services.dto.internal.SbmReply;
import tr.com.allianz.ysv.services.dto.request.DeclarationFilterRequest;
import tr.com.allianz.ysv.services.dto.request.DeclarationUpdateRequest;
import tr.com.allianz.ysv.services.dto.request.RequestContext;
import tr.com.allianz.ysv.services.dto.response.ApiResponse;
import tr.com.allianz.ysv.services.dto.response.BatchResult;
import tr.com.allianz.ysv.services.dto.response.PageResponse;
import tr.com.allianz.ysv.services.dto.response.ProcessView;
import tr.com.allianz.ysv.services.enums.ProcessStatus;
import tr.com.allianz.ysv.services.service.DeclarationService;


@RestController
@RequestMapping("/api/v1/declarations")
@RequiredArgsConstructor
@Tag(name = "Declarations", description = "SBM YSV beyanname gönderim, güncelleme ve sorgulama servisleri")
public class DeclarationController {


    /** {@code /processes} için sıralanabilir alanlar (listede görünen alanlar + kayıt tarihleri). */
    private static final Set<String> SORTABLE = new LinkedHashSet<>(List.of(
            "id", "declarationYear", "declarationMonth", "cityCode", "districtCode", "sbmFileNo",
            "movableType", "status", "paymentDate", "receivedPremiumAmount", "cancelledPremiumAmount",
            "taxAmount", "taxPremiumAmount", "taxRatio", "dateSent", "dateCreated", "dateUpdated"));

    private final DeclarationService declarationService;

    @PostMapping("/send")
    @Operation(summary = "Toplu gönder: filtreye uyan NEW/ERROR beyannameleri SBM'ye POST eder")
    public ResponseEntity<ApiResponse<BatchResult>> send(@Valid @RequestBody DeclarationFilterRequest filter,
                                                       RequestContext context) {
        return ResponseEntity.ok(declarationService.send(filter, context));
    }

    @PutMapping("/update")
    @Operation(summary = "Toplu güncelle: filtreye uyan SENT/COMPLETED beyannamelerin DB'deki değerlerini SBM'ye PUT eder")
    public ResponseEntity<ApiResponse<BatchResult>> update(@Valid @RequestBody DeclarationFilterRequest filter,
                                                         RequestContext context) {
        return ResponseEntity.ok(declarationService.update(filter, context));
    }

    @PostMapping("/query")
    @Operation(summary = "Toplu sorgula: filtreye uyan SENT/COMPLETED beyannameleri SBM'den doğrular, COMPLETED yapar")
    public ResponseEntity<ApiResponse<BatchResult>> queryBatch(@Valid @RequestBody DeclarationFilterRequest filter,
                                                             RequestContext context) {
        return ResponseEntity.ok(declarationService.queryBatch(filter, context));
    }

    @PostMapping("/cancel")
    @Operation(summary = "Toplu iptal: tutarları 0 ile SBM'de günceller (SBM'de silme yoktur)")
    public ResponseEntity<ApiResponse<BatchResult>> cancel(@Valid @RequestBody DeclarationFilterRequest filter,
                                                         RequestContext context) {
        return ResponseEntity.ok(declarationService.cancel(filter, context));
    }

    @PostMapping("/{ysvDosyaNo}/send")
    @Operation(summary = "Tekli gönder: beyannameyi SBM'ye POST eder")
    public ResponseEntity<JsonNode> sendOne(@PathVariable @NotBlank @Size(max = 36)
                                                 @Pattern(regexp = YSV_DOSYA_NO_PATTERN, message = YSV_DOSYA_NO_MESSAGE) String ysvDosyaNo,
                                                          RequestContext context) {
        return toResponse(declarationService.sendOne(ysvDosyaNo, context));
    }

    @PutMapping("/{ysvDosyaNo}")
    @Operation(summary = "Tekli güncelle: yeni tutarları DB'ye yazar; beyanname SBM'deyse aynı çağrıda PUT eder")
    public ResponseEntity<JsonNode> updateOne(@PathVariable @NotBlank @Size(max = 36)
                                                   @Pattern(regexp = YSV_DOSYA_NO_PATTERN, message = YSV_DOSYA_NO_MESSAGE) String ysvDosyaNo,
                                                               @Valid @RequestBody DeclarationUpdateRequest request,
                                                               RequestContext context) {
        return toResponse(declarationService.updateOne(ysvDosyaNo, request, context));
    }

    @GetMapping("/{ysvDosyaNo}")
    @Operation(summary = "Tekli sorgula: beyannameyi SBM'den sorgular; doğrulanırsa COMPLETED yapar")
    public ResponseEntity<JsonNode> query(@PathVariable @NotBlank @Size(max = 36)
                                               @Pattern(regexp = YSV_DOSYA_NO_PATTERN, message = YSV_DOSYA_NO_MESSAGE) String ysvDosyaNo,
                                                  RequestContext context) {
        return toResponse(declarationService.query(ysvDosyaNo, context));
    }

    @PostMapping("/{ysvDosyaNo}/cancel")
    @Operation(summary = "Tekli iptal: beyannamenin tutarlarını 0 ile SBM'de günceller")
    public ResponseEntity<JsonNode> cancelOne(@PathVariable @NotBlank @Size(max = 36)
                                                   @Pattern(regexp = YSV_DOSYA_NO_PATTERN, message = YSV_DOSYA_NO_MESSAGE) String ysvDosyaNo,
                                                            RequestContext context) {
        return toResponse(declarationService.cancelOne(ysvDosyaNo, context));
    }

    @GetMapping("/processes")
    @Operation(summary = "Beyanname kayıtlarını sayfalı listeler")
    public ResponseEntity<ApiResponse<PageResponse<ProcessView>>> processes(
            @RequestParam(required = false) ProcessStatus status,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer cityCode,
            @PageableDefault(size = 50, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        requireSortable(pageable.getSort());
        return ResponseEntity.ok(ApiResponse.ok(200, declarationService.search(status, year, month, cityCode, pageable)));
    }

    /**
     * Sıralama alanı sorguya olduğu gibi eklenir; bilinmeyen bir alan DB'de hata verip 500 dönerdi.
     * İstek DB'ye gitmeden 400 ile reddedilir (gelen değer mesajda geri dönmez).
     */
    private static void requireSortable(Sort sort) {
        for (Sort.Order order : sort) {
            if (!SORTABLE.contains(order.getProperty())) {
                throw new IllegalArgumentException("sort yalnızca şu alanlarla yapılabilir: " + String.join(", ", SORTABLE));
            }
        }
    }

    private static ResponseEntity<JsonNode> toResponse(SbmReply reply) {
        return ResponseEntity.status(reply.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(reply.body());
    }
}
