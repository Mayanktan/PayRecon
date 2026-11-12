package com.payrecon.bank;

/**
 * The banks that send us settlement files. Each one uses a different file format:
 * HDFC = CSV with rupee amounts, ICICI = pipe-separated with amounts in paise, CARDNET = JSON lines.
 */
public enum Bank {
    HDFC,
    ICICI,
    CARDNET
}
