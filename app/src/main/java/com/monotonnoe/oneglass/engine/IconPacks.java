package com.monotonnoe.oneglass.engine;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Icon packs in the launchers' common format (ADW, Nova and the like): a package with an
 * appfilter.xml that maps apps' components to drawables of its own. The module's side of the
 * lock screen icons: which packs there are, what a pack draws for an app, and the icon an app
 * gets on the lock screen (the user's choice for it, else the pack's, else its own), drawn to
 * a PNG for System UI ({@link SettingsProvider}, {@link LockIcons}).
 */
final class IconPacks {
    /** The pack for the lock screen: "" none (One UI's icons), "app" the apps' own icons. */
    static final String KEY_PACK = "lock_icon_pack";
    /** A choice for one app: "pack:<drawable>", "app" or "oneui"; none means the default. */
    static final String KEY_FOR = "lock_icon_for_";
    /** The apps whose notifications System UI showed on the lock screen (Secure Folder's). */
    static final String KEY_SEEN = "lock_icons_seen";
    static final String APP = "app";
    static final String ONEUI = "oneui";
    static final String FROM_PACK = "pack:";
    private static final String[] PACK_ACTIONS = {
            "org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.gau.go.launcherex.theme",
            "com.teslacoilsw.launcher.THEME", "com.anddoes.launcher.THEME",
    };
    /** The size icons are drawn at for System UI. */
    private static final int SIZE = 192;

    private IconPacks() {
    }

