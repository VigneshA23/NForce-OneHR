package com.nforce.onehr.dto.payroll;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** Full replace, not a partial patch — this is one small settings form saved as a whole, same
 * convention as PayGroupsTab's own edit drawer on the frontend. */
@Data
public class UpdatePayGroupFilingDetailsRequest {

    @Size(max = 10)
    private String pan;

    @Size(max = 10)
    private String tan;

    @Size(max = 50)
    private String tanCircleNumber;

    @Size(max = 150)
    private String citTdsLocation;

    @Size(max = 150)
    private String form130Signatory;

    @Size(max = 150)
    private String signatoryDesignation;

    @Size(max = 150)
    private String signatoryFatherName;

    @Size(max = 50)
    private String pfEstablishmentId;

    @Size(max = 50)
    private String esiEstablishmentId;
}
