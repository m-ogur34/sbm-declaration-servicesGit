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
    @Query("""
            select p from DeclarationProcess p
            where p.id in :ids and p.status in :statuses
            order by p.declarationYear, p.declarationMonth, p.cityCode, p.districtCode, p.id
            """)
    List<DeclarationProcess> findCandidatesByIds(@Param("ids") Collection<Long> ids,
                                                 @Param("statuses") Collection<ProcessStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from DeclarationProcess p where p.id in :ids order by p.id")
    List<DeclarationProcess> lockByIds(@Param("ids") Collection<Long> ids);

    List<DeclarationProcess> findBySbmFileNo(String sbmFileNo);

    /** Excel yüklemede mükerrer dosya numarası kontrolü için. */
    boolean existsBySbmFileNo(String sbmFileNo);
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
