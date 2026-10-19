package com.arthur.labops.equipment;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface EquipmentRepository extends JpaRepository<Equipment, Long>, JpaSpecificationExecutor<Equipment> {

    boolean existsByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select equipment from Equipment equipment where equipment.id = :id")
    Optional<Equipment> findByIdForUpdate(@Param("id") Long id);

    long countByStatus(EquipmentStatus status);

    /** Every category in use, retired equipment included, independent of any list filter or page. */
    @Query("select distinct equipment.category from Equipment equipment order by equipment.category")
    List<String> findDistinctCategories();
}
