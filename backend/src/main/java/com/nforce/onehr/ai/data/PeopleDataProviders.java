package com.nforce.onehr.ai.data;

import com.nforce.onehr.ai.contract.AssistantRequestContext;
import com.nforce.onehr.ai.contract.AudienceBucket;
import com.nforce.onehr.dto.BirthdayEntryDto;
import com.nforce.onehr.dto.DirectoryEntryDto;
import com.nforce.onehr.dto.EmployeeResponse;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.repository.EmployeeRepository;
import com.nforce.onehr.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The caller's own employment details, and the people-directory facts every employee can see.
 */
public final class PeopleDataProviders {

    private PeopleDataProviders() {}

    /**
     * The caller's own work profile: code, designation, department, location, manager, dates.
     *
     * <p>Read from the employee record directly, against a field whitelist, rather than through
     * {@code ProfileService#getProfile}: that method returns personal fields the assistant has no
     * business sending anywhere (an unmasked passport number among them) and runs in a writable
     * transaction because it also settles a stale attendance session. Only work facts the caller
     * already sees on My Profile are read here - never contact, identity, bank or address fields.
     */
    @Component
    @RequiredArgsConstructor
    public static class MyProfile implements AssistantDataProvider {

        private final EmployeeRepository employeeRepository;
        private final EmployeeService employeeService;

        @Override public String id() { return "profile.me"; }
        @Override public DataScope scope() { return DataScope.SELF; }
        @Override public String title() { return "Your employment details"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("my-profile", "profile", "people"); }

        @Override
        @Transactional(readOnly = true)
        public Optional<String> fetch(AssistantRequestContext context) {
            Optional<Employee> found = employeeRepository.findByUser_Email(context.getActorEmail());
            if (found.isEmpty()) {
                return Optional.of("- No employee record exists for this account yet; HR completes it.");
            }
            Employee e = found.get();

            StringBuilder out = new StringBuilder();
            line(out, "Name", e.getFullName());
            line(out, "Employee code", e.getEmployeeCode());
            line(out, "Role", roleLabel(context.getPrimaryRoleCode()));
            line(out, "Designation", e.getDesignation() != null ? e.getDesignation().getTitle() : null);
            line(out, "Department", e.getDepartment() != null ? e.getDepartment().getName() : null);
            line(out, "Business unit", e.getBusinessUnit() != null ? e.getBusinessUnit().getName() : null);
            line(out, "Location", e.getLocation() != null ? e.getLocation().getName() : null);
            line(out, "Employment type", e.getEmploymentType());
            line(out, "Work mode", e.getWorkMode());
            line(out, "Joining date", e.getJoiningDate() != null ? e.getJoiningDate().toString() : null);
            line(out, "Probation end date", e.getProbationEndDate() != null ? e.getProbationEndDate().toString() : null);
            line(out, "Confirmation date", e.getConfirmationDate() != null ? e.getConfirmationDate().toString() : null);

            EmployeeResponse.ManagerRef manager = employeeService.getMyManager(context.getActorEmail());
            out.append("- Reporting manager: ")
                    .append(manager != null ? manager.getFullName() + " (" + manager.getEmail() + ")" : "none assigned")
                    .append('\n');
            return Optional.of(out.toString().stripTrailing());
        }

        private static void line(StringBuilder out, String label, String value) {
            out.append("- ").append(label).append(": ").append(value == null || value.isBlank() ? "not set" : value).append('\n');
        }
    }

    /**
     * Headcount facts from the People Directory: how many people are listed, active, and where.
     *
     * <p>{@link DataScope#SHARED}: the directory is served to every role with no role check at all
     * ({@code GET /api/employees/directory}), including each person's active flag, department and
     * location, so a count derived from it tells nobody anything their own sidebar does not. Counts
     * only - no names or contact details are sent.
     */
    @Component
    @RequiredArgsConstructor
    public static class DirectorySummary implements AssistantDataProvider {

        private static final int MAX_GROUPS = 15;

        private final EmployeeService employeeService;

        @Override public String id() { return "directory.summary"; }
        @Override public DataScope scope() { return DataScope.SHARED; }
        @Override public String title() { return "People Directory headcount by department and location"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("directory", "people"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<DirectoryEntryDto> people = employeeService.listDirectory();
            if (people == null || people.isEmpty()) return Optional.empty();

            List<DirectoryEntryDto> active = people.stream().filter(DirectoryEntryDto::isActive).toList();
            StringBuilder out = new StringBuilder("People Directory: %d people listed, %d active and %d inactive."
                    .formatted(people.size(), active.size(), people.size() - active.size()));
            out.append("\nActive people by department: ").append(groupCounts(active, DirectoryEntryDto::getDepartmentName));
            out.append("\nActive people by location: ").append(groupCounts(active, DirectoryEntryDto::getLocationName));
            out.append("\nActive people by work mode: ").append(groupCounts(active, DirectoryEntryDto::getWorkMode));
            return Optional.of(out.toString());
        }

        private static String groupCounts(List<DirectoryEntryDto> people, java.util.function.Function<DirectoryEntryDto, String> key) {
            Map<String, Long> counts = people.stream().collect(Collectors.groupingBy(
                    p -> key.apply(p) == null || key.apply(p).isBlank() ? "(not set)" : key.apply(p),
                    TreeMap::new, Collectors.counting()));
            return counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(MAX_GROUPS)
                    .map(e -> e.getKey() + " " + e.getValue())
                    .collect(Collectors.joining(", "))
                    + (counts.size() > MAX_GROUPS ? " (and %d smaller groups)".formatted(counts.size() - MAX_GROUPS) : "");
        }
    }

