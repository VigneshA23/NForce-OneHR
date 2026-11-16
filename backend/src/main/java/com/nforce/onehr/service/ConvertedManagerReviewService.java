package com.nforce.onehr.service;

import com.nforce.onehr.dto.roles.RoleDtos.ConvertedManager;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Review list for the Managers whose role was granted automatically before V269 and was converted to an
 * explicit assignment instead of being revoked. Nothing here changes anyone's access: an administrator
 * reads the list (still holds MANAGER? how many direct reports?), removes the role through the normal
 * Roles & Permissions member removal where it isn't wanted, and marks the person reviewed.
 *
 * <p>Plain SQL over manager_role_auto_grants_archive (V269/V280): the table is a one-off snapshot with no
 * entity, and is dropped once the review is finished.
 */
@Service
@RequiredArgsConstructor
public class ConvertedManagerReviewService {

    private static final String MANAGER = "MANAGER"; // the archive is, by definition, about this role

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<ConvertedManager> list() {
        return jdbc.query("""
                SELECT a.user_id, u.email, e.full_name, e.employee_code,
                       a.granted_at, a.direct_reports_at_archive, a.reviewed_at,
                       (SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                         WHERE ur.user_id = a.user_id AND r.code = ?) AS holds_manager,
                       (SELECT COUNT(*) FROM employee_manager_history h
                          JOIN users x ON x.id = h.employee_user_id AND x.deleted_at IS NULL
                         WHERE h.manager_user_id = a.user_id AND h.effective_to IS NULL) AS direct_reports_now
                  FROM manager_role_auto_grants_archive a
                  JOIN users u ON u.id = a.user_id
                  LEFT JOIN employees e ON e.user_id = a.user_id
                 ORDER BY (a.reviewed_at IS NOT NULL), LOWER(COALESCE(e.full_name, u.email))
                """,
                (rs, i) -> new ConvertedManager(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("full_name") != null ? rs.getString("full_name") : rs.getString("email"),
                        rs.getString("email"), rs.getString("employee_code"),
                        toLocal(rs.getTimestamp("granted_at")),
                        rs.getLong("holds_manager") > 0,
                        rs.getLong("direct_reports_at_archive"), rs.getLong("direct_reports_now"),
                        toLocal(rs.getTimestamp("reviewed_at"))),
                MANAGER);
    }

    /** Idempotent: re-marking an already reviewed person keeps the first review. */
    @Transactional
    public void markReviewed(UUID userId, String actorEmail) {
        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("Actor not found"));
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM manager_role_auto_grants_archive WHERE user_id = ?", Integer.class, userId);
        if (exists == null || exists == 0) throw new NoSuchElementException("This person is not in the converted-managers list");
        int updated = jdbc.update("""
                UPDATE manager_role_auto_grants_archive SET reviewed_at = now(), reviewed_by = ?
                 WHERE user_id = ? AND reviewed_at IS NULL
                """, actor.getId(), userId);
        if (updated > 0) auditService.log(actor.getId(), "ROLE_MANAGER_CONVERSION_REVIEWED", userId);
    }

    private static java.time.LocalDateTime toLocal(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }
}
