package com.nforce.onehr.service;

import com.nforce.onehr.dto.EmployeeResponse;
import com.nforce.onehr.dto.asset.AssetAssignmentResponse;
import com.nforce.onehr.dto.doc.RequiredDocumentDto;
import com.nforce.onehr.dto.onboarding.*;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.entity.OnboardingChecklist;
import com.nforce.onehr.entity.OnboardingChecklistItem;
import com.nforce.onehr.entity.User;
import com.nforce.onehr.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Checklist/orchestration layer over Employee, Documents and Assets.
 * Pre-boarding items and the two setup tasks with no existing system of
 * record ("Email & system access created", "Payroll & benefits enrolment")
 * are stored and manually checked off by HR. "Required documents verified",
 * "Laptop assigned" and "Access card assigned" are computed live from
 * DocumentService / AssetService on every read — never persisted — so the
 * checklist can never drift out of sync with the real document/asset state.
 */
@Service
@RequiredArgsConstructor
public class OnboardingService {

    private static final Set<String> ADMIN_ROLES = Set.of("HR_ADMIN", "SUPER_ADMIN");
    private static final int ATTENTION_WINDOW_DAYS = 2;

    private final OnboardingChecklistRepository checklistRepo;
    private final OnboardingChecklistItemRepository itemRepo;
    private final EmployeeRepository employeeRepo;
    private final EmployeeManagerHistoryRepository historyRepo;
    private final UserRepository userRepo;
    private final EmployeeService employeeService;
    private final DocumentService documentService;
    private final AssetService assetService;
    private final AuditService auditService;
    private final NotificationService notificationService;

    // ── Start a new flow ───────────────────────────────────

    @Transactional
    public OnboardingChecklistDetailDto startOnboarding(StartOnboardingRequest req, String actorEmail) {
        User actor = requireAdmin(actorEmail);

        UUID employeeUserId = req.getEmployeeUserId();
        if (!employeeRepo.existsById(employeeUserId)) {
            throw new NoSuchElementException("Employee not found: " + employeeUserId);
        }

        if (checklistRepo.existsByEmployeeUserId(employeeUserId)) {
            throw new IllegalStateException("Onboarding has already been started for this employee");
        }

        Employee emp = employeeRepo.findById(employeeUserId)
                .orElseThrow(() -> new NoSuchElementException("Employee not found: " + employeeUserId));
        LocalDate joining = emp.getJoiningDate();

        OnboardingChecklist checklist = OnboardingChecklist.builder()
                .employeeUserId(employeeUserId)
                .startedBy(actor.getId())
                .build();
        checklist = checklistRepo.save(checklist);

        List<OnboardingChecklistItem> items = List.of(
                item(checklist.getId(), "PRE_BOARDING", "OFFER_LETTER_COUNTERSIGNED", "Offer letter countersigned", joining.minusDays(8)),
                item(checklist.getId(), "PRE_BOARDING", "WELCOME_EMAIL_SENT", "Welcome email & day-1 logistics sent", joining.minusDays(2)),
                item(checklist.getId(), "PRE_BOARDING", "BUDDY_ASSIGNED", "Onboarding buddy assigned", joining.minusDays(2)),
                item(checklist.getId(), "PRE_BOARDING", "WORKSTATION_READY", "Workstation & seating prepared", joining.minusDays(1)),
                item(checklist.getId(), "SETUP", "EMAIL_ACCESS_CREATED", "Email & system access created", joining.minusDays(2)),
                item(checklist.getId(), "SETUP", "PAYROLL_ENROLLED", "Payroll & benefits enrolment", joining.plusDays(2))
        );
        itemRepo.saveAll(items);

        auditService.log(actor.getId(), "ONBOARDING_STARTED", employeeUserId);
        notificationService.send(employeeUserId, "ONBOARDING_STARTED",
                "Onboarding started",
                "Your onboarding checklist has been created. HR will guide you through the next steps.",
                "/documents");

        return getDetail(checklist.getId(), actorEmail);
    }

    // ── Queue ───────────────────────────────────────────────

