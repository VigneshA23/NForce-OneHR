package com.nforce.onehr.service;

import com.nforce.onehr.dto.BirthdayWishResponse;
import com.nforce.onehr.dto.SendBirthdayWishRequest;
import com.nforce.onehr.entity.BirthdayWish;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.BirthdayWishRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "Send Wishes" — a teammate leaving a short birthday message for whoever's birthday it is today.
 * Deliberately not built on {@link KudosService}: see V194__create_birthday_wishes.sql for why a
 * separate table, and {@link #canSendWish} below for why a separate, deliberately org-wide
 * eligibility rule (unlike Kudos' manager/peer/direct-report scoping) — a birthday wish is not a
 * relationship-scoped recognition, it's "anyone in the org may wish anyone else well on their
 * actual birthday".
 */
@Service
@RequiredArgsConstructor
public class BirthdayWishService {

    private final BirthdayWishRepository birthdayWishRepository;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeService employeeService;
    private final NotificationService notificationService;

    @Transactional
    public BirthdayWishResponse send(SendBirthdayWishRequest req, String actorEmail) {
        User from = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("User not found"));

        if (from.getId().equals(req.getToUserId())) {
            throw new IllegalArgumentException("You can't send yourself a birthday wish");
        }

        User to = userRepository.findById(req.getToUserId())
                .orElseThrow(() -> new IllegalArgumentException("Recipient not found"));

        if (!canSendWish(to)) {
            throw new IllegalArgumentException("Birthday wishes can only be sent to an active employee on their actual birthday");
        }

        BirthdayWish saved = birthdayWishRepository.save(BirthdayWish.builder()
                .fromUserId(from.getId())
                .toUserId(to.getId())
                .message(req.getMessage().trim())
                .build());

        String fromName = employeeRepository.findById(from.getId())
                .map(Employee::getFullName).orElse(from.getEmail());
        notificationService.send(to.getId(), "BIRTHDAY_WISH",
                fromName + " wished you a happy birthday 🎂",
                req.getMessage().trim(),
                null);

        return toResponse(saved, fromName);
    }

    /**
     * Wishes the caller has received today (org-wide zone, midnight to midnight) — scoped to
     * today rather than all-time, since the celebration card that shows this count/list is itself
     * only ever shown on the birthday it's celebrating.
     */
    @Transactional(readOnly = true)
    public List<BirthdayWishResponse> listReceivedToday(String actorEmail) {
        UUID userId = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("User not found"))
                .getId();
        Instant since = startOfTodayInstant();
        return birthdayWishRepository.findByToUserIdAndCreatedAtAfterOrderByCreatedAtDesc(userId, since).stream()
                .map(w -> toResponse(w, displayName(w.getFromUserId())))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public long countReceivedToday(UUID userId) {
        return birthdayWishRepository.countByToUserIdAndCreatedAtAfter(userId, startOfTodayInstant());
    }

    /**
     * Recipient must be active, not past their last working day (same "no longer really
     * employed" signal used by Kudos/EmployeeService), and — the rule that actually distinguishes
     * this from Kudos — it must genuinely be their birthday today, reusing
     * {@link EmployeeService#isBirthdayToday} rather than duplicating that date math here.
     */
    private boolean canSendWish(User to) {
        if (!to.isActive()) return false;
        boolean stillEmployed = employeeRepository.findById(to.getId())
                .map(e -> e.getLastWorkingDay() == null || !LocalDate.now().isAfter(e.getLastWorkingDay()))
                .orElse(false);
        return stillEmployed && employeeService.isBirthdayToday(to.getId());
    }

    private Instant startOfTodayInstant() {
        // UTC, not the org's attendance zone: created_at is stored/compared as an Instant, and the
        // "today" boundary only needs to be *a* stable midnight, not one tied to the same zone
        // EmployeeService uses for birthday-date math (that zone only matters for deciding which
        // calendar day a birthday falls on, not for bounding this list).
        return LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private BirthdayWishResponse toResponse(BirthdayWish w, String fromName) {
        return BirthdayWishResponse.builder()
                .id(w.getId())
                .fromUserId(w.getFromUserId().toString())
                .fromName(fromName)
                .message(w.getMessage())
                .createdAt(w.getCreatedAt())
                .build();
    }

    private String displayName(UUID userId) {
        return employeeRepository.findById(userId).map(Employee::getFullName)
                .orElseGet(() -> userRepository.findById(userId).map(User::getEmail).orElse("Unknown"));
    }
}
