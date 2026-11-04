package com.nforce.onehr.dto.allocation;

import lombok.*;

import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ClientResponse {
    private UUID id;
    private String name;
}
