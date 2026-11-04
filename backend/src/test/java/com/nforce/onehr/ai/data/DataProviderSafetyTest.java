package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.SeedAuthorities;
import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.entity.Role;
import com.nforce.onehr.security.EffectivePermissionService;
import com.nforce.onehr.security.SeedMatrix;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guards on the live-data layer.
 *
 * <p>This is the part of the assistant that reads real employee records and puts them in a prompt
 * that goes to a third party. The behavioural tests cover what it does; these cover what it must
 * never become, because the dangerous version of this feature looks almost identical to the safe
 * one and would pass every functional test.
 */
class DataProviderSafetyTest {

    private static final String SCAN_ROOT = "com.nforce.onehr.ai.data";

    /**
     * Organisation-wide reads - most take no actor at all and are protected in the UI only by the
     * controller's {@code @PreAuthorize}. Only an {@link DataScope#ORGANISATION} provider, whose
     * audience the test below holds to HR/Admin, may reach one; anywhere else it would hand the model
     * an entire organisation's records, and it would look like an ordinary one-line change.
     */
    private static final Set<String> FORBIDDEN_CALLS = Set.of(
            "listorgleave", "listallassets", "listallholidays", "findall", "listall",
            "getdayforall", "listusers", "getorgdashboard", "listemployees", "listpotentialmanagers",
            "countallpendingrequired", "getadminkpis", "hrtilesummary", "countassets", "listqueue",
            // Widens to the whole organisation for HR_ADMIN/SUPER_ADMIN - the My Team AI providers
            // must reach only its never-widening counterpart, getAttendanceRequestReportForDirectReports.
            "getattendancerequestreport",
            // Every approved WFH request in the organisation - OrgAttendanceToday only.
            "listorgapprovedwfh");

    /**
     * Team reads - actor-scoped, but about the caller's direct reports rather than the caller. Only
     * a {@link DataScope#TEAM} (or organisation) provider may reach one.
     */
    private static final Set<String> TEAM_CALLS = Set.of(
            "listteamleave", "getdayformyteam", "getmonthformyteam", "getmanagerdashboard",
            "teamassignments", "teamrequests", "allteamclaims", "getteameffort", "getteampunctuality",
            "listfordirectreports", "getteamnegligence", "listteamapprovedwfh", "listteambalances",
            "getattendancerequestreportfordirectreports");

    /** Peer-group reads - actor-scoped, about everyone who shares the caller's manager. */
    private static final Set<String> PEER_CALLS = Set.of(
            "getdayforpeers", "getmonthforpeers", "listpeerleave", "listpeers");

    /**
     * Methods whose names read like reads but which write as they go. No provider may call these,
     * whatever its scope, because the assistant is read-only and the name gives no warning:
     * {@code getExceptionsForCaller} runs exception detection and can apply penalties and deduct
     * leave; {@code getToday} and {@code isClockedIn} settle a stale open session; {@code getProfile}
     * runs in a writable transaction for the same reason; {@code myDocuments} sends expiry reminders.
     */
    private static final Set<String> WRITES_AS_IT_READS = Set.of(
            "exceptionservice.getexceptionsforcaller", "attendanceservice.gettoday",
            "attendanceservice.isclockedin", "profileservice.getprofile", "documentservice.mydocuments");

    /** Anything that writes. A provider is a read; this is what keeps it one. */
    private static final Set<String> MUTATING_PREFIXES = Set.of(
            "submit", "create", "update", "delete", "approve", "reject", "cancel",
            "save", "initialize", "checkin", "checkout", "clear", "publish");

