package com.nforce.onehr.dto.attendance;

import lombok.*;

import java.time.LocalDate;

/** One point on the daily punctuality chart — only dates that were a working day for at least one direct report. */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DailyPunctuality {

    private LocalDate date;
    private int employeesOnTime;
}
