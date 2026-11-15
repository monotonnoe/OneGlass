package com.monotonnoe.oneglass.hook;

import android.view.View;
import android.view.ViewGroup;

import com.monotonnoe.oneglass.glass.Log;

/**
 * "Diagnostics" switch: writes the launcher's view tree to the LSPosed log once, so class names
 * of a new One UI build can be checked without a decompiler.
 */
final class Diagnostics {

    private static final int MAX_LINES = 600;
    private static int lines;

    private Diagnostics() {
    }

    static void dump(View root) {
        lines = 0;
        StringBuilder sb = new StringBuilder("view tree of ").append(root.getContext().getClass().getName()).append('\n');
        walk(root, 0, sb);
        // The LSPosed log truncates long entries: emit in chunks.
        String all = sb.toString();
        for (int i = 0; i < all.length(); i += 3000) {
            Log.i(all.substring(i, Math.min(all.length(), i + 3000)));
        }
    }

    private static void walk(View v, int depth, StringBuilder sb) {
        if (lines++ > MAX_LINES || depth > 18) {
            return;
        }
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        sb.append(v.getClass().getName())
                .append(" [").append(v.getLeft()).append(',').append(v.getTop())
                .append(' ').append(v.getWidth()).append('x').append(v.getHeight()).append(']');
        if (v.getVisibility() != View.VISIBLE) {
            sb.append(v.getVisibility() == View.GONE ? " GONE" : " INVISIBLE");
        }
        if (v.getBackground() != null) {
            sb.append(" bg=").append(v.getBackground().getClass().getSimpleName());
        }
        sb.append('\n');
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), depth + 1, sb);
            }
        }
    }
}