    /**
     * Every active People Directory entry whose name the question mentions, with the caller's own
     * entry marked - so "tell me about Praveen" asked by Praveen Gurram, "show details for Praveen
     * G." and "was the Praveen reporting to Ramesh present yesterday" are resolved against real
     * people rather than guessed at (ONEHR - the assistant assumed a colleague and refused, when
     * the name was the caller's own or matched several people).
     *
     * <p>{@link DataScope#SHARED}: exactly the fields the People Directory shows every role -
     * name, employee code, designation, department and reporting manager; never email or contact
     * details. Matching is by whole name word; see {@link #matches}.
     */
    @Component
    @RequiredArgsConstructor
    public static class NamedInQuestion implements AssistantDataProvider {

        private static final int MAX_MATCHES = 10;

        /** Words too common in questions to be read as a name, even where someone is called that. */
        private static final Set<String> NOT_NAMES = Set.of(
                "the", "and", "for", "you", "your", "yours", "mine", "myself", "what", "whats", "when", "where", "which",
                "who", "whom", "whose", "why", "how", "was", "were", "are", "has", "have", "had", "will", "can", "could",
                "would", "should", "may", "might", "must", "shall", "did", "does", "done", "not", "all", "any", "this",
                "that", "these", "those", "with", "from", "into", "about", "show", "tell", "give", "list", "get", "find",
                "please", "there", "their", "them", "they", "his", "her", "him", "she", "our", "out", "off", "last",
                "previous", "past", "next", "first", "only", "but", "also", "records", "record", "details", "detail",
                "leave", "leaves", "attendance", "present", "absent", "late", "today", "yesterday", "tomorrow", "week",
                "month", "year", "day", "days", "team", "manager", "reporting", "reports", "report", "employee",
                "employees", "name", "department", "designation", "request", "requests", "balance", "holiday",
                "holidays", "shift", "grace", "penalty", "penalties", "profile", "people", "person", "admin", "super");

        private final EmployeeService employeeService;

        @Override public String id() { return "people-named.matches"; }
        @Override public DataScope scope() { return DataScope.SHARED; }
        @Override public String title() { return "People Directory entries whose name the question mentions"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        @Override public Set<String> modules() { return Set.of("people-named"); }
        @Override public boolean consultedEveryTurn() { return true; }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            return Optional.empty();
        }

        // ponytail: reads the whole directory each turn; cache listDirectory if the org grows past a few thousand
        @Override
        public Optional<String> fetch(AssistantRequestContext context, String question) {
            if (question == null || question.isBlank()) return Optional.empty();
            List<DirectoryEntryDto> found = matches(question,
                    employeeService.listDirectory().stream().filter(DirectoryEntryDto::isActive).toList());
            if (found.isEmpty()) return Optional.empty();

            StringBuilder out = new StringBuilder(("The question mentions a name. Active People Directory entries it can refer "
                    + "to - exactly %d%s. Nobody else in the organisation has a matching name:")
                    .formatted(found.size(), found.size() > MAX_MATCHES ? ", the first %d listed".formatted(MAX_MATCHES) : ""));
            found.stream().limit(MAX_MATCHES).forEach(p -> out.append("\n- %s%s: employee code %s, %s, %s department, reporting manager %s".formatted(
                    p.getFullName(),
                    p.getEmail() != null && p.getEmail().equalsIgnoreCase(context.getActorEmail()) ? " (this is you, the signed-in user)" : "",
                    orNotSet(p.getEmployeeCode()), orNotSet(p.getDesignationName()), orNotSet(p.getDepartmentName()),
                    p.getManagerName() == null ? "none assigned" : p.getManagerName())));
            return Optional.of(out.toString());
        }

