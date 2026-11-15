package com.monotonnoe.oneglass.glass;

import android.view.View;
import android.view.ViewParent;

import java.util.HashMap;
import java.util.Map;

/**
 * Decides whether a view belongs to the home screen (pages, dock, open folders). The One UI apps
 * screen and the Edge panels stay stock. Same scope BlurIcons uses for its icon glass.
 */
public final class HomeScope {

    private static final int UNKNOWN = 0;
    private static final int HOME = 1;
    private static final int OTHER = 2;
    private static final Map<Class<?>, Integer> KIND = new HashMap<>();

    private HomeScope() {
    }

    private static int kind(Class<?> c) {
        Integer k = KIND.get(c);
        if (k != null) {
            return k;
        }
        String n = c.getName();
        int r = UNKNOWN;
        if (n.endsWith(".WorkspaceCellLayout") || n.endsWith(".HotseatCellLayout")) {
            r = HOME;
        } else if (n.contains(".applist.") || n.contains(".appscreen.") || n.contains(".verticalapplist.")
                || n.contains("Applist") || n.contains("AppsList") || n.contains(".edge.")) {
            r = OTHER;
        } else if ((n.contains(".folder.") || n.contains("Folder")) && !n.contains("FolderIcon")) {
            r = HOME;
        }
        KIND.put(c, r);
        return r;
    }

    public static boolean isHome(View v) {
        ViewParent p = v.getParent();
        int guard = 0;
        while (p instanceof View && guard++ < 16) {
            int k = kind(p.getClass());
            if (k == HOME) {
                return true;
            }
            if (k == OTHER) {
                return false;
            }
            p = p.getParent();
        }
        return false;
    }

    public static boolean inAppsScreen(View v) {
        ViewParent p = v.getParent();
        int guard = 0;
        while (p instanceof View && guard++ < 16) {
            if (kind(p.getClass()) == OTHER) {
                return true;
            }
            p = p.getParent();
        }
        return false;
    }
}
