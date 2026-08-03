package com.nforce.onehr.service;

import com.nforce.onehr.dto.OrgContextDto;
import com.nforce.onehr.dto.OrgContextDto.*;
import com.nforce.onehr.entity.Employee;
import com.nforce.onehr.entity.EmployeeManagerHistory;
import com.nforce.onehr.repository.EmployeeManagerHistoryRepository;
import com.nforce.onehr.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrgHierarchyService {

    private final EmployeeRepository employeeRepo;
    private final EmployeeManagerHistoryRepository historyRepo;

    /** Returns all employees (non-deleted) keyed by userId for fast lookup. */
    private Map<UUID, Employee> allEmployees() {
        return employeeRepo.findAllWithDetails().stream()
                .collect(Collectors.toMap(Employee::getUserId, e -> e));
    }

    /** All open manager assignments: employee_id → manager_id */
    private Map<UUID, UUID> employeeToManager() {
        return historyRepo.findByEffectiveToIsNull().stream()
                .collect(Collectors.toMap(
                        EmployeeManagerHistory::getEmployeeUserId,
                        EmployeeManagerHistory::getManagerUserId,
                        (a, b) -> a
                ));
    }

    /** Count of direct reports per manager — only counts employees with actual employee records */
    private Map<UUID, Long> directReportCounts(Map<UUID, UUID> empToMgr, Set<UUID> validEmployeeIds) {
        Map<UUID, Long> counts = new HashMap<>();
        empToMgr.entrySet().stream()
                .filter(e -> validEmployeeIds.contains(e.getKey()))
                .forEach(e -> counts.merge(e.getValue(), 1L, Long::sum));
        return counts;
    }

    private String initials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty()) sb.append(Character.toUpperCase(p.charAt(0)));
            if (sb.length() >= 2) break;
        }
        return sb.toString();
    }

    private PersonCard toCard(Employee emp, Map<UUID, Long> counts, boolean isFocus) {
        return PersonCard.builder()
                .id(emp.getUserId().toString())
                .name(emp.getFullName())
                .designation(emp.getDesignation() != null ? emp.getDesignation().getTitle() : null)
                .department(emp.getDepartment() != null ? emp.getDepartment().getName() : null)
                .initials(initials(emp.getFullName()))
                .directReportsCount(counts.getOrDefault(emp.getUserId(), 0L).intValue())
                .isFocus(isFocus)
                .active(emp.getUser().isActive())
                .build();
    }

    /** Build breadcrumb path from any root down to targetId. Returns empty if not reachable. */
    private List<BreadcrumbEntry> buildBreadcrumb(UUID targetId, Map<UUID, UUID> empToMgr,
                                                   Map<UUID, Employee> allEmps) {
        LinkedList<BreadcrumbEntry> crumb = new LinkedList<>();
        UUID cur = targetId;
        Set<UUID> visited = new HashSet<>();
        while (cur != null && !visited.contains(cur)) {
            visited.add(cur);
            Employee e = allEmps.get(cur);
            if (e != null) {
                crumb.addFirst(BreadcrumbEntry.builder()
                        .id(cur.toString())
                        .name(e.getFullName())
                        .build());
            }
            cur = empToMgr.get(cur);
        }
        return new ArrayList<>(crumb);
    }

    @Transactional(readOnly = true)
    public OrgContextDto getContext(UUID focusId) {
        Map<UUID, Employee> allEmps = allEmployees();
        Map<UUID, UUID> empToMgr = employeeToManager();
        Map<UUID, Long> counts = directReportCounts(empToMgr, allEmps.keySet());

        Employee focus = allEmps.get(focusId);
        if (focus == null) throw new NoSuchElementException("Employee not found: " + focusId);

        UUID managerId = empToMgr.get(focusId);

        // Manager card
        PersonCard managerCard = null;
        if (managerId != null) {
            Employee mgr = allEmps.get(managerId);
            if (mgr != null) managerCard = toCard(mgr, counts, false);
        }

        // Peers: everyone sharing the same manager (or all roots if focus is root)
        List<PersonCard> peers;
        if (managerId != null) {
            // Everyone whose manager_id == managerId
            final UUID mgrId = managerId;
            peers = empToMgr.entrySet().stream()
                    .filter(e -> mgrId.equals(e.getValue()))
                    .map(e -> allEmps.get(e.getKey()))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(e -> e.getFullName()))
                    .map(e -> toCard(e, counts, e.getUserId().equals(focusId)))
                    .collect(Collectors.toList());
        } else {
            // Focus is a root — peers are all other roots
            Set<UUID> hasManager = empToMgr.keySet();
            peers = allEmps.values().stream()
                    .filter(e -> !hasManager.contains(e.getUserId()))
                    .sorted(Comparator.comparing(Employee::getFullName))
                    .map(e -> toCard(e, counts, e.getUserId().equals(focusId)))
                    .collect(Collectors.toList());
        }

        // Direct reports of focus
        List<PersonCard> directReports = empToMgr.entrySet().stream()
                .filter(e -> focusId.equals(e.getValue()))
                .map(e -> allEmps.get(e.getKey()))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Employee::getFullName))
                .map(e -> toCard(e, counts, false))
                .collect(Collectors.toList());

        List<BreadcrumbEntry> breadcrumb = buildBreadcrumb(focusId, empToMgr, allEmps);

        return OrgContextDto.builder()
                .focusUser(toCard(focus, counts, true))
                .manager(managerCard)
                .peers(peers)
                .directReports(directReports)
                .breadcrumb(breadcrumb)
                .build();
    }

    @Transactional(readOnly = true)
    public List<PersonCard> getRoots() {
        Map<UUID, Employee> allEmps = allEmployees();
        Map<UUID, UUID> empToMgr = employeeToManager();
        Map<UUID, Long> counts = directReportCounts(empToMgr, allEmps.keySet());
        Set<UUID> hasManager = empToMgr.keySet();

        return allEmps.values().stream()
                .filter(e -> !hasManager.contains(e.getUserId()))
                .sorted(Comparator.comparing(Employee::getFullName))
                .map(e -> toCard(e, counts, false))
                .collect(Collectors.toList());
    }
}
