package tr.com.allianz.ysv.services.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * Internal lifecycle of a single {@code ALZ_SBM_DECL_PROCESS} row.
 *
 * <pre>
 * NEW ──(send)──▶ PROCESSING ──(SBM 200 + result:true)──▶ SENT ──(query confirms)──▶ COMPLETED
 *                      │
 *                      └──(error / result:false)──▶ ERROR (retryable)
 * </pre>
 */
public enum ProcessStatus {

    NEW,
    PROCESSING,
    SENT,
    ERROR,
    COMPLETED;

    /** Statuses the "send" (HTTP POST) operation is allowed to pick up. */
    public static final Set<ProcessStatus> SENDABLE = EnumSet.of(NEW, ERROR);

    /** Statuses the "update" (HTTP PUT) and "cancel" operations are allowed to pick up. */
    public static final Set<ProcessStatus> UPDATABLE = EnumSet.of(SENT, COMPLETED);

    /** SBM'de kaydı olan, dolayısıyla sorgulanabilen durumlar. */
    public static final Set<ProcessStatus> QUERYABLE = EnumSet.of(SENT, COMPLETED);

    public boolean isSendable() {
        return SENDABLE.contains(this);
    }

    public boolean isUpdatable() {
        return UPDATABLE.contains(this);
    }
}