        /**
         * Entries named by the question. A question word (3+ letters, not in {@link #NOT_NAMES})
         * matches an entry when it equals one of the words of its name; a single letter right after
         * such a word must be the initial of another of its words ("Praveen G." rules out "Praveen
         * Kumar"). For each word, only the entries matching the most question words are kept, so
         * "Praveen Gurram" names one person while "Praveen" alone names every Praveen.
         */
        static List<DirectoryEntryDto> matches(String question, List<DirectoryEntryDto> directory) {
            List<String> words = java.util.Arrays.stream(question.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}]+"))
                    .filter(w -> !w.isEmpty()).toList();
            Map<DirectoryEntryDto, Integer> scores = new java.util.LinkedHashMap<>();
            Map<String, List<DirectoryEntryDto>> byWord = new java.util.HashMap<>();
            for (DirectoryEntryDto entry : directory) {
                if (entry.getFullName() == null) continue;
                List<String> name = java.util.Arrays.asList(entry.getFullName().toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}]+"));
                int score = 0;
                boolean ruledOut = false;
                for (int i = 0; i < words.size(); i++) {
                    String w = words.get(i);
                    if (w.length() < 3 || NOT_NAMES.contains(w) || !name.contains(w)) continue;
                    score++;
                    byWord.computeIfAbsent(w, k -> new java.util.ArrayList<>()).add(entry);
                    if (i + 1 < words.size() && words.get(i + 1).length() == 1) {
                        String initial = words.get(i + 1);
                        if (name.stream().anyMatch(n -> !n.equals(w) && n.startsWith(initial))) score++;
                        else ruledOut = true;
                    }
                }
                if (score > 0 && !ruledOut) scores.put(entry, score);
            }
            java.util.Set<DirectoryEntryDto> kept = new java.util.LinkedHashSet<>();
            byWord.values().forEach(candidates -> {
                List<DirectoryEntryDto> live = candidates.stream().filter(scores::containsKey).toList();
                int best = live.stream().mapToInt(scores::get).max().orElse(0);
                live.stream().filter(e -> scores.get(e) == best).forEach(kept::add);
            });
            return List.copyOf(kept);
        }

        private static String orNotSet(String value) {
            return value == null || value.isBlank() ? "not set" : value;
        }
    }

    /**
     * Birthdays today and in the coming week - the Home page's birthday widget, which every role
     * sees ({@code GET /api/employees/birthdays} has no role check). Day and month only; no year,
     * so no age, ever leaves the service.
     */
    @Component
    @RequiredArgsConstructor
    public static class UpcomingBirthdays implements AssistantDataProvider {

        private static final int WINDOW_DAYS = 7;

        private final EmployeeService employeeService;

        @Override public String id() { return "birthdays.upcoming"; }
        @Override public DataScope scope() { return DataScope.SHARED; }
        @Override public String title() { return "Colleagues' birthdays today and in the next 7 days"; }
        @Override public Set<AudienceBucket> audiences() { return Set.of(AudienceBucket.values()); }
        // Not "dashboard": the page hint would hand this a slot on every question asked from Home.
        @Override public Set<String> modules() { return Set.of("birthdays"); }

        @Override
        public Optional<String> fetch(AssistantRequestContext context) {
            List<BirthdayEntryDto> all = employeeService.listUpcomingBirthdays();
            if (all == null) return Optional.empty();
            List<BirthdayEntryDto> today = all.stream().filter(BirthdayEntryDto::isToday).toList();
            List<BirthdayEntryDto> soon = all.stream()
                    .filter(b -> !b.isToday() && b.getDaysUntil() <= WINDOW_DAYS)
                    .sorted((a, b) -> Integer.compare(b.getDaysUntil(), a.getDaysUntil()))
                    .toList();
            if (today.isEmpty() && soon.isEmpty()) return Optional.of("No birthdays today or in the next 7 days.");

            StringBuilder out = new StringBuilder("Birthdays today (%d): %s".formatted(today.size(),
                    today.isEmpty() ? "none" : today.stream().map(PeopleDataProviders::describe).collect(Collectors.joining(", "))));
            out.append("\nBirthdays in the next 7 days (%d): %s".formatted(soon.size(),
                    soon.isEmpty() ? "none" : soon.stream()
                            .map(b -> "%s on %02d-%02d (in %d days)".formatted(describe(b), b.getBirthdayDay(), b.getBirthdayMonth(), b.getDaysUntil()))
                            .collect(Collectors.joining("; "))));
            return Optional.of(out.toString());
        }
    }

    private static String describe(BirthdayEntryDto b) {
        return b.getDepartmentName() == null ? b.getFullName() : b.getFullName() + " (" + b.getDepartmentName() + ")";
    }

    static String roleLabel(String roleCode) {
        if (roleCode == null) return null;
        return switch (roleCode) {
            case "SUPER_ADMIN" -> "Super Admin";
            case "HR_ADMIN" -> "HR Admin";
            case "MANAGER" -> "Manager";
            case "EMPLOYEE" -> "Employee";
            default -> roleCode.charAt(0) + roleCode.substring(1).toLowerCase().replace('_', ' ');
        };
    }
}
