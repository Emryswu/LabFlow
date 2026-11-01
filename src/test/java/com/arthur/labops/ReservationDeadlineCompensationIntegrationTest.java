package com.arthur.labops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.arthur.labops.equipment.Equipment;
import com.arthur.labops.equipment.EquipmentRepository;
import com.arthur.labops.reservation.Reservation;
import com.arthur.labops.reservation.ReservationPaymentGateway;
import com.arthur.labops.reservation.ReservationRepository;
import com.arthur.labops.reservation.ReservationStatus;
import com.arthur.labops.reservation.expiry.ReservationDeadlineEvent;
import com.arthur.labops.reservation.expiry.ReservationDeadlineKind;
import com.arthur.labops.reservation.expiry.ReservationExpiryCompensationJob;
import com.arthur.labops.reservation.expiry.ReservationExpiryScheduler;
import com.arthur.labops.user.PlatformUser;
import com.arthur.labops.user.PlatformUserRepository;

@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:deadline-compensation;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@Import(ReservationDeadlineCompensationIntegrationTest.SchedulerConfiguration.class)
class ReservationDeadlineCompensationIntegrationTest {

    @Autowired private ReservationRepository reservationRepository;
    @Autowired private EquipmentRepository equipmentRepository;
    @Autowired private PlatformUserRepository userRepository;
    @Autowired private ReservationPaymentGateway paymentGateway;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ReservationExpiryCompensationJob compensationJob;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FailingDeadlineScheduler scheduler;

    @ParameterizedTest(name = "{0}: failed delivery is recovered by one compensation scan")
    @EnumSource(ReservationDeadlineKind.class)
    void everyDeadlineKindIsRecoveredAfterDeliveryFailure(ReservationDeadlineKind kind) {
        Instant deadline = Instant.now().plusSeconds(3600);
        Equipment equipment = equipmentRepository.saveAndFlush(new Equipment(
                "DEADLINE-" + UUID.randomUUID(), "deadline compensation", "test", "lab"));
        PlatformUser requester = userRepository.findByUsername("student").orElseThrow();
        Instant start = deadline.plusSeconds(3600);
        Reservation reservation = new Reservation(equipment, requester.getId(), requester.getDisplayName(),
                kind.name(), start, start.plusSeconds(3600), deadline);
        // No default: adding a kind must also add its overdue fixture here.
        String deadlineColumn = switch (kind) {
            case APPROVAL -> "expires_at";
            case PAYMENT -> {
                reservation.awaitPayment(deadline);
                yield "payment_deadline";
            }
        };
        ReservationStatus initialStatus = reservation.getStatus();
        int failuresBefore = scheduler.failures.get();

        Long reservationId = new TransactionTemplate(transactionManager).execute(transaction -> {
            Reservation saved = reservationRepository.saveAndFlush(reservation);
            if (kind == ReservationDeadlineKind.PAYMENT) {
                paymentGateway.openOrder(saved, 3_000L);
            }
            eventPublisher.publishEvent(ReservationDeadlineEvent.arm(kind, saved.getId(), deadline));
            return saved.getId();
        });

        assertThat(scheduler.failures.get()).isEqualTo(failuresBefore + 1);
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(initialStatus);
        // The column comes from the exhaustive enum switch, never external input.
        jdbc.update("update equipment_reservations set " + deadlineColumn + " = ? where id = ?",
                Instant.now().minusSeconds(1), reservationId);

        compensationJob.expireOverduePendingReservations();

        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .as("%s must be recovered by the DB scan when its deadline message is lost", kind)
                .isEqualTo(ReservationStatus.EXPIRED);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SchedulerConfiguration {
        @Bean
        @Primary
        FailingDeadlineScheduler failingDeadlineScheduler() {
            return new FailingDeadlineScheduler();
        }
    }

    static class FailingDeadlineScheduler implements ReservationExpiryScheduler {
        final AtomicInteger failures = new AtomicInteger();

        @Override
        public void schedule(ReservationDeadlineKind kind, Long reservationId, Instant deadline) {
            failures.incrementAndGet();
            throw new IllegalStateException("simulated deadline delivery failure after commit");
        }

        @Override
        public void cancel(ReservationDeadlineKind kind, Long reservationId, Instant deadline) {
        }
    }
}