    @Transactional
    public List<OnboardingChecklistSummaryDto> listQueue(String actorEmail) {
        requireAdmin(actorEmail);
        LocalDate today = LocalDate.now();
        List<OnboardingChecklist> all = checklistRepo.findAll();

        return all.stream()
                .map(c -> employeeRepo.findById(c.getEmployeeUserId()).map(emp -> {
                    Computed computed = compute(c, emp, today);
                    OnboardingChecklist finalized = finalizeIfComplete(c, computed);
                    return toSummary(finalized, emp, computed);
                }))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<EmployeeResponse> eligibleEmployees(String actorEmail) {
        requireAdmin(actorEmail);
        Set<UUID> onboarded = checklistRepo.findAll().stream()
                .map(OnboardingChecklist::getEmployeeUserId)
                .collect(Collectors.toSet());
        return employeeService.listEmployees().stream()
                .filter(e -> !onboarded.contains(e.getUserId()))
                .collect(Collectors.toList());
    }

    // ── Detail ──────────────────────────────────────────────

    @Transactional
    public OnboardingChecklistDetailDto getDetail(UUID checklistId, String actorEmail) {
        requireAdmin(actorEmail);
        OnboardingChecklist checklist = checklistRepo.findById(checklistId)
                .orElseThrow(() -> new NoSuchElementException("Onboarding checklist not found: " + checklistId));
        Employee emp = employeeRepo.findById(checklist.getEmployeeUserId())
                .orElseThrow(() -> new NoSuchElementException("Employee not found: " + checklist.getEmployeeUserId()));

        LocalDate today = LocalDate.now();
        Computed c = compute(checklist, emp, today);
        OnboardingChecklist finalized = finalizeIfComplete(checklist, c);
        boolean archived = "COMPLETED".equals(finalized.getStatus());

        List<TimelineEntryDto> timeline = new ArrayList<>();
        timeline.add(TimelineEntryDto.builder().at(finalized.getStartedAt())
                .text("Onboarding started").meta("checklist generated for pre-boarding, documents and setup").build());
        Stream.concat(c.preBoarding.stream(), c.setup.stream())
                .filter(i -> !i.isAuto() && i.getDoneAt() != null)
                .forEach(i -> timeline.add(TimelineEntryDto.builder().at(i.getDoneAt())
                        .text(i.getLabel()).meta("checked off by " + i.getDoneByName()).build()));
        if (archived && finalized.getCompletedAt() != null) {
            timeline.add(TimelineEntryDto.builder().at(finalized.getCompletedAt())
                    .text("Onboarding complete").meta("all tasks done · archived").build());
        }
        timeline.sort(Comparator.comparing(TimelineEntryDto::getAt));

        return OnboardingChecklistDetailDto.builder()
                .checklistId(finalized.getId())
                .employeeUserId(emp.getUserId())
                .employeeName(emp.getFullName())
                .employeeCode(emp.getEmployeeCode())
                .departmentName(emp.getDepartment() != null ? emp.getDepartment().getName() : null)
                .designationName(emp.getDesignation() != null ? emp.getDesignation().getTitle() : null)
                .locationName(emp.getLocation() != null ? emp.getLocation().getName() : null)
                .managerName(currentManagerName(emp.getUserId()))
                .joiningDate(emp.getJoiningDate())
                .archived(archived)
                .status(archived ? "COMPLETE" : c.statusLabel)
                .completedAt(finalized.getCompletedAt())
                .totalItems(c.totalItems)
                .doneItems(c.doneItems)
                .preBoarding(c.preBoarding)
                .setup(c.setup)
                .documentsItem(c.documentsItem)
                .documentsBreakdown(c.documentsBreakdown)
                .timeline(timeline)
                .build();
    }

    // ── Toggle a manual item ────────────────────────────────

    @Transactional
    public OnboardingChecklistDetailDto toggleItem(UUID checklistId, UUID itemId, String actorEmail) {
        User actor = requireAdmin(actorEmail);
        OnboardingChecklist checklist = checklistRepo.findById(checklistId)
                .orElseThrow(() -> new NoSuchElementException("Onboarding checklist not found: " + checklistId));
        OnboardingChecklistItem item = itemRepo.findById(itemId)
                .orElseThrow(() -> new NoSuchElementException("Checklist item not found: " + itemId));
        if (!item.getChecklistId().equals(checklistId)) {
            throw new IllegalArgumentException("Item does not belong to this checklist");
        }
        if ("COMPLETED".equals(checklist.getStatus())) {
            throw new IllegalStateException("This onboarding flow is already complete and archived");
        }

        item.setDone(!item.isDone());
        if (item.isDone()) {
            item.setDoneAt(Instant.now());
            item.setDoneBy(actor.getId());
        } else {
            item.setDoneAt(null);
            item.setDoneBy(null);
        }
        itemRepo.save(item);
        auditService.log(actor.getId(),
                item.isDone() ? "ONBOARDING_ITEM_CHECKED" : "ONBOARDING_ITEM_UNCHECKED",
                checklist.getEmployeeUserId());

        return getDetail(checklistId, actorEmail);
    }

    // ── Computation ─────────────────────────────────────────

    private static class Computed {
        List<OnboardingItemDto> preBoarding;
        List<OnboardingItemDto> setup;
        OnboardingItemDto documentsItem;
        List<DocumentsBreakdownDto> documentsBreakdown;
        int totalItems;
        int doneItems;
        String statusLabel;
    }

    private Computed compute(OnboardingChecklist checklist, Employee emp, LocalDate today) {
        List<OnboardingChecklistItem> stored = itemRepo.findByChecklistIdOrderByDueDateAsc(checklist.getId());

        List<OnboardingItemDto> preBoarding = stored.stream()
                .filter(i -> "PRE_BOARDING".equals(i.getCategory()))
                .map(this::toManualDto)
                .collect(Collectors.toList());

        List<OnboardingItemDto> setupManual = stored.stream()
                .filter(i -> "SETUP".equals(i.getCategory()))
                .map(this::toManualDto)
                .collect(Collectors.toList());

        List<AssetAssignmentResponse> assignments = assetService.currentAssignmentsForEmployee(emp.getUserId());
        LocalDate setupAutoDue = emp.getJoiningDate().minusDays(1);
        List<OnboardingItemDto> setup = new ArrayList<>();
        setup.add(autoAssetItem("LAPTOP_ASSIGNED", "Laptop assigned", "Laptop", assignments, setupAutoDue));
        setup.add(autoAssetItem("ACCESS_CARD_ASSIGNED", "Access card assigned", "Access Card", assignments, setupAutoDue));
        setup.addAll(setupManual);

        List<RequiredDocumentDto> required = documentService.requiredDocumentsFor(emp.getUserId());
        List<DocumentsBreakdownDto> breakdown = required.stream()
                .map(r -> DocumentsBreakdownDto.builder()
                        .documentTypeName(r.getDocumentTypeName())
                        .status(!r.isUploaded() ? "MISSING" : r.getStatus())
                        .build())
                .collect(Collectors.toList());
        long verifiedCount = required.stream().filter(r -> "VERIFIED".equals(r.getStatus())).count();
        boolean docsComplete = !required.isEmpty() && required.stream().allMatch(r -> "VERIFIED".equals(r.getStatus()));
        OnboardingItemDto documentsItem = OnboardingItemDto.builder()
                .itemKey("DOCUMENTS_VERIFIED")
                .label("Required documents verified")
                .category("DOCUMENTS")
                .auto(true)
                .source("Documents")
                .done(docsComplete)
                .meta(verifiedCount + " of " + required.size() + " required documents verified")
                .build();

        int total = preBoarding.size() + setup.size() + 1;
        int done = (int) (preBoarding.stream().filter(OnboardingItemDto::isDone).count()
                + setup.stream().filter(OnboardingItemDto::isDone).count()
                + (docsComplete ? 1 : 0));

        List<OnboardingItemDto> dueTracked = Stream.concat(preBoarding.stream(), setup.stream()).collect(Collectors.toList());
        boolean anyOverdue = dueTracked.stream()
                .anyMatch(i -> !i.isDone() && i.getDueDate() != null && i.getDueDate().isBefore(today));
        boolean anySoon = dueTracked.stream()
                .anyMatch(i -> !i.isDone() && i.getDueDate() != null && !i.getDueDate().isBefore(today)
                        && ChronoUnit.DAYS.between(today, i.getDueDate()) <= ATTENTION_WINDOW_DAYS);

        Computed c = new Computed();
        c.preBoarding = preBoarding;
        c.setup = setup;
        c.documentsItem = documentsItem;
        c.documentsBreakdown = breakdown;
        c.totalItems = total;
        c.doneItems = done;
        c.statusLabel = anyOverdue ? "OVERDUE" : anySoon ? "ATTENTION" : "ON_TRACK";
        return c;
    }

    /** If every item — manual and derived — is now done, flip the checklist to COMPLETED. Runs on every read. */
    private OnboardingChecklist finalizeIfComplete(OnboardingChecklist checklist, Computed c) {
        if (!"IN_PROGRESS".equals(checklist.getStatus())) return checklist;
        if (c.doneItems == c.totalItems) {
            checklist.setStatus("COMPLETED");
            checklist.setCompletedAt(Instant.now());
            checklist = checklistRepo.save(checklist);
            auditService.log(checklist.getStartedBy(), "ONBOARDING_COMPLETED", checklist.getEmployeeUserId());
        }
        return checklist;
    }

    private OnboardingChecklistSummaryDto toSummary(OnboardingChecklist checklist, Employee emp, Computed c) {
        boolean archived = "COMPLETED".equals(checklist.getStatus());

        String nextDueLabel = null;
        LocalDate nextDueDate = null;
        if (!archived) {
            List<OnboardingItemDto> incomplete = Stream.concat(c.preBoarding.stream(), c.setup.stream())
                    .filter(i -> !i.isDone())
                    .sorted(Comparator.comparing(OnboardingItemDto::getDueDate, Comparator.nullsLast(Comparator.naturalOrder())))
                    .collect(Collectors.toList());
            if (!incomplete.isEmpty()) {
                nextDueLabel = incomplete.get(0).getLabel();
                nextDueDate = incomplete.get(0).getDueDate();
            } else if (!c.documentsItem.isDone()) {
                nextDueLabel = c.documentsItem.getLabel();
            }
        }

        LocalDate completedDate = checklist.getCompletedAt() != null
                ? checklist.getCompletedAt().atZone(ZoneId.systemDefault()).toLocalDate() : null;
        Long durationDays = completedDate != null ? ChronoUnit.DAYS.between(emp.getJoiningDate(), completedDate) : null;

        return OnboardingChecklistSummaryDto.builder()
                .checklistId(checklist.getId())
                .employeeUserId(emp.getUserId())
                .employeeName(emp.getFullName())
                .employeeCode(emp.getEmployeeCode())
                .departmentName(emp.getDepartment() != null ? emp.getDepartment().getName() : null)
                .designationName(emp.getDesignation() != null ? emp.getDesignation().getTitle() : null)
                .joiningDate(emp.getJoiningDate())
                .archived(archived)
                .status(archived ? "COMPLETE" : c.statusLabel)
                .totalItems(c.totalItems)
                .doneItems(c.doneItems)
                .nextDueLabel(nextDueLabel)
                .nextDueDate(nextDueDate)
                .completedDate(completedDate)
                .durationDays(durationDays != null ? Math.abs(durationDays) : null)
                .build();
    }

    private OnboardingItemDto toManualDto(OnboardingChecklistItem item) {
        return OnboardingItemDto.builder()
                .id(item.getId())
                .itemKey(item.getItemKey())
                .label(item.getLabel())
                .category(item.getCategory())
                .auto(false)
                .dueDate(item.getDueDate())
                .done(item.isDone())
                .doneAt(item.getDoneAt())
                .doneByName(item.getDoneBy() != null ? employeeOrEmailName(item.getDoneBy()) : null)
                .build();
    }

    private OnboardingItemDto autoAssetItem(String key, String label, String categoryName,
                                             List<AssetAssignmentResponse> assignments, LocalDate dueDate) {
        Optional<AssetAssignmentResponse> match = assignments.stream()
                .filter(a -> categoryName.equalsIgnoreCase(a.getCategoryName()))
                .findFirst();
        boolean done = match.isPresent();
        return OnboardingItemDto.builder()
                .itemKey(key)
                .label(label)
                .category("SETUP")
                .auto(true)
                .source("Assets")
                .dueDate(dueDate)
                .done(done)
                .doneAt(done ? match.get().getEffectiveFrom() : null)
                .doneByName(done ? "System" : null)
                .meta(done ? match.get().getAssetTag() : null)
                .build();
    }

    private OnboardingChecklistItem item(UUID checklistId, String category, String itemKey, String label, LocalDate dueDate) {
        return OnboardingChecklistItem.builder()
                .checklistId(checklistId).category(category).itemKey(itemKey).label(label).dueDate(dueDate)
                .build();
    }

    private String currentManagerName(UUID employeeUserId) {
        return historyRepo.findByEmployeeUserIdAndEffectiveToIsNull(employeeUserId)
                .map(EmployeeManagerHistory::getManagerUserId)
                .map(this::employeeOrEmailName)
                .orElse(null);
    }

    private String employeeOrEmailName(UUID userId) {
        return employeeRepo.findById(userId).map(Employee::getFullName)
                .orElseGet(() -> userRepo.findById(userId).map(User::getEmail).orElse("Unknown"));
    }

    private User requireAdmin(String actorEmail) {
        User actor = userRepo.findByEmail(actorEmail)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + actorEmail));
        boolean isAdmin = actor.getRoles().stream().anyMatch(r -> ADMIN_ROLES.contains(r.getCode()));
        if (!isAdmin) {
            throw new AccessDeniedException("HR Admin or Super Admin role required");
        }
        return actor;
    }
}
