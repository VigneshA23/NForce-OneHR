package com.nforce.onehr.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

/**
 * Thin wrapper around the {@code employee_code_seq} Postgres sequence (V132) — the single
 * source of truth for the numeric suffix in an Employee ID. Deliberately not a Spring Data JPA
 * repository since a sequence isn't backed by an entity; both queries below hit the sequence
 * directly.
 */
@Repository
public class EmployeeCodeSequenceRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Atomically advances the sequence and returns the new value. This is the ONLY method that
     * consumes a sequence value — call it exactly once per employee actually created, never on
     * a mere preview.
     */
    public long nextValue() {
        return ((Number) entityManager
                .createNativeQuery("SELECT nextval('employee_code_seq')")
                .getSingleResult())
                .longValue();
    }

    /**
     * Read-only peek at what {@link #nextValue()} would currently return, without consuming it.
     * Reading last_value/is_called directly off the sequence relation (standard Postgres
     * feature) rather than calling nextval() is what keeps this side-effect-free — opening the
     * Add Employee form must never reserve a sequence number.
     */
    public long peekNextValue() {
        return ((Number) entityManager
                .createNativeQuery("SELECT CASE WHEN is_called THEN last_value + 1 ELSE last_value END FROM employee_code_seq")
                .getSingleResult())
                .longValue();
    }

    /**
     * Bumps the sequence up so its next {@link #nextValue()} will be at least
     * {@code minValue + 1} — a no-op if the sequence is already past {@code minValue}. Used when
     * an admin hand-types (or accepts a suggestion for) an Employee ID whose numeric suffix is
     * higher than what the sequence itself has reached, so the next preview continues from that
     * higher value instead of leaving it behind. Per {@code employee_code_seq}'s own contract
     * (V132), this only ever moves the sequence forward, never back.
     */
    public void advanceAtLeastTo(long minValue) {
        entityManager
                .createNativeQuery("SELECT setval('employee_code_seq', GREATEST(?1, (SELECT last_value FROM employee_code_seq)), true)")
                .setParameter(1, minValue)
                .getSingleResult();
    }
}
