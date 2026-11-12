package com.payrecon.recon.mr;

/** Hadoop counters: cheap global tallies that every map/reduce task can increment. */
public enum ReconCounter {
    LEDGER_PAYMENTS,
    LEDGER_PAYOUTS,
    BANK_LINES,
    BANK_LINES_HDFC,
    BANK_LINES_ICICI,
    BANK_LINES_CARDNET,
    MALFORMED_LINES,
    HEADER_LINES
}
