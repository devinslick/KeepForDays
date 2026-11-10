package com.messages.keepfordays;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** v1.9.0 hand-rolled Material You kit. The gradle-free toolchain has no
 *  AndroidX/Material, so: DayNight via Theme.DeviceDefault.DayNight,
 *  surfaces/text from the Monet system palette (API 31+; static fallback
 *  below), cards = rounded GradientDrawables, chips = small pill
 *  TextViews. No lambdas (project convention). */
final class Ui {
    private Ui() { }

    static boolean dark(Context c) {
        int m = c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return m == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int sys(Context c, String name, int fallback) {
        if (Build.VERSION.SDK_INT >= 31) {
            int id = c.getResources().getIdentifier(name, "color", "android");
            if (id != 0) {
                try {
                    return c.getResources().getColor(id, c.getTheme());
                } catch (Throwable ignored) { }
            }
        }
        return fallback;
    }

    static int accent(Context c) {
        return dark(c) ? sys(c, "system_accent1_200", 0xFFEAABFF)
                      : sys(c, "system_accent1_600", 0xFF7B4FD0);
    }

    static int accentDim(Context c) {
        int a = accent(c);
        return Color.argb(36, Color.red(a), Color.green(a), Color.blue(a));
    }

    static int bg(Context c) {
        return dark(c) ? sys(c, "system_neutral1_900", 0xFF141414)
                      : sys(c, "system_neutral1_50", 0xFFFAF9FD);
    }

    static int surface(Context c) {
        return dark(c) ? sys(c, "system_neutral2_800", 0xFF1F1F24)
                      : sys(c, "system_neutral2_50", 0xFFF2EEF9);
    }

    static int textPri(Context c) {
        return dark(c) ? sys(c, "system_neutral1_100", 0xFFF0EAF6)
                      : sys(c, "system_neutral1_900", 0xFF1B1B1F);
    }

    static int textSec(Context c) {
        return dark(c) ? sys(c, "system_neutral2_300", 0xFFB9B3C6)
                      : sys(c, "system_neutral2_700", 0xFF4A4453);
    }

    static int ok(Context c) {
        return dark(c) ? sys(c, "system_accent3_200", 0xFF9BD6A4)
                      : sys(c, "system_accent3_700", 0xFF2C6B35);
    }

    static int danger(Context c) {
        return dark(c) ? sys(c, "system_error_300", 0xFFF2B8B5)
                      : sys(c, "system_error_600", 0xFFB3261E);
    }

    static int dp(Context c, int d) {
        return (int) (d * c.getResources().getDisplayMetrics().density);
    }

    // ---- containers ----

    static LinearLayout column(Context c) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        return col;
    }

    static LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    /** Material card: rounded surface container with padding. */
    static LinearLayout card(Context c) {
        LinearLayout card = column(c);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(c, 16));
        d.setColor(surface(c));
        card.setBackground(d);
        int p = dp(c, 16);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(c, 8), 0, dp(c, 8));
        card.setLayoutParams(lp);
        return card;
    }

    // ---- text ----

    static TextView headline(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(textPri(c));
        return t;
    }

    static TextView title(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(textPri(c));
        return t;
    }

    static TextView body(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(14);
        t.setTextColor(textPri(c));
        return t;
    }

    static TextView small(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(textSec(c));
        return t;
    }

    static TextView mono(Context c, String s) {
        TextView t = body(c, s);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(12);
        return t;
    }

    // ---- widgets ----

    static Button button(Context c, String label) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        return b;
    }

    static Button tonal(Context c, String label) {
        Button b = button(c, label);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(c, 20));
        d.setColor(accentDim(c));
        b.setBackground(d);
        b.setTextColor(accent(c));
        b.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        return b;
    }

    static Button filled(Context c, String label) {
        Button b = button(c, label);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(c, 20));
        d.setColor(accent(c));
        b.setBackground(d);
        // NOTE: color ints MUST carry 0xFF alpha — 0x001B1B1F is fully
        // transparent (this is why the first wizard build had "empty"
        // filled buttons).
        b.setTextColor(dark(c) ? 0xFF1B1B1F : 0xFFFFFFFF);
        b.setPadding(dp(c, 20), dp(c, 10), dp(c, 20), dp(c, 10));
        return b;
    }

    /** Status chip: pill with tinted background. */
    static TextView chip(Context c, String text, int tint, int fg) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(fg);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(c, 14));
        d.setColor(Color.argb(45, Color.red(tint), Color.green(tint), Color.blue(tint)));
        t.setBackground(d);
        t.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
        return t;
    }

    static void copyToClipboard(Activity a, String text, String toast) {
        try {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("kfd", text));
                Toast.makeText(a, toast, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(a, "copy failed: " + t, Toast.LENGTH_SHORT).show();
        }
    }

    /** Drawer navigation row; selected = accent tint + bold. */
    static LinearLayout navItem(Context c, String label, final Runnable onSelect) {
        LinearLayout item = row(c);
        int p = dp(c, 14);
        item.setPadding(dp(c, 20), p, dp(c, 20), p);
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(15);
        t.setTextColor(textPri(c));
        item.addView(t);
        item.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onSelect.run(); }
        });
        item.setTag("nav:" + label);
        return item;
    }

    static void markSelected(Context c, LinearLayout drawer, String label) {
        for (int i = 0; i < drawer.getChildCount(); i++) {
            View v = drawer.getChildAt(i);
            Object tag = v.getTag();
            if (!(tag instanceof String) || !((String) tag).startsWith("nav:")) continue;
            boolean sel = ((String) tag).equals("nav:" + label);
            v.setBackgroundColor(sel ? accentDim(c) : Color.TRANSPARENT);
            TextView t = (TextView) ((ViewGroup) v).getChildAt(0);
            t.setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
            t.setTextColor(sel ? accent(c) : textPri(c));
        }
    }

    static LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
