package com.nforce.onehr.service;

import com.nforce.onehr.dto.SendKudosRequest;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.Kudos;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.KudosRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a kudos notification links to.
 *
 * <p>It used to link to {@code /my-team}, which is where kudos are <em>sent</em> from. The
 * recipient clicked "Open related page" on "You've been appreciated" and landed on a team roster
 * with no mention of the appreciation — the page was not merely unhelpful, it was about somebody
 * else entirely.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KudosNotificationTest {

    @Mock private KudosRepository kudosRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private NotificationService notificationService;

    @InjectMocks private KudosService kudosService;

    private final UUID senderId = UUID.randomUUID();
    private final UUID recipientId = UUID.randomUUID();
    private final String senderEmail = "sender@nforceone.com";

    @BeforeEach
    void setUp() {
        User sender = User.builder().id(senderId).email(senderEmail).build();
        User recipient = User.builder().id(recipientId).email("recipient@nforceone.com").build();

        when(userRepository.findByEmail(senderEmail)).thenReturn(Optional.of(sender));
        when(userRepository.findById(recipientId)).thenReturn(Optional.of(recipient));
        when(employeeRepository.findById(senderId)).thenReturn(Optional.of(
                Employee.builder().userId(senderId).fullName("Vyshnavi Guthala").build()));

        // The recipient is the sender's reporting manager, which is one of the two relationships
        // KudosService permits.
        when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(senderId)).thenReturn(Optional.of(
                EmployeeManagerHistory.builder().employeeUserId(senderId).managerUserId(recipientId).build()));

        when(kudosRepository.save(any(Kudos.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private SendKudosRequest request() {
        SendKudosRequest req = new SendKudosRequest();
        req.setToUserId(recipientId);
        req.setCategory("Great Work");
        return req;
    }

    @Test
    @DisplayName("a kudos notification offers no related page, because none exists")
    void kudosNotificationHasNoLinkPath() {
        kudosService.send(request(), senderEmail);

        ArgumentCaptor<String> linkPath = ArgumentCaptor.forClass(String.class);
        verify(notificationService).send(eq(recipientId), eq("KUDOS"), anyString(), anyString(),
                linkPath.capture());

        // Null rather than a guess. KudosController exposes /received and kudosApi has received(),
        // but no page renders either, so there is genuinely nowhere to send them. NotificationsPage
        // hides the button when linkPath is null, which is the behaviour the bug report asked for
        // when no related page exists.
        assertThat(linkPath.getValue()).isNull();
    }

    @Test
    @DisplayName("it specifically does not point at My Team")
    void kudosNeverLinksToMyTeam() {
        kudosService.send(request(), senderEmail);

        ArgumentCaptor<String> linkPath = ArgumentCaptor.forClass(String.class);
        verify(notificationService).send(any(UUID.class), anyString(), anyString(), anyString(),
                linkPath.capture());

        // The exact regression: /my-team is where kudos are sent FROM. Pinned separately from the
        // null assertion so that if somebody later adds a real destination, this still guards
        // against reaching for the old wrong one.
        assertThat(linkPath.getValue()).isNotEqualTo("/my-team");
    }

    @Test
    @DisplayName("the notification still names who sent it and what for")
    void notificationStillCarriesTheAppreciation() {
        kudosService.send(request(), senderEmail);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService).send(any(UUID.class), anyString(), anyString(),
                message.capture(), any());

        // With no page to open, the notification text is the whole of what the recipient gets, so
        // it has to stand on its own.
        assertThat(message.getValue()).contains("Vyshnavi Guthala").contains("Great Work");
    }

    @Test
    @DisplayName("a note from the sender reaches the recipient")
    void noteIsIncluded() {
        SendKudosRequest req = request();
        req.setNote("Thanks for staying late on the release");

        kudosService.send(req, senderEmail);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService).send(any(UUID.class), anyString(), anyString(),
                message.capture(), any());

        assertThat(message.getValue()).contains("Thanks for staying late on the release");
    }

    @Test
    @DisplayName("unused list endpoints exist, which is why there is no page to link to")
    void receivedKudosAreQueryableButNotRendered() {
        when(kudosRepository.findByToUserIdOrderByCreatedAtDesc(recipientId)).thenReturn(List.of());
        when(userRepository.findByEmail("recipient@nforceone.com")).thenReturn(Optional.of(
                User.builder().id(recipientId).email("recipient@nforceone.com").build()));

        // Documents why linkPath is null rather than pointing somewhere: the data is reachable, the
        // UI for it simply does not exist yet. When a page is built, this test is the breadcrumb
        // back to the decision.
        assertThat(kudosService.listReceived("recipient@nforceone.com")).isEmpty();
    }
}
