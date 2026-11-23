package com.nforce.onehr.repository;

import com.nforce.onehr.entity.LeaveType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeaveTypeRepository extends JpaRepository<LeaveType, UUID> {

    Optional<LeaveType> findByCode(String code);

    boolean existsByCodeIgnoreCase(String code);

    List<LeaveType> findByActiveTrueOrderByNameAsc();

    List<LeaveType> findAllByOrderByNameAsc();

    /**
     * Every record that still points at a leave type, by id or by code — the deletion safeguard
     * (V309). Balances, requests, occurrences and carry-forward history reference the id; payroll
     * no-attendance review items reference it by id without a FK; penalization policy versions
     * (leave_priority_order, comma-separated codes) and attendance penalties (leave_breakdown JSON,
     * keyed by code) reference the code. Anything above zero means "deactivate, don't delete".
     */
    @Query(value = """
            SELECT (SELECT COUNT(*) FROM leave_balances WHERE leave_type_id = :id)
                 + (SELECT COUNT(*) FROM leave_requests WHERE leave_type_id = :id)
                 + (SELECT COUNT(*) FROM leave_occurrences WHERE leave_type_id = :id)
                 + (SELECT COUNT(*) FROM leave_carry_forward_transfers WHERE leave_type_id = :id)
                 + (SELECT COUNT(*) FROM payroll_run_review_items WHERE selected_leave_type_id = :id)
                 + (SELECT COUNT(*) FROM penalization_policy_versions
                    WHERE (',' || replace(COALESCE(leave_priority_order, ''), ' ', '') || ',') LIKE ('%,' || :code || ',%'))
                 + (SELECT COUNT(*) FROM attendance_penalties
                    WHERE leave_breakdown IS NOT NULL AND btrim(leave_breakdown) <> '' AND jsonb_exists(leave_breakdown::jsonb, :code))
            """, nativeQuery = true)
    long countReferences(@Param("id") UUID id, @Param("code") String code);
}
