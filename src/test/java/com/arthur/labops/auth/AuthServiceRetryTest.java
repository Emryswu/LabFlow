package com.arthur.labops.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;

import com.arthur.labops.common.BusinessException;
import com.arthur.labops.user.CurrentUserResponse;
import com.arthur.labops.user.UserRole;

class AuthServiceRetryTest {

    @Test
    void refreshRetriesOnceOnLockFailureAndSucceeds() {
        StubAuthRefreshTx tx = new StubAuthRefreshTx();
        IssuedAuthSession session = dummySession("next-refresh");
        tx.refreshSteps.add(() -> {
            throw new OptimisticLockingFailureException("stale version");
        });
        tx.refreshSteps.add(() -> RefreshOutcome.issued(session));

        AuthService service = new AuthService(null, null, tx, null);

        assertThat(service.refresh("  raw-token  ")).isSameAs(session);
        assertThat(tx.refreshCalls).isEqualTo(2);
        assertThat(tx.terminateCalls).isZero();
    }

    @Test
    void refreshTerminatesFamilyAfterTwoConsecutiveLockFailures() {
        StubAuthRefreshTx tx = new StubAuthRefreshTx();
        tx.refreshSteps.add(() -> {
            throw new PessimisticLockingFailureException("locked 1");
        });
        tx.refreshSteps.add(() -> {
            throw new OptimisticLockingFailureException("locked 2");
        });
        tx.terminateSteps.add(() -> {
            throw new OptimisticLockingFailureException("terminate lock 1");
        });
        tx.terminateSteps.add(RefreshOutcome::reused);

        AuthService service = new AuthService(null, null, tx, null);

        assertThatThrownBy(() -> service.refresh("raw-token"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException business = (BusinessException) ex;
                    assertThat(business.getCode()).isEqualTo("INVALID_REFRESH_TOKEN");
                    assertThat(business.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                });
        assertThat(tx.refreshCalls).isEqualTo(2);
        assertThat(tx.terminateCalls).isEqualTo(2);
    }

    @Test
    void refreshPropagatesNonLockFailureWithoutRetrying() {
        StubAuthRefreshTx tx = new StubAuthRefreshTx();
        IllegalStateException boom = new IllegalStateException("db down");
        tx.refreshSteps.add(() -> {
            throw boom;
        });

        AuthService service = new AuthService(null, null, tx, null);

        assertThatThrownBy(() -> service.refresh("raw-token")).isSameAs(boom);
        assertThat(tx.refreshCalls).isEqualTo(1);
    }

    @Test
    void logoutRetriesOnceAndSwallowsSecondLockFailure() {
        StubAuthRefreshTx tx = new StubAuthRefreshTx();
        tx.logoutSteps.add(() -> {
            throw new OptimisticLockingFailureException("lock 1");
        });
        tx.logoutSteps.add(() -> {
            throw new OptimisticLockingFailureException("lock 2");
        });

        AuthService service = new AuthService(null, null, tx, null);

        assertThatCode(() -> service.logout("raw-token")).doesNotThrowAnyException();
        assertThat(tx.logoutCalls).isEqualTo(2);
    }

    private static IssuedAuthSession dummySession(String refreshToken) {
        AuthResponse response = AuthResponse.of(
                "access-jwt",
                900L,
                new CurrentUserResponse(1L, "student", "Student", UserRole.STUDENT));
        return new IssuedAuthSession(response, refreshToken);
    }

    private static final class StubAuthRefreshTx extends AuthRefreshTx {
        private final Deque<Supplier<RefreshOutcome>> refreshSteps = new ArrayDeque<>();
        private final Deque<Supplier<RefreshOutcome>> terminateSteps = new ArrayDeque<>();
        private final Deque<Runnable> logoutSteps = new ArrayDeque<>();
        private int refreshCalls;
        private int terminateCalls;
        private int logoutCalls;

        private StubAuthRefreshTx() {
            super(null, null, null, null);
        }

        @Override
        public RefreshOutcome refreshInTx(String rawRefreshToken) {
            refreshCalls++;
            return refreshSteps.removeFirst().get();
        }

        @Override
        public RefreshOutcome terminateLiveFamilyInTx(String rawRefreshToken) {
            terminateCalls++;
            return terminateSteps.removeFirst().get();
        }

        @Override
        public void logoutInTx(String rawRefreshToken) {
            logoutCalls++;
            logoutSteps.removeFirst().run();
        }
    }
}
