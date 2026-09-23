package tr.com.allianz.ysv.services.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tr.com.allianz.ysv.services.entity.DeclarationProcess;
import tr.com.allianz.ysv.services.enums.ProcessStatus;

@Repository
public interface DeclarationProcessRepository extends JpaRepository<DeclarationProcess, Long> {
    @Query("""
            select p from DeclarationProcess p
            where p.status in :statuses
              and (:year is null or p.declarationYear = :year)
              and (:month is null or p.declarationMonth = :month)
              and (:cityCode is null or p.cityCode = :cityCode)
            order by p.declarationYear, p.declarationMonth, p.cityCode, p.districtCode, p.id
            """)
    List<DeclarationProcess> findCandidates(@Param("statuses") Collection<ProcessStatus> statuses,
                                            @Param("year") Integer year,
                                            @Param("month") Integer month,
                                            @Param("cityCode") Integer cityCode);

    /** Candidates pinned by explicit id. */
    /** Candidates pinned by SBM file number (ysvDosyaNo). */
    @Query("""
            select p from DeclarationProcess p
            where p.sbmFileNo in :fileNos and p.status in :statuses
            order by p.declarationYear, p.declarationMonth, p.cityCode, p.districtCode, p.id
            """)
    List<DeclarationProcess> findCandidatesByFileNos(@Param("fileNos") Collection<String> fileNos,
                                                     @Param("statuses") Collection<ProcessStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from DeclarationProcess p where p.id in :ids order by p.id")
    List<DeclarationProcess> lockByIds(@Param("ids") Collection<Long> ids);

    List<DeclarationProcess> findBySbmFileNo(String sbmFileNo);

    /** Bir beyannamenin tüm satırlarını, iki operatör aynı anda değiştiremesin diye kilitleyerek okur. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from DeclarationProcess p where p.sbmFileNo = :fileNo order by p.id")
    List<DeclarationProcess> lockBySbmFileNo(@Param("fileNo") String fileNo);

    /** Excel upsert'ünde dosyadaki dönemin mevcut satırları, kilitli. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select p from DeclarationProcess p
            where p.declarationYear = :year and p.declarationMonth = :month
            order by p.id
            """)
    List<DeclarationProcess> lockByPeriod(@Param("year") Integer year, @Param("month") Integer month);

    /** Excel doğrulamada dönemin mevcut satırları, kilitsiz (yükleme {@link #lockByPeriod} kullanır). */
    @Query("""
            select p from DeclarationProcess p
            where p.declarationYear = :year and p.declarationMonth = :month
            order by p.id
            """)
    List<DeclarationProcess> findByPeriod(@Param("year") Integer year, @Param("month") Integer month);

    /**
     * Verilen dosya numaralarından DB'de kayıtlı olanlar (Excel yüklemede "başka dönemde kayıtlı"
     * kontrolü). Oracle IN sınırı nedeniyle en fazla 1000 değerle çağrılır.
     */
    @Query("select distinct p.sbmFileNo from DeclarationProcess p where p.sbmFileNo in :fileNos")
    List<String> findExistingFileNos(@Param("fileNos") Collection<String> fileNos);
    @Query("""
            select p from DeclarationProcess p
            where (:status is null or p.status = :status)
              and (:year is null or p.declarationYear = :year)
              and (:month is null or p.declarationMonth = :month)
              and (:cityCode is null or p.cityCode = :cityCode)
            """)
    Page<DeclarationProcess> search(@Param("status") ProcessStatus status,
                                    @Param("year") Integer year,
                                    @Param("month") Integer month,
                                    @Param("cityCode") Integer cityCode,
                                    Pageable pageable);
}
