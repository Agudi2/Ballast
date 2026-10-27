package dev.ballast.api;

/** Result of an acknowledgement attempt, without requiring indefinite acknowledgement tombstones. */
public enum AckResult {
    /** This call made the one terminal acknowledgement transition and durably synced it. */
    APPLIED,
    /** No transition occurred: the job/token was unknown, already acknowledged, stale, or mismatched. */
    NOT_APPLIED
}
