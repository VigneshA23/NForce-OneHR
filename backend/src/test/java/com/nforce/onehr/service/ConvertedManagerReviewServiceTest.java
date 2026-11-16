package com.nforce.onehr.service;

import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConvertedManagerReviewServiceTest {

    @Mock private JdbcTemplate jdbc;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @InjectMocks private ConvertedManagerReviewService service;

    private final UUID adminId = UUID.randomUUID();
    private final UUID personId = UUID.randomUUID();

    private void actor() {
        when(userRepository.findByEmail("admin@t")).thenReturn(Optional.of(User.builder().id(adminId).build()));
    }

    @Test
    void markReviewed_unknownPerson_is404_andWritesNothing() {
        actor();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(personId))).thenReturn(0);

        assertThrows(NoSuchElementException.class, () -> service.markReviewed(personId, "admin@t"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verifyNoInteractions(auditService);
    }

    @Test
    void markReviewed_firstTime_updatesAndAudits() {
        actor();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(personId))).thenReturn(1);
        when(jdbc.update(anyString(), eq(adminId), eq(personId))).thenReturn(1);

        service.markReviewed(personId, "admin@t");

        verify(auditService).log(adminId, "ROLE_MANAGER_CONVERSION_REVIEWED", personId);
    }

    @Test
    void markReviewed_again_keepsTheFirstReview_andDoesNotAuditTwice() {
        actor();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(personId))).thenReturn(1);
        when(jdbc.update(anyString(), eq(adminId), eq(personId))).thenReturn(0);

        service.markReviewed(personId, "admin@t");

        verifyNoInteractions(auditService);
    }
}
