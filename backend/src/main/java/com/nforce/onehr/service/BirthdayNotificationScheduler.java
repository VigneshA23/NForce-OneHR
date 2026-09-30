package com.nforce.onehr.service;

import com.nforce.onehr.dto.BirthdayEntryDto;
import com.nforce.onehr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Once daily: notifies each employee whose birthday is today, and separately notifies HR/Super
 * Admin with an aggregate count so they can coordinate a celebration — mirrors
 * {@link PenaltyEvaluationScheduler}'s "single daily cron, configurable, log-and-let-the-next-run-
 * retry" shape, the established convention for a background job in this codebase (there were only
 * two others before this one; see that class's own doc comment for the full history).
 *
 * Reuses {@link EmployeeService#listUpcomingBirthdays()} — the same query the dashboard's
 * Birthdays widget already calls — filtered to {@code today}, rather than a second birthday query;
 * no new date-matching logic is introduced here at all.
 *
 * No new-day dedup table: like both existing schedulers, this relies on the cron firing once a
 * day. A person's own repeat-visibility of the celebration is separately gated client-side (once
 * per email per calendar day, in `frontend/src/lib/birthday.ts`), so an extra scheduler run in the
 * same day would at most duplicate a bell notification, not the full-screen celebration.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BirthdayNotificationScheduler {

    private final EmployeeService employeeService;
    private final NotificationService notificationService;
    private final UserRepository userRepository;

    @Scheduled(cron = "${app.birthday.notification.cron:0 0 2 * * *}")
    public void run() {
        List<BirthdayEntryDto> today;
        try {
            today = employeeService.listUpcomingBirthdays().stream()
                    .filter(BirthdayEntryDto::isToday)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Scheduled birthday lookup failed", e);
            return;
        }
        if (today.isEmpty()) return;

        log.info("Sending birthday notifications for {} employee(s)", today.size());
        for (BirthdayEntryDto entry : today) {
            try {
                notificationService.send(UUID.fromString(entry.getUserId()), "BIRTHDAY",
                        "Happy Birthday! 🎂",
                        "Wishing you a wonderful day, " + entry.getFullName() + "!",
                        null);
            } catch (Exception e) {
                // One bad row must never stop the rest of today's birthdays (or the HR digest
                // below) from being notified — same "log and move on" tolerance
                // PenaltyEvaluationScheduler and StaleAttendanceSweeper already apply per-item.
                log.error("Failed to send birthday notification for user {}", entry.getUserId(), e);
            }
        }

        try {
            Set<UUID> admins = userRepository.findActiveAdminUserIds();
            String names = today.stream().map(BirthdayEntryDto::getFullName).collect(Collectors.joining(", "));
            String title = today.size() == 1
                    ? "🎉 1 employee has a birthday today"
                    : "🎉 " + today.size() + " employees have birthdays today";
            for (UUID adminId : admins) {
                notificationService.send(adminId, "BIRTHDAY_TODAY_HR", title, names, null);
            }
        } catch (Exception e) {
            log.error("Failed to send HR birthday digest", e);
        }
    }
}
