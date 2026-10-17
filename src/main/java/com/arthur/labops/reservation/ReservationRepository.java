package com.arthur.labops.reservation;

import java.time.Instant;
import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ReservationRepository extends JpaRepository<Reservation, Long>, JpaSpecificationExecutor<Reservation> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select reservation
            from Reservation reservation
            where reservation.equipment.id = :equipmentId
              and reservation.status in :statuses
              and reservation.startTime < :requestedEnd
              and reservation.endTime > :requestedStart
              and (:excludeId is null or reservation.id <> :excludeId)
            order by reservation.id
            """)
    java.util.List<Reservation> findConflictsForUpdate(
            @Param("equipmentId") Long equipmentId,
            @Param("statuses") Collection<ReservationStatus> statuses,
            @Param("requestedEnd") Instant requestedEnd,
            @Param("requestedStart") Instant requestedStart,
            @Param("excludeId") Long excludeId);

    /**
     * Read-only counterpart of {@link #findConflictsForUpdate} for a whole page of
     * pending requests at once: every slot-holder on these devices that touches the
     * window. The caller narrows each candidate down with {@link Reservation#overlaps}.
     */
    @Query("""
            select reservation
            from Reservation reservation
            where reservation.equipment.id in :equipmentIds
              and reservation.status in :statuses
              and reservation.startTime < :windowEnd
              and reservation.endTime > :windowStart
            order by reservation.startTime, reservation.id
            """)
    java.util.List<Reservation> findOverlappingCandidates(
            @Param("equipmentIds") Collection<Long> equipmentIds,
            @Param("statuses") Collection<ReservationStatus> statuses,
            @Param("windowEnd") Instant windowEnd,
            @Param("windowStart") Instant windowStart);

    boolean existsByEquipmentIdAndStatusInAndStartTimeLessThanEqualAndEndTimeGreaterThan(
            Long equipmentId,
            Collection<ReservationStatus> statuses,
            Instant startBoundary,
            Instant endBoundary);

    @Query("select reservation.equipment.id from Reservation reservation where reservation.id = :id")
    java.util.Optional<Long> findEquipmentIdById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select reservation from Reservation reservation where reservation.id = :id")
    java.util.Optional<Reservation> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select reservation.id from Reservation reservation
            where reservation.status = com.arthur.labops.reservation.ReservationStatus.PENDING
              and reservation.expiresAt <= :now
            order by reservation.id
            """)
    java.util.List<Long> findOverduePendingIds(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select reservation from Reservation reservation
            where reservation.equipment.id = :equipmentId
              and reservation.status in :statuses
            order by reservation.id
            """)
    java.util.List<Reservation> findByEquipmentIdAndStatusInForUpdate(
            @Param("equipmentId") Long equipmentId,
            @Param("statuses") Collection<ReservationStatus> statuses);

    long countByRequesterIdAndStatusIn(Long requesterId, Collection<ReservationStatus> statuses);

    long countByStatus(ReservationStatus status);

    /** Half-open {@code [from, to)}: a reservation starting exactly at midnight belongs to the next day. */
    long countByStartTimeGreaterThanEqualAndStartTimeLessThanAndStatusIn(
            Instant from, Instant to, Collection<ReservationStatus> statuses);

    @Query("""
            select distinct reservation.equipment.id from Reservation reservation
            where reservation.status in :statuses
            order by reservation.equipment.id
            """)
    java.util.List<Long> findEquipmentIdsWithReservationsInStatus(
            @Param("statuses") Collection<ReservationStatus> statuses);

    @Query("""
            select reservation.id from Reservation reservation
            where reservation.status = com.arthur.labops.reservation.ReservationStatus.AWAITING_PAYMENT
              and reservation.paymentDeadline <= :now
            order by reservation.id
            """)
    java.util.List<Long> findOverdueAwaitingPaymentIds(@Param("now") Instant now);
}
