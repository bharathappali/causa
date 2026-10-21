package com.causa.core.domain;

import com.causa.common.constants.DiagnosticConstants.DiagnosticStatus;
import com.causa.common.constants.DiagnosticConstants.FaultDomain;
import com.causa.common.utils.IdUtils;

import java.time.Instant;
import java.util.Objects;

/**
 * Diagnostic Domain Model
 *
 * <p>Represents the LLM-based diagnostic analysis result for an alert.
 * <p>This is an immutable aggregate root in the core domain layer.
 *
 * @since 0.0.1
 */
public final class Diagnostic {

    private final String diagnosticId;
    private final String alertId;
    /** RCA profile that produced this diagnostic. {@code default} when profiles are not configured. */
    private final String profileId;
    /** Shared 16-character suffix for every diagnostic created from the same alert. */
    private final String runGroupId;
    private final DiagnosticStatus status;
    private final Instant generatedAt;
    private final Float confidenceScore;
    private final FaultDomain faultDomain;
    /** Typed RCA — null until the pipeline reaches VALIDATING. Serialisation to/from DB is handled exclusively by {@link com.causa.infrastructure.persistence.mappers.DiagnosticEntityMapper}. */
    private final RootCauseAnalysis rca;
    private final String validationResult;
    private final String validationData;
    /** Every {@link com.causa.core.domain.validation.EvidenceItem} collected for this finding, as JSON. */
    private final String allEvidence;
    /** The user-facing subset of {@link #allEvidence}, selected once in the pipeline, as JSON. */
    private final String evidence;
    /** Free-text note. Used for the reason when a profile is {@code SKIPPED}. */
    private final String remarks;

    private Diagnostic(Builder builder) {
        this.diagnosticId    = Objects.requireNonNull(builder.diagnosticId, "diagnosticId cannot be null");
        this.alertId         = Objects.requireNonNull(builder.alertId, "alertId cannot be null");
        this.profileId       = builder.profileId;
        this.runGroupId      = builder.runGroupId;
        this.status          = Objects.requireNonNull(builder.status, "status cannot be null");
        this.generatedAt     = Objects.requireNonNull(builder.generatedAt, "generatedAt cannot be null");
        this.confidenceScore = builder.confidenceScore;
        this.faultDomain     = builder.faultDomain;
        this.rca             = builder.rca;
        this.validationResult = builder.validationResult;
        this.validationData  = builder.validationData;
        this.allEvidence     = builder.allEvidence;
        this.evidence        = builder.evidence;
        this.remarks         = builder.remarks;
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public String getDiagnosticId()         { return diagnosticId; }
    public String getAlertId()              { return alertId; }
    public String getProfileId()            { return profileId; }
    public String getRunGroupId()           { return runGroupId; }
    public DiagnosticStatus getStatus()     { return status; }
    public Instant getGeneratedAt()         { return generatedAt; }
    public Float getConfidenceScore()       { return confidenceScore; }
    public FaultDomain getFaultDomain()     { return faultDomain; }
    /** Typed RCA result — null until pipeline reaches VALIDATING/COMPLETED. */
    public RootCauseAnalysis getRca()       { return rca; }
    public String getValidationResult()     { return validationResult; }
    public String getValidationData()       { return validationData; }
    /** Complete internal evidence record as JSON — null when no evidence was collected. */
    public String getAllEvidence()          { return allEvidence; }
    /** Pre-selected user-facing evidence as JSON — null when no evidence was collected. */
    public String getEvidence()             { return evidence; }
    /** Free-text note, including the reason a profile was skipped. */
    public String getRemarks()              { return remarks; }

    /**
     * Generates a unique diagnostic ID: {@code diag_<16-char-alphanumeric>}.
     * Total length = 21 chars. The diagnostics primary key is {@code VARCHAR(32)} so profile
     * prefixes such as {@code cryo_diag} fit beside this default.
     *
     * @param alertId   unused — kept for call-site compatibility
     * @param timestamp unused — kept for call-site compatibility
     * @return a new unique diagnostic ID
     */
    public static String generateDiagnosticId(String alertId, Instant timestamp) {
        return IdUtils.generateDiagnosticId();
    }

    public static Builder builder() { return new Builder(); }


    /**
     * Optional filter criteria for diagnostic list queries.
     *
     * <p>All fields are optional — {@code null} or blank means "skip this filter".
     * All present (non-blank) fields are AND-ed inside the repository via a JOIN
     * to the {@code alerts} table.
     */
    public record Filter(
            /** Exact match on {@code workload_name} column (container name for k8s, workload name for VM). Null/blank → skip. */
            String workload,
            /** Exact match on {@code workload_info->>'namespace'} (JSONB via alerts JOIN). Null/blank → skip. */
            String namespace,
            /** Exact match on {@code profile_id}. Null/blank → skip. */
            String profile
    ) {
        /** Workload and namespace only. Profile is unconstrained. */
        public Filter(String workload, String namespace) {
            this(workload, namespace, null);
        }

        /** Returns a filter with no constraints — matches every diagnostic. */
        public static Filter empty() { return new Filter(null, null, null); }

        /** Returns {@code true} when every field is null or blank. */
        public boolean isEmpty() { return isBlank(workload) && isBlank(namespace) && isBlank(profile); }

        private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static final class Builder {
        private String diagnosticId;
        private String alertId;
        private String profileId;
        private String runGroupId;
        private DiagnosticStatus status;
        private Instant generatedAt;
        private Float confidenceScore;
        private FaultDomain faultDomain;
        private RootCauseAnalysis rca;
        private String validationResult;
        private String validationData;
        private String allEvidence;
        private String evidence;
        private String remarks;

        private Builder() {}

        public Builder diagnosticId(String v)        { this.diagnosticId = v;    return this; }
        public Builder alertId(String v)              { this.alertId = v;         return this; }
        public Builder profileId(String v)            { this.profileId = v;       return this; }
        public Builder runGroupId(String v)           { this.runGroupId = v;      return this; }
        public Builder status(DiagnosticStatus v)     { this.status = v;          return this; }
        public Builder generatedAt(Instant v)         { this.generatedAt = v;     return this; }
        public Builder confidenceScore(Float v)       { this.confidenceScore = v; return this; }
        public Builder faultDomain(FaultDomain v)     { this.faultDomain = v;     return this; }
        public Builder rca(RootCauseAnalysis v)       { this.rca = v;             return this; }
        public Builder validationResult(String v)     { this.validationResult = v; return this; }
        public Builder validationData(String v)       { this.validationData = v;  return this; }
        public Builder allEvidence(String v)          { this.allEvidence = v;     return this; }
        public Builder evidence(String v)             { this.evidence = v;        return this; }
        public Builder remarks(String v)              { this.remarks = v;         return this; }

        public Diagnostic build() { return new Diagnostic(this); }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Diagnostic that = (Diagnostic) o;
        return Objects.equals(diagnosticId, that.diagnosticId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(diagnosticId);
    }

    @Override
    public String toString() {
        return "Diagnostic{" +
            "diagnosticId='" + diagnosticId + '\'' +
            ", alertId='" + alertId + '\'' +
            ", profileId='" + profileId + '\'' +
            ", runGroupId='" + runGroupId + '\'' +
            ", status=" + status +
            ", generatedAt=" + generatedAt +
            ", confidenceScore=" + confidenceScore +
            ", faultDomain=" + faultDomain +
            '}';
    }
}
