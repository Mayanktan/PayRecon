package com.payrecon.recon.match;

/**
 * Every way the ledger and the bank can disagree. "Actionable" ones go to the analysts' queue;
 * the others are informational (they will usually clear by themselves).
 */
public enum ExceptionType {
    IN_TRANSIT(false, "Not settled yet, but still inside the normal settlement time"),
    PENDING_REVIEW(false, "May belong to an ambiguous bank line; waits for a person to decide"),
    PAYOUT_PENDING(false, "Payout batch dated on the cutoff day; the bank debit is not due yet"),
    MISSING_IN_BANK(true, "We captured this payment but the bank never settled it"),
    MISSING_IN_LEDGER(true, "The bank paid us money that matches no payment"),
    DUPLICATE_IN_BANK(true, "The bank sent the same settlement more than once"),
    AMOUNT_MISMATCH(true, "The bank settled a different amount than we captured"),
    AMBIGUOUS_MATCH(true, "Several payments fit this bank line equally well; a person must choose"),
    PAYOUT_BATCH_MISMATCH(true, "The bank debited a different amount than the payout batch total"),
    PAYOUT_NOT_EXECUTED(true, "We sent a payout batch but the bank never debited it"),
    UNKNOWN_DEBIT(true, "The bank debited money that matches no payout batch"),
    MALFORMED_BANK_LINE(true, "A bank file line could not be read");

    public final boolean actionable;
    public final String meaning;

    ExceptionType(boolean actionable, String meaning) {
        this.actionable = actionable;
        this.meaning = meaning;
    }
}
