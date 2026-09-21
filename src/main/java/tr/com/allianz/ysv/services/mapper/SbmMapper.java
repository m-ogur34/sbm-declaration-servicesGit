package tr.com.allianz.ysv.services.mapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tr.com.allianz.ysv.services.dto.internal.SbmAmountItem;
import tr.com.allianz.ysv.services.dto.internal.SbmDeclarationRequest;
import tr.com.allianz.ysv.services.dto.internal.SbmQueryRequest;
import tr.com.allianz.ysv.services.entity.DeclarationProcess;
import tr.com.allianz.ysv.services.enums.MovableType;
import tr.com.allianz.ysv.services.enums.SbmErrorCode;
import tr.com.allianz.ysv.services.exception.SbmIntegrationException;
import tr.com.allianz.ysv.services.util.DistrictCodeResolver;


@Slf4j
@Component
public class SbmMapper {

    static final int SBM_FILE_NO_MAX_LENGTH = 36;

    static final int COMPANY_CODE_MAX_LENGTH = 3;

    public SbmDeclarationRequest toSendRequest(List<DeclarationProcess> group, String companyCode) {
        requireGroupAndCompanyCode(group, companyCode);
        String fileNo = resolveFileNo(group);
        DeclarationProcess head = requirePaymentDate(group.get(0), fileNo);
        if (head.getCityCode() == null) {
            throw new SbmIntegrationException(SbmErrorCode.CORE_01000.getCode(),
                    "ilKodu boş olamaz. Dosya no: " + fileNo);
        }
        return baseRequest(head, companyCode, group, fileNo, false)
                .ay(head.getDeclarationMonth())
                .yil(head.getDeclarationYear())
                .ilKodu(head.getCityCode())
                .ilceKodu(DistrictCodeResolver.resolve(head.getDistrictCode()))
                .build();
    }

    public SbmDeclarationRequest toUpdateRequest(List<DeclarationProcess> group,
                                                 String companyCode,
                                                 boolean zeroAmounts) {
        requireGroupAndCompanyCode(group, companyCode);
        String fileNo = resolveFileNo(group);
        DeclarationProcess head = requirePaymentDate(group.get(0), fileNo);
        // No city or district check: PUT does not carry those fields at all.
        return baseRequest(head, companyCode, group, fileNo, zeroAmounts).build();
    }

    public SbmQueryRequest toQueryRequest(String ysvDosyaNo, String companyCode) {
        requireCompanyCode(companyCode);
        if (ysvDosyaNo == null || ysvDosyaNo.isBlank()) {
            throw new SbmIntegrationException(SbmErrorCode.CORE_01000.getCode(),
                    "Sorgu için ysvDosyaNo zorunludur.");
        }
        String trimmed = ysvDosyaNo.trim();
        requireFileNoLength(trimmed);
        return SbmQueryRequest.builder()
                .sigortaSirketKodu(companyCode)
                .ysvDosyaNo(trimmed)
                .build();
    }

    private SbmDeclarationRequest.SbmDeclarationRequestBuilder baseRequest(DeclarationProcess head,
                                                                           String companyCode,
                                                                           List<DeclarationProcess> group,
                                                                           String fileNo,
                                                                           boolean zeroAmounts) {
        return SbmDeclarationRequest.builder()
                .sigortaSirketKodu(companyCode)
                .sonOdemeTarihi(head.getPaymentDate())
                .ysvDosyaNo(fileNo)
                .ysvTutarList(toAmountList(group, zeroAmounts));
    }

    private void requireGroupAndCompanyCode(List<DeclarationProcess> group, String companyCode) {
        if (group == null || group.isEmpty()) {
            throw new SbmIntegrationException(SbmErrorCode.CORE_01000.getCode(),
                    "Gönderilecek beyanname satırı bulunamadı.");
        }
        requireCompanyCode(companyCode);
    }


    private void requireCompanyCode(String companyCode) {
        if (companyCode == null || companyCode.isBlank()) {
            throw new SbmIntegrationException(SbmErrorCode.RISK_HAVUZU_00002.getCode(),
                    "Sigorta şirket kodu tanımlı değil.");
        }
        if (companyCode.length() > COMPANY_CODE_MAX_LENGTH) {
            throw new SbmIntegrationException(SbmErrorCode.CORE_01008.getCode(),
                    "sigortaSirketKodu en fazla " + COMPANY_CODE_MAX_LENGTH
                            + " karakter olabilir: " + companyCode);
        }
    }

