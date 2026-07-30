package com.nforce.onehr.service;

import com.nforce.onehr.dto.NotificationDto;
import com.nforce.onehr.entity.Notification;
import com.nforce.onehr.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;

    /**
     * Create a notification. Always silent — exceptions are caught so a notification
     * failure never aborts the calling transaction.
     */
    public void send(UUID userId, String type, String title, String message, String linkPath) {
        try {
            notificationRepository.save(Notification.builder()
                    .userId(userId)
                    .type(type)
                    .title(title)
                    .message(message)
                    .linkPath(linkPath)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to persist notification for user {}: {}", userId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Page<NotificationDto> getNotifications(UUID userId, int page, int size) {
        return notificationRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size))
                .map(this::toDto);
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(UUID userId) {
        return notificationRepository.countByUserIdAndReadFalse(userId);
    }

    @Transactional
    public void markRead(Long notificationId, UUID userId) {
        notificationRepository.findById(notificationId).ifPresent(n -> {
            if (n.getUserId().equals(userId)) {
                n.setRead(true);
                notificationRepository.save(n);
            }
        });
    }

    @Transactional
    public void markAllRead(UUID userId) {
        notificationRepository.markAllReadByUserId(userId);
    }

    private NotificationDto toDto(Notification n) {
        return NotificationDto.builder()
                .id(n.getId())
                .type(n.getType())
                .title(n.getTitle())
                .message(n.getMessage())
                .linkPath(n.getLinkPath())
                .read(n.isRead())
                .createdAt(n.getCreatedAt())
                .build();
    }
}
