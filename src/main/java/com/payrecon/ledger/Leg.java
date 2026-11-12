package com.payrecon.ledger;

/** One line of a journal entry: "debit account X by amount" or "credit account Y by amount". */
public record Leg(String accountCode, Direction direction, long amountMinor) {

    public Leg {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("A posting amount must be positive, got " + amountMinor);
        }
    }

    public static Leg debit(String accountCode, long amountMinor) {
        return new Leg(accountCode, Direction.DEBIT, amountMinor);
    }

    public static Leg credit(String accountCode, long amountMinor) {
        return new Leg(accountCode, Direction.CREDIT, amountMinor);
    }
}