    private DeclarationProcess requirePaymentDate(DeclarationProcess head, String fileNo) {
        if (head.getPaymentDate() == null) {
            throw new SbmIntegrationException(SbmErrorCode.CORE_01000.getCode(),
                    "sonOdemeTarihi boş olamaz. Dosya no: " + fileNo);
        }
        return head;
    }


    private String resolveFileNo(List<DeclarationProcess> group) {
        Set<String> fileNumbers = new LinkedHashSet<>();
        for (DeclarationProcess process : group) {
            String candidate = process.getSbmFileNo();
            if (candidate != null && !candidate.isBlank()) {
                fileNumbers.add(candidate.trim());
            }
        }
        if (fileNumbers.isEmpty()) {
            throw new SbmIntegrationException(SbmErrorCode.CORE_01000.getCode(),
                    "ysvDosyaNo boş olamaz.");
        }
        String fileNo = fileNumbers.iterator().next();
        if (fileNumbers.size() > 1) {
            log.warn("Declaration group {}/{} il={} ilce={} carries {} different ysvDosyaNo values {}; "
                            + "the first one is sent. Check the OPUS extract.",
                    group.get(0).getDeclarationYear(), group.get(0).getDeclarationMonth(),
                    group.get(0).getCityCode(), group.get(0).getDistrictCode(),
                    fileNumbers.size(), fileNumbers);
        }
        requireFileNoLength(fileNo);
        return fileNo;
    }


    private void requireFileNoLength(String fileNo) {
        if (fileNo.length() > SBM_FILE_NO_MAX_LENGTH) {
            throw new SbmIntegrationException(SbmErrorCode.CORE_01008.getCode(),
                    "ysvDosyaNo en fazla " + SBM_FILE_NO_MAX_LENGTH + " karakter olabilir: " + fileNo);
        }
    }

    private List<SbmAmountItem> toAmountList(List<DeclarationProcess> group, boolean zeroAmounts) {
        Set<MovableType> seen = EnumSet.noneOf(MovableType.class);
        List<SbmAmountItem> items = new ArrayList<>(group.size());
        for (DeclarationProcess process : group) {
            MovableType movableType = process.getMovableType();
            if (movableType == null) {
                throw new SbmIntegrationException(SbmErrorCode.CORE_01000.getCode(),
                        "menkulTipi boş olamaz. Dosya no: " + process.getSbmFileNo());
            }
            if (!seen.add(movableType)) {
                throw new SbmIntegrationException(SbmErrorCode.RISK_HAVUZU_00005.getCode(),
                        "Aynı beyannamede mükerrer menkul tipi var: " + movableType.getSbmValue()
                                + ". Dosya no: " + process.getSbmFileNo());
            }
            items.add(toAmountItem(process, movableType, zeroAmounts));
        }
        return items;
    }

    private SbmAmountItem toAmountItem(DeclarationProcess process,
                                       MovableType movableType,
                                       boolean zeroAmounts) {
        return SbmAmountItem.builder()
                .menkulTipi(movableType.getSbmValue())
                .alinanPrimTutari(amount(process.getReceivedPremiumAmount(), zeroAmounts))
                .iptalPrimTutari(amount(process.getCancelledPremiumAmount(), zeroAmounts))
                .odenecekVergi(amount(process.getTaxAmount(), zeroAmounts))
                .vergiPrimTutari(amount(process.getTaxPremiumAmount(), zeroAmounts))
                .vergiOrani(process.getTaxRatio() == null ? 0 : process.getTaxRatio())
                .gecmisAyIadeTutari(refundAmount(process.getPrevMonthRefundAmount(), zeroAmounts))
                .build();
    }

    private static BigDecimal amount(BigDecimal value, boolean zeroAmounts) {
        if (zeroAmounts || value == null) {
            return BigDecimal.ZERO;
        }
        return value;
    }


    private static BigDecimal refundAmount(BigDecimal value, boolean zeroAmounts) {
        if (value == null) {
            return null;
        }
        return zeroAmounts ? BigDecimal.ZERO : value;
    }
}