    private List<Class<?>> providerClasses() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(AssistantDataProvider.class));
        return scanner.findCandidateComponents(SCAN_ROOT).stream()
                .map(BeanDefinition::getBeanClassName)
                .map(name -> {
                    try { return Class.forName(name); }
                    catch (ClassNotFoundException e) { throw new IllegalStateException(name, e); }
                })
                .filter(c -> !c.isInterface())
                // Test doubles live in the same package and would otherwise be reviewed as if they
                // shipped. Same filter ActionFrameworkDisabledTest uses for its own stub.
                .filter(c -> !c.getName().contains("Test"))
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("the scan finds the providers, so the checks below are not vacuous")
    void scanIsNotVacuous() {
        // Without this, a scan that silently matched nothing would make every assertion here pass
        // forever while proving nothing at all.
        assertThat(providerClasses())
                .as("classpath scan of %s found no providers", SCAN_ROOT)
                .hasSizeGreaterThanOrEqualTo(15);
    }

    @Test
    @DisplayName("the registered providers are exactly the reviewed set")
    void noProviderAppearsWithoutReview() {
        Set<String> found = providerClasses().stream()
                .map(Class::getSimpleName)
                .collect(Collectors.toSet());

        // Adding a provider means editing this list, which forces the question "does this read only
        // the caller's own data?" to be answered by a person rather than assumed.
        assertThat(found).containsExactlyInAnyOrder(
                // self
                "Balances", "MyRequests", "MyClaims",
                "Today", "MyHistory", "MyShift",
                "MyRegularizations", "MyWfhAndPartialDay", "MyOvertime",
                "MyAssetRequests", "MyAssetAssignments",
                "MyProfile", "UpcomingHolidays", "MyNotifications",
                "MyDocumentCompliance", "MyPolicies", "MyTickets",
                // shared - content every role's own sidebar already shows, unguarded
                "DirectorySummary", "UpcomingBirthdays", "LatestAnnouncements", "NamedInQuestion",
                // peers - the employee's own project team, as My Team shows it
                "PeerTeam",
                // approvals
                "PendingApprovals", "PendingForManager", "PendingRegularizations",
                "PendingWfhAndPartialDay", "PendingOvertime", "PendingAssetRequests",
                "ApprovalSummaryProvider",
                // team - Manager, HR Admin and Super Admin, whoever has direct reports
                "TeamMembers", "TeamAttendance", "TeamLeave", "TeamPenalties",
                "TeamExpenseClaims", "TeamAssetRequests", "TeamAssetAssignments",
                // my-team - Efforts/Punctuality, Negligence, Time Assignments, Attendance Request
                // Reports, and the Overview gaps: same direct-report-only audience as "team" above
                "MyTeamEffort", "MyTeamNegligence", "MyTeamAssignments", "MyTeamRequestReports", "MyTeamOverviewExtras",
                "MyTeamAttendanceDiscrepancies",
                // organisation - HR/Admin only, audiences mirroring each screen's @PreAuthorize
                "UserAccounts", "Headcount", "OrgAttendanceToday", "OrgLeave", "OrgPenalties",
                "OrgStructure", "OrgDocumentCompliance", "HelpdeskQueue", "OnboardingSummary",
                "OrgAssetSummary", "ApiUsage");
    }

    @Test
    @DisplayName("the bytecode scan sees real service calls, so the call checks below are not vacuous")
    void bytecodeScanSeesRealCalls() throws Exception {
        // A direct call, a call made only inside a lambda, and a call made from a static helper in
        // the enclosing class - the three shapes a forbidden call could otherwise hide behind.
        assertThat(invokedOwnerQualifiedNames(LeaveDataProviders.Balances.class))
                .contains("leaveservice.listmybalances");
        assertThat(invokedOwnerQualifiedNames(OrganisationDataProviders.UserAccounts.class))
                .contains("usermanagementservice.listusers");
        assertThat(invokedOwnerQualifiedNames(TeamDataProviders.TeamPenalties.class))
                .contains("attendancepenaltyservice.listfordirectreports");
        assertThat(invokedOwnerQualifiedNames(MyTeamDataProviders.MyTeamRequestReports.class))
                .contains("reportsservice.getattendancerequestreportfordirectreports");
    }

    @Test
    @DisplayName("the My Team AI providers reach only the direct-report-only entry point, never the "
            + "org-widening one a same-named sibling method exists for")
    void myTeamProvidersNeverReachTheWideningEntryPoint() throws Exception {
        // TeamPenalties is deliberately not checked here: it shares an enclosing class with
        // OrganisationDataProviders' own activePenalties helper, which legitimately calls the
        // widening attendancepenaltyservice.list for its own ORGANISATION-scope caller - the
        // bytecode scan attributes that shared enclosing class's calls to every nested provider,
        // TeamPenalties included, so this specific check would be a false positive for it. Its own
        // positive assertion above (it calls listForDirectReports) is the real guarantee there.
        for (Class<?> type : List.of(
                MyTeamDataProviders.MyTeamEffort.class, MyTeamDataProviders.MyTeamNegligence.class,
                MyTeamDataProviders.MyTeamAssignments.class, MyTeamDataProviders.MyTeamRequestReports.class,
                MyTeamDataProviders.MyTeamOverviewExtras.class)) {
            assertThat(invokedOwnerQualifiedNames(type)).as(type.getSimpleName())
                    .doesNotContain("attendancepenaltyservice.list")
                    .doesNotContain("reportsservice.getattendancerequestreport");
        }
    }

    @Test
    @DisplayName("only an organisation-scoped provider ever calls an unscoped or org-wide read")
    void onlyOrganisationProvidersReachOrgWideReads() throws Exception {
        List<String> problems = new java.util.ArrayList<>();
        for (Class<?> type : providerClasses()) {
            AssistantDataProvider provider = instantiateWithoutDependencies(type);
            if (provider == null || provider.scope() == DataScope.ORGANISATION) continue;

            // Checked against the bytecode's real call sites, lambdas and method references
            // included - a provider's own declared method names say nothing about what it calls.
            for (String invoked : invokedMethodNames(type)) {
                if (FORBIDDEN_CALLS.contains(invoked)) problems.add(type.getSimpleName() + " -> " + invoked);
                if (provider.scope() != DataScope.TEAM && TEAM_CALLS.contains(invoked)) {
                    problems.add(type.getSimpleName() + " -> " + invoked + " (a team read)");
                }
                if (provider.scope() != DataScope.PEERS && PEER_CALLS.contains(invoked)) {
                    problems.add(type.getSimpleName() + " -> " + invoked + " (a peer-group read)");
                }
            }
        }

        assertThat(problems)
                .as("a provider reached a read wider than its declared scope; declare the scope it really "
                        + "has (TEAM for a Manager's reports, ORGANISATION with an HR/Admin audience for "
                        + "everyone), or use the caller-scoped method instead")
                .isEmpty();
    }

    @Test
    @DisplayName("no provider calls a method that writes, including reads that write as they go")
    void providersCallNoMutation() throws Exception {
        List<String> problems = new java.util.ArrayList<>();
        for (Class<?> type : providerClasses()) {
            for (String invoked : invokedMethodNames(type)) {
                if (MUTATING_PREFIXES.stream().anyMatch(invoked::startsWith)) {
                    problems.add(type.getSimpleName() + " -> " + invoked);
                }
            }
            // Owner-qualified, because these names are ordinary elsewhere: a DTO's getToday() is a
            // plain getter, AttendanceService's settles a stale session as it goes.
            for (String invoked : invokedOwnerQualifiedNames(type)) {
                if (WRITES_AS_IT_READS.contains(invoked)) problems.add(type.getSimpleName() + " -> " + invoked);
            }
        }

        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("no provider declares a method that writes")
    void providersDeclareNoMutation() {
        List<String> problems = providerClasses().stream()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods())
                        .map(m -> type.getSimpleName() + "." + m.getName()))
                .filter(name -> {
                    String method = name.substring(name.indexOf('.') + 1).toLowerCase(Locale.ROOT);
                    return MUTATING_PREFIXES.stream().anyMatch(method::startsWith);
                })
                .toList();

        // A provider holds a service that can mutate - LeaveService can approve leave - so the
        // discipline is that the provider itself exposes nothing but a read. This catches the
        // moment somebody adds a convenience method that breaks it.
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("every provider declares its permission gate and at least one module")
    void everyProviderDeclaresItsGates() throws Exception {
        Set<String> registered = SeedAuthorities.ofRoles("SUPER_ADMIN"); // V233: Super Admin holds every permission
        for (Class<?> type : providerClasses()) {
            AssistantDataProvider provider = instantiateWithoutDependencies(type);
            if (provider == null) continue;

            // An empty module set would run on every question; an authority that is not a registered
            // permission (a typo) could never be granted, silently turning the provider off for everyone.
            assertThat(provider.authorities()).as("%s authorities", type.getSimpleName()).isNotNull();
            assertThat(registered).as("%s authorities must be registered permissions", type.getSimpleName())
                    .containsAll(provider.authorities()).containsAll(provider.standsAsideFor());
            assertThat(provider.modules()).as("%s modules", type.getSimpleName()).isNotEmpty();
            assertThat(provider.id()).as("%s id", type.getSimpleName()).isNotBlank();
            assertThat(provider.title()).as("%s title", type.getSimpleName()).isNotBlank();
            assertThat(provider.scope()).as("%s scope", type.getSimpleName()).isNotNull();
        }
    }

    @Test
    @DisplayName("the wider a provider's scope, the narrower the permissions allowed to run it")
    void scopeNeverOutrunsAuthority() throws Exception {
        Set<String> employee = SeedAuthorities.ofRoles("EMPLOYEE");
        for (Class<?> type : providerClasses()) {
            AssistantDataProvider provider = instantiateWithoutDependencies(type);
            if (provider == null) continue;
            String name = type.getSimpleName();

            switch (provider.scope()) {
                // The caller's own records, content the UI already shows every role unguarded, or the
                // caller's own project team as their My Team page shows it.
                case SELF, SHARED, PEERS -> { }
                // Anything about other people must sit behind a real grant, never one a plain
                // Employee holds - so it never runs for someone who has nothing beyond EMPLOYEE.
                case APPROVALS, TEAM -> assertThat(provider.authorities())
                        .as("%s reads other people's records and must not run for an Employee", name)
                        .isNotEmpty().noneMatch(employee::contains);
                // Organisation-wide reads usually go through service methods with no actor at all,
                // protected in the UI only by the controller's @PreAuthorize - so the permission is
                // the whole gate, and it may only ever be an organisation-wide (ALL) grant.
                case ORGANISATION -> assertThat(provider.authorities())
                        .as("%s reads organisation-wide data and may only run behind an ALL-scope grant", name)
                        .isNotEmpty().allMatch(a -> a.endsWith(":ALL"));
            }
        }
    }

    /**
     * The role-bucket audiences providers had before gating moved to permissions, where they were
     * narrower than "APPROVALS/TEAM: Manager and above, everything else: everyone".
     */
    private static final Map<String, Set<AudienceBucket>> AUDIENCES_BEFORE = Map.ofEntries(
            Map.entry("PeerTeam", Set.of(AudienceBucket.EMPLOYEE)),
            Map.entry("UserAccounts", Set.of(AudienceBucket.ADMIN)),
            Map.entry("ApiUsage", Set.of(AudienceBucket.ADMIN)),
            Map.entry("Headcount", Set.of(AudienceBucket.HR)),
            Map.entry("OnboardingSummary", Set.of(AudienceBucket.HR)),
            Map.entry("OrgAttendanceToday", Set.of(AudienceBucket.HR, AudienceBucket.ADMIN)),
            Map.entry("OrgLeave", Set.of(AudienceBucket.HR, AudienceBucket.ADMIN)),
            Map.entry("OrgPenalties", Set.of(AudienceBucket.HR, AudienceBucket.ADMIN)),
            Map.entry("OrgStructure", Set.of(AudienceBucket.HR, AudienceBucket.ADMIN)),
            Map.entry("OrgDocumentCompliance", Set.of(AudienceBucket.HR, AudienceBucket.ADMIN)),
            Map.entry("HelpdeskQueue", Set.of(AudienceBucket.HR, AudienceBucket.ADMIN)),
            Map.entry("OrgAssetSummary", Set.of(AudienceBucket.HR, AudienceBucket.ADMIN)));

    private static final Set<AudienceBucket> MANAGER_AND_ABOVE =
            Set.of(AudienceBucket.MANAGER, AudienceBucket.HR, AudienceBucket.ADMIN);

    /** Real system-role combinations: everyone holds EMPLOYEE, an admin with reports also holds MANAGER. */
    private static final List<Set<AudienceBucket>> ROLE_COMBINATIONS = List.of(
            Set.of(AudienceBucket.EMPLOYEE),
            Set.of(AudienceBucket.EMPLOYEE, AudienceBucket.MANAGER),
            Set.of(AudienceBucket.EMPLOYEE, AudienceBucket.HR),
            Set.of(AudienceBucket.EMPLOYEE, AudienceBucket.ADMIN),
            Set.of(AudienceBucket.EMPLOYEE, AudienceBucket.MANAGER, AudienceBucket.HR),
            Set.of(AudienceBucket.EMPLOYEE, AudienceBucket.MANAGER, AudienceBucket.ADMIN));

    @Test
    @DisplayName("with the seeded roles, every provider runs for exactly the roles its old audience gate admitted")
    void permissionGatesMatchTheOldAudienceGatesForSystemRoles() throws Exception {
        List<String> problems = new java.util.ArrayList<>();
        for (Class<?> type : providerClasses()) {
            AssistantDataProvider provider = instantiateWithoutDependencies(type);
            if (provider == null) continue;
            Set<AudienceBucket> before = AUDIENCES_BEFORE.getOrDefault(type.getSimpleName(),
                    provider.scope() == DataScope.APPROVALS || provider.scope() == DataScope.TEAM
                            ? MANAGER_AND_ABOVE : Set.of(AudienceBucket.values()));
            for (Set<AudienceBucket> holder : ROLE_COMBINATIONS) {
                boolean old = before.stream().anyMatch(holder::contains);
                boolean now = AssistantDataService.mayRun(provider, seededContext(holder));
                if (old != now) problems.add(type.getSimpleName() + " for " + holder + ": was " + old + ", now " + now);
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("a custom role gets the data its permission grants, and none it does not")
    void customRoleGetsExactlyWhatItsPermissionsGrant() {
        EffectivePermissionService permissions = SeedMatrix.permissionService(
                Map.of("CUSTOM_PEOPLE", Map.of("leave:VIEW", "ALL")));
        Role custom = Role.builder().id(99).code("CUSTOM_PEOPLE").displayName("People Ops").build();
        Set<String> held = permissions.coverage(List.of(SeedMatrix.role("EMPLOYEE"), custom));
        AssistantRequestContext context = AssistantRequestContext.builder()
                .audiences(Set.of(AudienceBucket.EMPLOYEE)).authorities(held).customAuthorities(held).build();

        assertThat(AssistantDataService.mayRun(new OrganisationDataProviders.OrgLeave(null, null), context)).isTrue();
        // TEAM is covered by the ALL grant, so the team leave view opens too...
        assertThat(AssistantDataService.mayRun(new TeamDataProviders.TeamLeave(null, null), context)).isTrue();
        // ...but nothing it was not granted.
        assertThat(AssistantDataService.mayRun(new OrganisationDataProviders.OrgAttendanceToday(null, null, null, null), context)).isFalse();
        assertThat(AssistantDataService.mayRun(new OrganisationDataProviders.UserAccounts(null, null), context)).isFalse();
        assertThat(AssistantDataService.mayRun(new LeaveDataProviders.PendingApprovals(null), context)).isFalse();
        // A plain Employee without the custom role gets none of it.
        assertThat(AssistantDataService.mayRun(new OrganisationDataProviders.OrgLeave(null, null),
                seededContext(Set.of(AudienceBucket.EMPLOYEE)))).isFalse();
    }

    private static AssistantRequestContext seededContext(Set<AudienceBucket> buckets) {
        return AssistantRequestContext.builder().audiences(buckets).authorities(SeedAuthorities.of(buckets)).build();
    }

    /**
     * Every method name a provider class actually invokes, read from its bytecode - its own, plus
     * its enclosing class's, because providers are nested classes that share static helpers in the
     * outer class, and a call moved into one of those must not drop out of sight.
     *
     * <p>Covers ordinary calls, the synthetic methods lambdas compile into (they are methods of the
     * same class), and method references, which compile to an {@code invokedynamic} whose target
     * arrives as a {@link org.springframework.asm.Handle} bootstrap argument rather than a call.
     */
    private Set<String> invokedMethodNames(Class<?> type) throws java.io.IOException {
        return invocations(type).stream()
                .map(qualified -> qualified.substring(qualified.indexOf('.') + 1))
                .collect(Collectors.toSet());
    }

    /** As {@link #invokedMethodNames}, but "ownersimplename.method", lower-cased. */
    private Set<String> invokedOwnerQualifiedNames(Class<?> type) throws java.io.IOException {
        return invocations(type);
    }

    private Set<String> invocations(Class<?> type) throws java.io.IOException {
        Set<String> found = new java.util.HashSet<>(invocationsOf(type));
        if (type.getDeclaringClass() != null) found.addAll(invocationsOf(type.getDeclaringClass()));
        return found;
    }

    private Set<String> invocationsOf(Class<?> type) throws java.io.IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        try (java.io.InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("bytecode for %s", type.getName()).isNotNull();
            Set<String> found = new java.util.HashSet<>();
            new org.springframework.asm.ClassReader(in).accept(new org.springframework.asm.ClassVisitor(
                    org.springframework.asm.Opcodes.ASM9) {
                @Override
                public org.springframework.asm.MethodVisitor visitMethod(
                        int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new org.springframework.asm.MethodVisitor(org.springframework.asm.Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String method,
                                                    String desc, boolean isInterface) {
                            found.add(qualify(owner, method));
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String method, String desc,
                                                           org.springframework.asm.Handle bootstrap,
                                                           Object... bootstrapArgs) {
                            for (Object arg : bootstrapArgs) {
                                if (arg instanceof org.springframework.asm.Handle handle) {
                                    found.add(qualify(handle.getOwner(), handle.getName()));
                                }
                            }
                        }
                    };
                }
            }, 0);
            return found;
        }
    }

    /** "com/nforce/onehr/service/AttendanceService" + "getToday" -> "attendanceservice.gettoday". */
    private static String qualify(String internalOwner, String method) {
        String simple = internalOwner.substring(internalOwner.lastIndexOf('/') + 1);
        return (simple + "." + method).toLowerCase(Locale.ROOT);
    }

    /**
     * Builds a provider with null dependencies purely to read its declared metadata.
     *
     * <p>{@code id()}, {@code authorities()} and {@code modules()} are constants that never touch the
     * injected service, so a null is harmless here and avoids standing up a Spring context to
     * assert four constants.
     */
    private AssistantDataProvider instantiateWithoutDependencies(Class<?> type) throws Exception {
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object[] args = new Object[constructor.getParameterCount()];
        return (AssistantDataProvider) constructor.newInstance(args);
    }
}
