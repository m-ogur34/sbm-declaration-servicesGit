package tr.com.allianz.ysv.services.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tr.com.allianz.ysv.services.enums.MovableType;
import tr.com.allianz.ysv.services.enums.ProcessStatus;


@Entity
@Table(name = "ALZ_SBM_DECL_PROCESS")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeclarationProcess {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "declProcessSeqGenerator")
    @SequenceGenerator(name = "declProcessSeqGenerator",
            sequenceName = "ALZ_SBM_DECL_PROCESS_SEQ",
            allocationSize = 1)
    @Column(name = "ID")
    private Long id;

    /** SBM: {@code ay}, POST only. */
    @Column(name = "DECLARATION_MONTH")
    private Integer declarationMonth;

    /** SBM: {@code yil}, POST only. */
    @Column(name = "DECLARATION_YEAR")
    private Integer declarationYear;

    /** SBM: {@code ilKodu}, POST only. */
    @Column(name = "CITY_CODE")
    private Integer cityCode;

    /** SBM: {@code ilceKodu}, POST only, null for metropolitan municipalities. */
    @Column(name = "DISTRICT_CODE")
    private Integer districtCode;

    /** SBM: {@code sigortaSirketKodu}. */
    @Column(name = "COMPANY_CODE", length = 3)
    private String companyCode;

    /** SBM: {@code sonOdemeTarihi}. */
    @Column(name = "PAYMENT_DATE")
    private LocalDate paymentDate;

    /** SBM: {@code ysvDosyaNo}, max 36 characters. */
    @Column(name = "SBM_FILE_NO", length = 100)
    private String sbmFileNo;

    /** SBM: {@code alinanPrimTutari}. */
    @Column(name = "RECEIVED_PREMIUM_AMOUNT")
    private BigDecimal receivedPremiumAmount;

    /** SBM: {@code iptalPrimTutari}. */
    @Column(name = "CANCELLED_PREMIUM_AMOUNT")
    private BigDecimal cancelledPremiumAmount;

    /** SBM: {@code gecmisAyIadeTutari}. */
    @Column(name = "PREV_MONTH_REFUND_AMOUNT")
    private BigDecimal prevMonthRefundAmount;

    /** SBM: {@code menkulTipi}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "MOVABLE_TYPE", length = 20)
    private MovableType movableType;

    /** SBM: {@code odenecekVergi}. */
    @Column(name = "TAX_AMOUNT")
    private BigDecimal taxAmount;

    /** SBM: {@code vergiOrani}. */
    @Column(name = "TAX_RATIO")
    private Integer taxRatio;

    /** SBM: {@code vergiPrimTutari}. */
    @Column(name = "TAX_PREMIUM_AMOUNT")
    private BigDecimal taxPremiumAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS", length = 20)
    private ProcessStatus status;

    @Column(name = "DATE_CREATED")
    private LocalDateTime dateCreated;

    @Column(name = "CREATED_BY_USER", length = 100)
    private String createdByUser;

    @Column(name = "DATE_UPDATED")
    private LocalDateTime dateUpdated;

    @Column(name = "UPDATED_BY_USER", length = 100)
    private String updatedByUser;

    @Column(name = "DATE_SENT")
    private LocalDateTime dateSent;

    @Column(name = "SENT_BY_USER", length = 100)
    private String sentByUser;

    @Column(name = "ERROR_DETAILS", length = 2000)
    private String errorDetails;

    @Column(name = "SOURCE_FILE_NAME", length = 500)
    private String sourceFileName;
}
