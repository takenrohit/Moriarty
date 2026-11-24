package dev.investigator.evidence;

/**
 * A compact summary that is stored as one Evidence record.
 *
 * <p>Implementations are records whose JSON form is the Tool_Result shown to the model. The
 * {@link #headline()} is not part of that JSON; {@link EvidenceService} stores it on the Evidence for
 * renderers.
 */
public interface ToolSummary {

    /** The Evidence_ID this summary was built for. */
    String evidenceId();

    /** One-line human description, at most {@link Headlines#MAX_LENGTH} code points. */
    String headline();
}
