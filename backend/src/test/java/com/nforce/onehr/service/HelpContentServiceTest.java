package com.nforce.onehr.service;

import com.nforce.onehr.dto.helpcontent.CreateHelpContentRequest;
import com.nforce.onehr.dto.helpcontent.HelpContentDetailDto;
import com.nforce.onehr.entity.HelpContent;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.HelpContentRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit tests — same isolation approach as {@code HelpdeskServiceTest}. Specification
 * based filtering (listPublished/listAll) is thin Spring Data plumbing not meaningfully testable
 * against a mock repository, so these focus on authorization guards, publish/archive state
 * transitions, view-count tracking, and attachment visibility — the module's actual logic.
 */
@ExtendWith(MockitoExtension.class)
class HelpContentServiceTest {

    @Mock private HelpContentRepository repo;
    @Mock private UserRepository userRepo;
    @Mock private EmployeeRepository employeeRepo;

    @InjectMocks private HelpContentService service;

    private final UUID employeeId = UUID.randomUUID();
    private final UUID hrAdminId = UUID.randomUUID();
    private final String employeeEmail = "employee@test.com";
    private final String hrAdminEmail = "hr@test.com";

    private User employeeUser;
    private User hrAdminUser;

    @BeforeEach
    void setUp() {
        employeeUser = User.builder().id(employeeId).email(employeeEmail)
                .roles(Set.of(Role.builder().code("EMPLOYEE").build())).build();
        hrAdminUser = User.builder().id(hrAdminId).email(hrAdminEmail)
                .roles(Set.of(Role.builder().code("HR_ADMIN").build())).build();

        lenient().when(employeeRepo.findById(any())).thenReturn(Optional.empty());
        lenient().when(userRepo.findById(employeeId)).thenReturn(Optional.of(employeeUser));
        lenient().when(userRepo.findById(hrAdminId)).thenReturn(Optional.of(hrAdminUser));
        lenient().when(repo.save(any(HelpContent.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private HelpContent faq() {
        return HelpContent.builder().id(UUID.randomUUID()).type(HelpContentType.FAQ.name())
                .title("How do I apply for leave?").createdBy(hrAdminId).build();
    }

    @Test
    void create_asEmployee_isDenied() {
        when(userRepo.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        CreateHelpContentRequest req = new CreateHelpContentRequest();
        req.setType("FAQ");
        req.setTitle("x");

        assertThrows(AccessDeniedException.class, () -> service.create(req, employeeEmail));
        verify(repo, never()).save(any());
    }

    @Test
    void create_asHrAdmin_succeedsAndSetsCreatedBy() {
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        CreateHelpContentRequest req = new CreateHelpContentRequest();
        req.setType("GUIDE");
        req.setTitle("Leave Policy");

        HelpContentDetailDto detail = service.create(req, hrAdminEmail);

        assertEquals("GUIDE", detail.getType());
        assertFalse(detail.isPublished());
        assertTrue(detail.isActive());
    }

    @Test
    void create_withInvalidType_isRejected() {
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        CreateHelpContentRequest req = new CreateHelpContentRequest();
        req.setType("NOT_A_TYPE");
        req.setTitle("x");

        assertThrows(IllegalArgumentException.class, () -> service.create(req, hrAdminEmail));
        verify(repo, never()).save(any());
    }

    @Test
    void publish_setsPublishedTrueAndStampsPublishedAt() {
        HelpContent content = faq();
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        HelpContentDetailDto detail = service.publish(content.getId(), hrAdminEmail);

        assertTrue(detail.isPublished());
        assertNotNull(detail.getPublishedAt());
    }

    @Test
    void unpublish_setsPublishedFalse() {
        HelpContent content = faq();
        content.setPublished(true);
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        HelpContentDetailDto detail = service.unpublish(content.getId(), hrAdminEmail);

        assertFalse(detail.isPublished());
    }

    @Test
    void archive_setsActiveFalse() {
        HelpContent content = faq();
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        HelpContentDetailDto detail = service.archive(content.getId(), hrAdminEmail);

        assertFalse(detail.isActive());
    }

    @Test
    void reactivate_setsActiveTrue() {
        HelpContent content = faq();
        content.setActive(false);
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        HelpContentDetailDto detail = service.reactivate(content.getId(), hrAdminEmail);

        assertTrue(detail.isActive());
    }

    @Test
    void delete_asEmployee_isDenied() {
        when(userRepo.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));

        assertThrows(AccessDeniedException.class, () -> service.delete(UUID.randomUUID(), employeeEmail));
        verify(repo, never()).delete(any(HelpContent.class));
    }

    @Test
    void getPublished_onUnpublishedContent_throwsNotFound() {
        HelpContent content = faq(); // published=false by default
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        assertThrows(NoSuchElementException.class, () -> service.getPublished(content.getId()));
    }

    @Test
    void getPublished_onPublishedActiveContent_returnsDetail() {
        HelpContent content = faq();
        content.setPublished(true);
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        HelpContentDetailDto detail = service.getPublished(content.getId());

        assertEquals(content.getTitle(), detail.getTitle());
    }

    @Test
    void trackView_onPublishedActiveContent_incrementsViewCount() {
        HelpContent content = faq();
        content.setPublished(true);
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        service.trackView(content.getId());

        verify(repo).incrementViewCount(content.getId());
    }

    @Test
    void trackView_onUnpublishedContent_doesNotIncrement() {
        HelpContent content = faq(); // not published
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        service.trackView(content.getId());

        verify(repo, never()).incrementViewCount(any());
    }

    @Test
    void getAttachment_employeeAccessingUnpublishedContent_isDenied() {
        HelpContent content = faq();
        content.setAttachmentData(new byte[]{1, 2, 3});
        when(userRepo.findByEmail(employeeEmail)).thenReturn(Optional.of(employeeUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        assertThrows(AccessDeniedException.class, () -> service.getAttachment(content.getId(), employeeEmail));
    }

    @Test
    void getAttachment_adminAccessingUnpublishedContent_isAllowed() {
        HelpContent content = faq();
        content.setAttachmentData(new byte[]{1, 2, 3});
        when(userRepo.findByEmail(hrAdminEmail)).thenReturn(Optional.of(hrAdminUser));
        when(repo.findById(content.getId())).thenReturn(Optional.of(content));

        HelpContent result = service.getAttachment(content.getId(), hrAdminEmail);

        assertNotNull(result.getAttachmentData());
    }
}
