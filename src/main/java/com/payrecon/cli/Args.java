package com.payrecon.cli;

import com.payrecon.common.ApiException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Minimal command-line parsing: positional words plus --flag value / --flag=value / --switch. */
public final class Args {

    private final List<String> positional = new ArrayList<>();
    private final Map<String, String> flags = new HashMap<>();

    public static Args parse(String[] argv) {
        Args a = new Args();
        for (int i = 0; i < argv.length; i++) {
            String s = argv[i];
            if (s.startsWith("--") && s.length() > 2) {
                String key = s.substring(2);
                int eq = key.indexOf('=');
                if (eq > 0) {
                    a.flags.put(key.substring(0, eq), key.substring(eq + 1));
                } else if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                    a.flags.put(key, argv[++i]);
                } else {
                    a.flags.put(key, "true");
                }
            } else {
                a.positional.add(s);
            }
        }
        return a;
    }

    public String pos(int index, String defaultValue) {
        return index < positional.size() ? positional.get(index) : defaultValue;
    }

    public boolean has(String flag) {
        return flags.containsKey(flag);
    }

    public String flag(String flag, String defaultValue) {
        return flags.getOrDefault(flag, defaultValue);
    }

    public int intFlag(String flag, int defaultValue) {
        String v = flags.get(flag);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.replace("_", "").replace(",", ""));
        } catch (NumberFormatException e) {
            throw new ApiException(400, "bad_flag", "--" + flag + " must be a whole number, got " + v);
        }
    }
}
