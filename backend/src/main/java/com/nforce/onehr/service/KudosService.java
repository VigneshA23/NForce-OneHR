package com.nforce.onehr.service;

import com.nforce.onehr.dto.KudosResponse;
import com.nforce.onehr.dto.SendKudosRequest;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.Kudos;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.KudosRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "Appreciate your lead" / peer kudos (ONEHR-73), extended (ONEHR: Managers and HR/Super Admin
 * Can Give Appreciation) so the eligible-recipient rules now differ by the sender's role:
 * <ul>
 *   <li>HR_ADMIN / SUPER_ADMIN — any active, currently-employed person org-wide (see
 *       {@link #ORG_WIDE_KUDOS_ROLES}), not just their own reporting line.</li>
 *   <li>Everyone else (Manager and plain Employee alike) — unchanged relationship scope: their
 *       own current reporting manager, a current peer (same-manager sibling), or — newly — one of
 *       their own current direct reports (see {@link #isCurrentManagerOf}). A plain Employee with
 *       no direct reports simply never satisfies that last clause, so their effective restriction
 *       is unchanged.</li>
 * </ul>
 * A recipient who is deactivated ({@link User#isActive()}) or already past their {@link
 * Employee#getLastWorkingDay()} is rejected regardless of the sender's role — mirrors
 * EmployeeService#listUpcomingBirthdays' reasoning for preferring lastWorkingDay over active
 * alone.
 */
@Service
@RequiredArgsConstructor
public class KudosService {

    private final KudosRepository kudosRepository;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository historyRepository;
    private final NotificationService notificationService;

    private static final Set<String> ORG_WIDE_KUDOS_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");

    @Transactional
    public KudosResponse send(SendKudosRequest req, String actorEmail) {
        User from = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("User not found"));

        if (from.getId().equals(req.getToUserId())) {
            throw new IllegalArgumentException("You can't appreciate yourself");
        }

        User to = userRepository.findById(req.getToUserId())
                .orElseThrow(() -> new IllegalArgumentException("Recipient not found"));

        if (!isEligibleRecipient(to)) {
            throw new IllegalArgumentException("You can't appreciate a deactivated or offboarded employee");
        }
        if (!canAppreciate(from, to.getId())) {
            throw new AccessDeniedException("You can only appreciate your reporting manager, a direct report, or a current peer");
        }

        Kudos saved = kudosRepository.save(Kudos.builder()
                .fromUserId(from.getId())
                .toUserId(to.getId())
                .category(req.getCategory())
                .note(req.getNote())
                .build());

        String fromName = employeeRepository.findById(from.getId())
                .map(Employee::getFullName).orElse(from.getEmail());
        String note = req.getNote() != null && !req.getNote().isBlank() ? ": " + req.getNote().trim() : "";
        notificationService.send(to.getId(), "KUDOS",
                "You've been appreciated 🎉",
                fromName + " sent you kudos for \"" + req.getCategory() + "\"" + note,
                // No linkPath: nothing in OneHR displays received kudos. KudosController exposes
                // /received and the API client has kudosApi.received, but no page renders either, so
                // there is no destination to offer. This previously pointed at /my-team, which is
                // where kudos are SENT from - the recipient landed on a team roster with no mention
                // of the appreciation they had just been notified about.
                //
                // NotificationsPage hides the "Open related page" button when linkPath is null, so
                // the notification simply reads as the complete message it already is. Give it a
                // real path the day a page exists to show these.
                null);

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<KudosResponse> listReceived(String actorEmail) {
        UUID userId = requireUserId(actorEmail);
        return kudosRepository.findByToUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<KudosResponse> listSent(String actorEmail) {
        UUID userId = requireUserId(actorEmail);
        return kudosRepository.findByFromUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private UUID requireUserId(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("User not found"))
                .getId();
    }

    /**
     * HR_ADMIN/SUPER_ADMIN may appreciate any eligible recipient org-wide — {@link
     * #isEligibleRecipient} has already ruled out deactivated/offboarded recipients by this
     * point, so no further relationship check is needed for those roles. Everyone else keeps the
     * existing relationship-scoped rule, now including their own current direct reports.
     */
    private boolean canAppreciate(User from, UUID toId) {
        if (from.getRoles().stream().anyMatch(r -> ORG_WIDE_KUDOS_ROLES.contains(r.getCode()))) {
            return true;
        }
        return isManagerOrPeerOf(from.getId(), toId) || isCurrentManagerOf(from.getId(), toId);
    }

    /** True if `toId` is `fromId`'s current reporting manager, or a current peer (same-manager sibling). */
    private boolean isManagerOrPeerOf(UUID fromId, UUID toId) {
        boolean isManager = historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(fromId)
                .map(h -> h.getManagerUserId().equals(toId))
                .orElse(false);
        return isManager || historyRepository.findCurrentPeerIds(fromId).contains(toId);
    }

    /** True if `toId` is currently a direct report of `fromId` — the new "Manager -> direct report" case. */
    private boolean isCurrentManagerOf(UUID fromId, UUID toId) {
        return historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(toId)
                .map(h -> h.getManagerUserId().equals(fromId))
                .orElse(false);
    }

    /**
     * False for a deactivated user ({@link User#isActive()}) or one already past their {@link
     * Employee#getLastWorkingDay()} — same "no longer really employed" signal used elsewhere
     * (see EmployeeService#listUpcomingBirthdays). An employee still within their notice period
     * (lastWorkingDay in the future, or unset) remains a valid recipient.
     */
    private boolean isEligibleRecipient(User to) {
        if (!to.isActive()) return false;
        LocalDate today = LocalDate.now();
        return employeeRepository.findById(to.getId())
                .map(e -> e.getLastWorkingDay() == null || !today.isAfter(e.getLastWorkingDay()))
                .orElse(true);
    }

    private KudosResponse toResponse(Kudos k) {
        return KudosResponse.builder()
                .id(k.getId())
                .fromUserId(k.getFromUserId().toString())
                .fromName(displayName(k.getFromUserId()))
                .toUserId(k.getToUserId().toString())
                .toName(displayName(k.getToUserId()))
                .category(k.getCategory())
                .note(k.getNote())
                .createdAt(k.getCreatedAt())
                .build();
    }

    private String displayName(UUID userId) {
        return employeeRepository.findById(userId).map(Employee::getFullName)
                .orElseGet(() -> userRepository.findById(userId).map(User::getEmail).orElse("Unknown"));
    }
}