    /** The installed icon packs: package, label. */
    static Map<String, String> installed(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, String> out = new java.util.TreeMap<>();
        for (String action : PACK_ACTIONS) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(action),
                    PackageManager.GET_META_DATA)) {
                String pkg = ri.activityInfo.packageName;
                out.put(pkg, String.valueOf(ri.loadLabel(pm)));
            }
        }
        return out;
    }

    /** A pack's appfilter: package to drawable (the launcher activity's first, else any). */
    private static final Map<String, Map<String, String>> FILTERS = new HashMap<>();
    private static final Map<String, Map<String, String>> COMPONENTS = new HashMap<>();
    private static final Map<String, List<String>> DRAWABLES = new HashMap<>();

    static synchronized void forget() {
        FILTERS.clear();
        COMPONENTS.clear();
        DRAWABLES.clear();
    }

    private static synchronized void load(Context c, String pack) {
        if (FILTERS.containsKey(pack)) return;
        Map<String, String> byPkg = new HashMap<>();
        Map<String, String> byComponent = new HashMap<>();
        Set<String> names = new LinkedHashSet<>();
        try {
            Context pc = c.createPackageContext(pack, Context.CONTEXT_IGNORE_SECURITY);
            Resources r = pc.getResources();
            XmlPullParser x = parser(pc, r, pack, "appfilter");
            if (x != null) {
                for (int e = x.getEventType(); e != XmlPullParser.END_DOCUMENT; e = x.next()) {
                    if (e != XmlPullParser.START_TAG || !"item".equals(x.getName())) continue;
                    String comp = x.getAttributeValue(null, "component");
                    String dr = x.getAttributeValue(null, "drawable");
                    if (comp == null || dr == null || dr.isEmpty()) continue;
                    names.add(dr);
                    // ComponentInfo{package/activity}
                    int a = comp.indexOf('{');
                    int b = comp.indexOf('}');
                    if (a < 0 || b <= a) continue;
                    String inner = comp.substring(a + 1, b);
                    byComponent.put(inner, dr);
                    int slash = inner.indexOf('/');
                    String pkg = slash > 0 ? inner.substring(0, slash) : inner;
                    if (!byPkg.containsKey(pkg)) byPkg.put(pkg, dr);
                }
            }
            XmlPullParser all = parser(pc, r, pack, "drawable");
            if (all != null) {
                for (int e = all.getEventType(); e != XmlPullParser.END_DOCUMENT; e = all.next()) {
                    if (e != XmlPullParser.START_TAG || !"item".equals(all.getName())) continue;
                    String dr = all.getAttributeValue(null, "drawable");
                    if (dr != null && !dr.isEmpty()) names.add(dr);
                }
            }
        } catch (Throwable t) {
            XLog.e("icon pack " + pack, t);
        }
        FILTERS.put(pack, byPkg);
        COMPONENTS.put(pack, byComponent);
        DRAWABLES.put(pack, new ArrayList<>(names));
    }

    /** res/xml/{name}.xml, else assets/{name}.xml. */
    private static XmlPullParser parser(Context pc, Resources r, String pack, String name)
            throws Exception {
        int id = r.getIdentifier(name, "xml", pack);
        if (id != 0) return r.getXml(id);
        try {
            InputStream in = pc.getAssets().open(name + ".xml");
            XmlPullParser x = XmlPullParserFactory.newInstance().newPullParser();
            x.setInput(in, "UTF-8");
            return x;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The drawables a pack has, for choosing one by hand. */
    static List<String> drawables(Context c, String pack) {
        load(c, pack);
        List<String> l = DRAWABLES.get(pack);
        return l != null ? l : new ArrayList<>();
    }

    /** What the pack draws for {@code pkg}, or null. */
    static String drawableFor(Context c, String pack, String pkg) {
        load(c, pack);
        Intent launch = c.getPackageManager().getLaunchIntentForPackage(pkg);
        ComponentName cn = launch != null ? launch.getComponent() : null;
        if (cn != null) {
            Map<String, String> comps = COMPONENTS.get(pack);
            String d = comps != null ? comps.get(pkg + "/" + cn.getClassName()) : null;
            if (d != null) return d;
        }
        Map<String, String> f = FILTERS.get(pack);
        return f != null ? f.get(pkg) : null;
    }

    /** A drawable of the pack by name, or null. */
    static Drawable packDrawable(Context c, String pack, String name) {
        try {
            Context pc = c.createPackageContext(pack, Context.CONTEXT_IGNORE_SECURITY);
            Resources r = pc.getResources();
            int id = r.getIdentifier(name, "drawable", pack);
            if (id == 0) id = r.getIdentifier(name, "mipmap", pack);
            return id != 0 ? r.getDrawableForDensity(id, 640, null) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The icon {@code pkg} gets on the lock screen, or null for One UI's: the user's choice
     * for it, else the pack's, else the app's own (with a pack, or with "the apps' own").
     */
    static Drawable lockIcon(Context c, SharedPreferences p, String pkg) {
        String pack = p.getString(KEY_PACK, "");
        String choice = p.getString(KEY_FOR + pkg, "");
        if (ONEUI.equals(choice)) return null;
        if (choice.startsWith(FROM_PACK) && !pack.isEmpty() && !APP.equals(pack)) {
            Drawable d = packDrawable(c, pack, choice.substring(FROM_PACK.length()));
            if (d != null) return d;
        }
        if (pack.isEmpty() && !APP.equals(choice)) return null;
        if (!APP.equals(choice) && !APP.equals(pack)) {
            String name = drawableFor(c, pack, pkg);
            Drawable d = name != null ? packDrawable(c, pack, name) : null;
            if (d != null) return d;
        }
        try {
            return c.getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@link #lockIcon} as a PNG in the cache, made once until the settings change. */
    static File lockIconFile(Context c, SharedPreferences p, String pkg) {
        File dir = new File(c.getCacheDir(), "lockicons");
        File f = new File(dir, pkg + ".png");
        File none = new File(dir, pkg + ".none");
        if (f.isFile()) return f;
        if (none.isFile()) return null;
        dir.mkdirs();
        Drawable d = lockIcon(c, p, pkg);
        try {
            if (d == null) {
                none.createNewFile();
                return null;
            }
            Bitmap b = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, SIZE, SIZE);
            d.draw(new Canvas(b));
            File tmp = new File(dir, pkg + ".tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                b.compress(Bitmap.CompressFormat.PNG, 100, o);
            }
            b.recycle();
            return tmp.renameTo(f) ? f : null;
        } catch (Throwable t) {
            XLog.e("lock icon " + pkg, t);
            return null;
        }
    }

    /** The made icons dropped: the settings changed. */
    static void clearCache(Context c) {
        File dir = new File(c.getCacheDir(), "lockicons");
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }
}
