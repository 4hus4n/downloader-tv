package com.ahusan.tvdownloader;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class Format {
    private Format() { }

    static String size(long b) {
        if (b < 0) return "?";
        if (b < 1024) return b + " B";
        double v = b / 1024.0;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(Locale.US, v >= 100 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }

    static String date(long t) {
        return new SimpleDateFormat("d MMM, HH:mm", Locale.US).format(new Date(t));
    }
}
