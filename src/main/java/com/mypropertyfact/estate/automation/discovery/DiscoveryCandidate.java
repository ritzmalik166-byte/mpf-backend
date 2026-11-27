package com.mypropertyfact.estate.automation.discovery;

/** Candidate evidence only; it is NOT an importable property row. */
public record DiscoveryCandidate(
        String sourceUrl, String projectName, String builderName, String city,
        String projectStatus, String reraNumber, String reraEvidenceUrl,
        String imagePermissionEvidence
) { }
