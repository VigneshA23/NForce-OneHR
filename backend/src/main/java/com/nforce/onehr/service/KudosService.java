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

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "Appreciate your lead" / peer kudos (ONEHR-73). Deliberately scoped: you may only appreciate
 * your own current reporting manager or a current peer (same-manager sibling) — not the whole
 * company directory — since that's the only relationship the My Team page actually shows you.
 */
@Service
@RequiredArgsConstructor
public class KudosService {

    private final KudosRepository kudosRepository;
    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeManagerHistoryRepository historyRepository;
    private final NotificationService notificationService;

    @Transactional
    public KudosResponse send(SendKudosRequest req, String actorEmail) {
        User from = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new IllegalStateException("User not found"));

        if (from.getId().equals(req.getToUserId())) {
            throw new IllegalArgumentException("You can't appreciate yourself");
        }
        if (!isManagerOrPeerOf(from.getId(), req.getToUserId())) {
            throw new AccessDeniedException("You can only appreciate your reporting manager or a current peer");
        }

        User to = userRepository.findById(req.getToUserId())
                .orElseThrow(() -> new IllegalArgumentException("Recipient not found"));

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
                "/my-team");

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

    /** True if `toId` is `fromId`'s current reporting manager, or a current peer (same-manager sibling). */
    private boolean isManagerOrPeerOf(UUID fromId, UUID toId) {
        boolean isManager = historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(fromId)
                .map(h -> h.getManagerUserId().equals(toId))
                .orElse(false);
        return isManager || historyRepository.findCurrentPeerIds(fromId).contains(toId);
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
