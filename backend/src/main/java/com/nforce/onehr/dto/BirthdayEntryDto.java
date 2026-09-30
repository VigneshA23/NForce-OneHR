package com.nforce.onehr.dto;

import lombok.Builder;
import lombok.Data;

/** Deliberately excludes birth year — see EmployeeService#listUpcomingBirthdays. */
@Data @Builder
public class BirthdayEntryDto {
    private String userId;
    private String fullName;
    private String departmentName;
    /** Nullable, same as departmentName — an employee with no designation on file simply omits it. */
    private String designationName;
    private int birthdayMonth;
    private int birthdayDay;
    private int daysUntil;
    private boolean today;
}
