package com.nforce.onehr.dto.talent;

import lombok.Value;

@Value
public class CertificateExpiryBreakdown {
    long in30Days;
    long in60Days;
    long in90Days;
}
