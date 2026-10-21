package com.causa.core.services;

import com.causa.core.domain.Alert;
import com.causa.core.domain.Diagnostic;
import com.causa.core.domain.PageRequest;
import com.causa.core.domain.PageResult;

import java.util.List;
import java.util.Optional;

/**
 * Diagnostic Service — Primary Port
 *
 * @since 0.0.1
 */
public interface DiagnosticService {

    /**
     * Persists a PENDING diagnostic stub for the given alert and immediately returns it.
     * The full MCP + LLM analysis pipeline is dispatched on a background thread — the caller
     * is never blocked.
     *
     * @param alert the accepted alert to analyse
     * @return the PENDING diagnostic for the {@code default} profile, or the first profile when
     *         none is named {@code default}
     */
    Diagnostic triggerDiagnostics(Alert alert);

    /**
     * Persists one PENDING diagnostic per RCA profile and dispatches a single background pipeline.
     * Profiles share one MCP collection and one id suffix.
     *
     * @param alert the accepted alert to analyse
     * @return the PENDING diagnostics, one per profile, in profile order
     */
    List<Diagnostic> triggerDiagnosticsForProfiles(Alert alert);

    /**
     * Returns a paginated list of diagnostics ordered by creation time descending,
     * optionally filtered by container name and/or namespace.
     *
     * @param filter      optional container/namespace filter
     * @param pageRequest page and size
     * @return paginated result of diagnostics
     */
    PageResult<Diagnostic> listDiagnostics(Diagnostic.Filter filter, PageRequest pageRequest);

    /**
     * Returns a single diagnostic by its ID.
     */
    Optional<Diagnostic> getDiagnosticById(String diagnosticId);
}
