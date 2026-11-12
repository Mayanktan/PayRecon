package com.payrecon.ledger;

/**
 * Account types from accounting. Each type has a "normal side":
 * ASSET and EXPENSE accounts grow when debited; LIABILITY and REVENUE accounts grow when credited.
 * We store every balance in its natural sign, so a positive balance always means "normal".
 */
public enum AccountType {
    ASSET,
    LIABILITY,
    REVENUE,
    EXPENSE;

    public boolean debitNormal() {
        return this == ASSET || this == EXPENSE;
    }

    /** How a posting changes this account's balance (positive = balance grows). */
    public long signedDelta(Direction direction, long amount) {
        boolean grows = (direction == Direction.DEBIT) == debitNormal();
        return grows ? amount : -amount;
    }
}
