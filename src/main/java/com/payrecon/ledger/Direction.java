package com.payrecon.ledger;

/** Which side of a journal entry a posting is on. Every entry needs both sides, and they must be equal. */
public enum Direction {
    DEBIT,
    CREDIT
}
