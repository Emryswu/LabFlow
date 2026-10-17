package com.arthur.labops.dashboard;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * Loads only the timestamp column of rows inside a time window, so the
 * per-day counting in {@link DailyBuckets} never hydrates whole entities.
 * The window is bounded (two weeks), which bounds the rows returned.
 */
@Component
class DashboardTrendQuery {

    private final EntityManager entityManager;

    DashboardTrendQuery(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    <T> List<Instant> timestamps(Class<T> entity, String timeField, Instant from, Instant to, Filter<T> filter) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Instant> query = builder.createQuery(Instant.class);
        Root<T> root = query.from(entity);
        Path<Instant> time = root.get(timeField);
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(builder.greaterThanOrEqualTo(time, from));
        predicates.add(builder.lessThan(time, to));
        predicates.addAll(filter.predicates(root, builder));
        query.select(time).where(predicates.toArray(Predicate[]::new));
        return entityManager.createQuery(query).getResultList();
    }

    /** Extra conditions on top of the time window, e.g. "only my own reservations". */
    @FunctionalInterface
    interface Filter<T> {

        List<Predicate> predicates(Root<T> root, CriteriaBuilder builder);

        static <T> Filter<T> none() {
            return (root, builder) -> List.of();
        }
    }
}
