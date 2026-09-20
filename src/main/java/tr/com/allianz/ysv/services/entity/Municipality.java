package tr.com.allianz.ysv.services.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


@Entity
@Table(name = "ALZ_SBM_MUNICIPALITY")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Municipality {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "municipalitySeqGenerator")
    @SequenceGenerator(name = "municipalitySeqGenerator",
            sequenceName = "ALZ_SBM_MUNICIPALITY_SEQ",
            allocationSize = 1)
    @Column(name = "ID")
    private Long id;

    @Column(name = "CITY_CODE", nullable = false)
    private Integer cityCode;

    @Column(name = "DISTRICT_CODE", nullable = false)
    private Integer districtCode;

    @Column(name = "MUNICIPALITY_NAME", nullable = false, length = 255)
    private String municipalityName;

    @Column(name = "KEP_EMAIL", length = 320)
    private String kepEmail;

    @Column(name = "EMAIL", length = 320)
    private String email;

    @Column(name = "PHONE", length = 30)
    private String phone;

    @Column(name = "POSTAL_ADDRESS", length = 1000)
    private String postalAddress;

    @Column(name = "NOTIFICATION_TYPE", length = 20)
    private String notificationType;

    @Column(name = "CONTACT_PERSON", length = 255)
    private String contactPerson;

    @Column(name = "IS_ACTIVE", nullable = false, length = 1)
    private String isActive;
}
