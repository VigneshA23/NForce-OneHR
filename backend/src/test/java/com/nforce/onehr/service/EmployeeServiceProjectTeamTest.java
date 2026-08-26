package com.nforce.onehr.service;

import com.nforce.onehr.dto.DirectoryEntryDto;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.DepartmentRepository;
import com.nforce.onehr.repository.DesignationRepository;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.repository.LocationRepository;
import com.nforce.onehr.repository.RoleRepository;
import com.nforce.onehr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit tests for {@link EmployeeService#listPeers} — "My Team: Project Team"
 * (ONEHR-73). Verifies the service layer correctly turns whatever id set
 * {@link EmployeeManagerHistoryRepository#findCurrentPeerIds} returns into the directory
 * entries the frontend renders, for both a "same reporting manager" group (which must include
 * the caller themself) and a "different reporting manager" / team-of-one case.
 *
 * Deliberately does NOT attempt to exercise the JPQL in findCurrentPeerIds itself against a
 * real database — see LeaveServiceTest's header comment for why this repo's H2 test profile
 * can't run @DataJpaTest against the citext-typed entity graph. The query's own same-manager
 * (including self) vs. different-manager behavior is verified by direct trace of the JOIN
 * predicate; this suite is the regression guard for the service layer built on top of it.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeServiceProjectTeamTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private EmployeeManagerHistoryRepository historyRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private DesignationRepository designationRepository;
    @Mock private LocationRepository locationRepository;

    @InjectMocks private EmployeeService employeeService;

    private final UUID selfId = UUID.randomUUID();
    private final UUID peerAId = UUID.randomUUID();
    private final UUID peerBId = UUID.randomUUID();
    private final String selfEmail = "self@test.com";

    private Employee employee(UUID id, String email, String name) {
        User user = User.builder().id(id).email(email).active(true).build();
        return Employee.builder().userId(id).user(user).employeeCode(email).fullName(name).build();
    }

    @BeforeEach
    void setUp() {
        lenient().when(employeeRepository.findByUser_Email(selfEmail))
                .thenReturn(Optional.of(employee(selfId, selfEmail, "Self Employee")));
        // findCurrentManager() is exercised as a side effect of listPeers — no manager data is
        // under test here, so it's stubbed to "no manager" for every id EXCEPT the caller: both
        // tests below simulate a caller who currently has a manager (that's what puts them in a
        // Project Team at all — see listPeers' own "no manager → no team" guard, which reuses
        // this same repository call).
        lenient().when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        lenient().when(historyRepository.findByEmployeeUserIdAndEffectiveToIsNull(selfId))
                .thenReturn(Optional.of(EmployeeManagerHistory.builder()
                        .employeeUserId(selfId).managerUserId(UUID.randomUUID()).build()));
    }

    @Test
    void sameReportingManager_includesSelfAndSiblings() {
        // findCurrentPeerIds now deliberately includes the caller (see the repository's own
        // doc comment) — simulating exactly what the same-manager JOIN produces for a group of 3.
        when(historyRepository.findCurrentPeerIds(selfId)).thenReturn(List.of(selfId, peerAId, peerBId));
        when(employeeRepository.findAllById(List.of(selfId, peerAId, peerBId))).thenReturn(List.of(
                employee(selfId, selfEmail, "Self Employee"),
                employee(peerAId, "peera@test.com", "Peer A"),
                employee(peerBId, "peerb@test.com", "Peer B")
        ));

        List<DirectoryEntryDto> result = employeeService.listPeers(selfEmail);

        assertEquals(3, result.size(), "Project Team should include the caller plus both siblings");
        assertTrue(result.stream().anyMatch(e -> e.getUserId().equals(selfId.toString())),
                "Caller must appear in their own Project Team");
        assertTrue(result.stream().anyMatch(e -> e.getFullName().equals("Peer A")));
        assertTrue(result.stream().anyMatch(e -> e.getFullName().equals("Peer B")));
    }

    @Test
    void differentReportingManager_onlySelfNoUnrelatedEmployees() {
        // A caller who doesn't share a manager with peerA/peerB (e.g. a different team, or no
        // manager assigned) — the JOIN only ever returns rows sharing the caller's OWN manager,
        // so peerA/peerB must never leak in here regardless of what's mocked for them elsewhere.
        when(historyRepository.findCurrentPeerIds(selfId)).thenReturn(List.of(selfId));
        when(employeeRepository.findAllById(List.of(selfId))).thenReturn(List.of(
                employee(selfId, selfEmail, "Self Employee")
        ));

        List<DirectoryEntryDto> result = employeeService.listPeers(selfEmail);

        assertEquals(1, result.size(), "A team-of-one Project Team is still just the caller — no unrelated employees");
        assertEquals(selfId.toString(), result.get(0).getUserId());
    }
}
