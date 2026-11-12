package com.payrecon.ledger;

/**
 * The chart of accounts: every account PayRecon uses.
 *
 * <pre>
 * CLEARING:&lt;bank&gt;       ASSET      money a bank owes us for captured payments it has not settled yet
 * SETTLEMENT_CASH        ASSET      cash in our own bank account
 * MERCHANT_PAYABLE:&lt;id&gt;  LIABILITY  money we owe each merchant
 * REFUNDS_PAYABLE        LIABILITY  money we owe customers for refunds
 * SUSPENSE               LIABILITY  money we received but cannot identify yet
 * FEE_REVENUE            REVENUE    our fees
 * </pre>
 */
public final class Accounts {

    public static final String SETTLEMENT_CASH = "SETTLEMENT_CASH";
    public static final String FEE_REVENUE = "FEE_REVENUE";
    public static final String REFUNDS_PAYABLE = "REFUNDS_PAYABLE";
    public static final String SUSPENSE = "SUSPENSE";

    private Accounts() {
    }

    public static String clearing(String bank) {
        return "CLEARING:" + bank;
    }

    public static String merchantPayable(String merchantId) {
        return "MERCHANT_PAYABLE:" + merchantId;
    }
}
