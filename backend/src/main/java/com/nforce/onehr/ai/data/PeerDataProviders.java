package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.dto.AttendanceResponse;
import com.nforce.onehr.dto.LeaveRequestResponse;
import com.nforce.onehr.service.AttendanceRulesService;
import com.nforce.onehr.service.AttendanceService;
import com.nforce.onehr.service.LeaveService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The employee's own project team - the colleagues My Team shows them, with attendance and leave. */
public final class PeerDataProviders {

    private PeerDataProviders() {}

    /**
     * Today's attendance and the next two weeks' approved leave for everyone who shares the
     * caller's manager, through the same two reads as My Team's peers view
     * ({@code getDayForPeers}, {@code listPeerLeave}), both of which resolve the group from the actor.
     *
     * <p>Stands aside for a Manager, HR Admin or Super Admin: to them "my team" means the people who
     * report to them, which {@code TeamDataProviders} and {@code OrganisationDataProviders} answer,
     * and a second, differently-shaped "team" in the same turn would only invite the wrong one.
     */
    @Component
    @RequiredArgsConstructor
    public static class PeerTeam implements AssistantDataProvider {

        private static final int WINDOW_DAYS = 14;

        private final AttendanceService attendanceService;
        private final LeaveService leaveService;
        private final AttendanceRulesService attendanceRulesService;

        @Override public String id() { return "peers.team"; }
        @Override public DataScope scope() { return DataScope.PEERS; }
        @Override public String title() { return "Your project team (colleagues who share your manager): attendance today and upcoming leave"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.EMPLOYEE); }
        @Override public Set<String> modules() { return Set.of("peers", "people"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            Set<AudienceBucket> audiences = context.getAudiences();
            if (audiences.contains(AudienceBucket.MANAGER) || audiences.contains(AudienceBucket.HR)
                    || audiences.contains(AudienceBucket.ADMIN)) {
                return Optional.empty();
            }
            String email = context.getActorEmail();
            LocalDate today = LocalDate.now(attendanceRulesService.getDefaultZoneId());
            List<AttendanceResponse> roster = attendanceService.getDayForPeers(email, today);
            if (roster == null || roster.isEmpty()) {
                return Optional.of("You have no project team right now: no reporting manager is assigned to you.");
            }
            List<LeaveRequestResponse> leave = leaveService.listPeerLeave(email, today, today.plusDays(WINDOW_DAYS - 1));
            return Optional.of("Your project team is everyone who shares your manager, you included (%d people)."
                    .formatted(roster.size())
                    + "\n" + TeamDataProviders.rosterSummary(roster, today, "people in your project team",
                            "the check-ins My Team shows for today")
                    + "\n" + TeamDataProviders.leaveSummary(leave, today, WINDOW_DAYS, "person(s) in your project team"));
        }
    }
}
