package com.nforce.onehr.service;

import com.nforce.onehr.dto.NotificationDto;
import com.nforce.onehr.entity.Notification;
import com.nforce.onehr.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Notification bell: unread-only listing, unread count, mark-one/mark-all read. */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;

    private NotificationService service;
    private final UUID userId = UUID.randomUUID();

    private Notification notification(Long id, boolean read) {
        return Notification.builder()
                .id(id)
                .userId(userId)
                .type("ACCOUNT")
                .title("Title " + id)
                .message("Message " + id)
                .linkPath("/somewhere")
                .read(read)
                .createdAt(Instant.now())
                .build();
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new NotificationService(notificationRepository);
    }

    @Test
    void getUnreadNotifications_returnsOnlyUnread_mappedToDto() {
        Notification unread = notification(1L, false);
        when(notificationRepository.findByUserIdAndReadFalseOrderByCreatedAtDesc(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(unread)));

        Page<NotificationDto> result = service.getUnreadNotifications(userId, 0, 8);

        assertEquals(1, result.getTotalElements());
        NotificationDto dto = result.getContent().get(0);
        assertEquals(1L, dto.getId());
        assertFalse(dto.isRead());
        assertEquals("Title 1", dto.getTitle());
    }

    @Test
    void getUnreadNotifications_delegatesPagingToRepository() {
        when(notificationRepository.findByUserIdAndReadFalseOrderByCreatedAtDesc(eq(userId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.getUnreadNotifications(userId, 2, 5);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationRepository).findByUserIdAndReadFalseOrderByCreatedAtDesc(eq(userId), pageableCaptor.capture());
        assertEquals(PageRequest.of(2, 5), pageableCaptor.getValue());
    }

    @Test
    void getUnreadCount_delegatesToRepository() {
        when(notificationRepository.countByUserIdAndReadFalse(userId)).thenReturn(3L);

        assertEquals(3L, service.getUnreadCount(userId));
    }

    @Test
    void markRead_onlyFlipsNotificationOwnedByCaller() {
        Notification owned = notification(1L, false);
        when(notificationRepository.findById(1L)).thenReturn(java.util.Optional.of(owned));

        service.markRead(1L, userId);

        assertTrue(owned.isRead());
        verify(notificationRepository).save(owned);
    }

    @Test
    void markRead_ignoresNotificationOwnedByAnotherUser() {
        Notification other = notification(1L, false);
        other.setUserId(UUID.randomUUID());
        when(notificationRepository.findById(1L)).thenReturn(java.util.Optional.of(other));

        service.markRead(1L, userId);

        assertFalse(other.isRead());
        verify(notificationRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void markAllRead_delegatesBulkUpdateToRepository() {
        service.markAllRead(userId);

        verify(notificationRepository).markAllReadByUserId(userId);
    }
}
