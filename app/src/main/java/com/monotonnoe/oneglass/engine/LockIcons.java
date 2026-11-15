package com.monotonnoe.oneglass.engine;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.ImageView;

import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The lock screen cards' icons from the icon pack the user chose (or the apps' own icons, or
 * one picked for an app), as iOS shows apps' coloured icons there: asked of the module's
 * provider once per app (it draws them, {@link IconPacks}), kept, and set on One UI's icon
 * view while the phone is locked; One UI's icon is put back when unlocked. The apps whose
 * cards show are told to the module, so the settings can offer Secure Folder's too. System UI.
 */
final class LockIcons {
    /** Sent to System UI by the settings screen when the icons change. */
    static final String ACTION = "com.monotonnoe.oneglass.engine.LOCK_ICONS";
    private static final Object NONE = new Object();
    private static final Object LOADING = new Object();
    private static final Map<String, Object> ICONS = new HashMap<>();
    private static final Set<String> TOLD = new HashSet<>();
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean started;

    private LockIcons() {
    }

    static void start(Context c) {
        if (started) return;
        started = true;
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                ICONS.clear();
                LockNotifications.refreshAll();
            }
        }, new IntentFilter(ACTION), Context.RECEIVER_EXPORTED);
    }

    /** The icon for {@code pkg}, or null for One UI's (or while it is being asked for). */
    static Drawable get(Context c, String pkg) {
        Object o = ICONS.get(pkg);
        if (o instanceof Drawable) return (Drawable) o;
        if (o != null) return null;
        ICONS.put(pkg, LOADING);
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        boolean tell = TOLD.add(pkg);
        WORK.execute(() -> {
            Uri base = Uri.parse("content://" + Settings.AUTHORITY);
            if (tell) {
                try {
                    ContentValues v = new ContentValues();
                    v.put(IconPacks.KEY_SEEN, pkg);
                    app.getContentResolver().update(base, v, null, null);
                } catch (Throwable ignored) {
                }
            }
            Object icon = NONE;
            Uri uri = Uri.withAppendedPath(base, "lockicon/" + pkg);
            try (ParcelFileDescriptor pfd = app.getContentResolver().openFileDescriptor(uri, "r")) {
                Bitmap b = pfd != null ? decode(app, pfd) : null;
                if (b != null) icon = new BitmapDrawable(app.getResources(), b);
            } catch (FileNotFoundException none) {
                // One UI's icon for it (or the module could not be asked: the log says).
                if (none.getMessage() != null && !none.getMessage().contains("One UI")) {
                    XLog.i("lock icons: " + pkg + ": " + none.getMessage());
                }
            } catch (Throwable t) {
                XLog.e("lock icon " + pkg, t);
            }
            Object done = icon;
            XLog.i("lock icons: " + pkg + (done == NONE ? ": One UI's" : ": from the module"));
            MAIN.post(() -> {
                ICONS.put(pkg, done);
                if (done != NONE) LockNotifications.refreshAll();
            });
        });
        return null;
    }

    /** The provider's PNGs are this many pixels a side ({@link IconPacks}). */
    private static final int PNG_PX = 192;
    /**
     * The most a card's icon is drawn at: its view is about 35 dp. The icon is kept at this
     * size instead of the PNG's 192 px (a third of the memory); the view scaled it down to
     * its size anyway.
     */
    private static final float KEEP_DP = 44f;
    /** The density a bitmap decoded without options is given (that is, the device's). */
    private static final int DEFAULT_DENSITY =
            Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8).getDensity();

    /** The PNG at {@link #KEEP_DP}, with the same intrinsic size as the full PNG had. */
    private static Bitmap decode(Context app, ParcelFileDescriptor pfd) {
        int keep = Math.round(KEEP_DP * app.getResources().getDisplayMetrics().density);
        if (keep >= PNG_PX || DEFAULT_DENSITY <= 0) {
            return BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = true;
        o.inDensity = PNG_PX;
        o.inTargetDensity = keep;
        Bitmap b = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor(), null, o);
        // Its density says it stands for PNG_PX pixels: the drawable measures as before.
        if (b != null) b.setDensity(Math.round(DEFAULT_DENSITY * keep / (float) PNG_PX));
        return b;
    }

    /** One UI's icon view as it was, to put back. */
    private static final Map<ImageView, Object[]> ORIGINAL = new WeakHashMap<>();

    /** {@code icon} on One UI's view, or One UI's own back when {@code icon} is null. */
    static void set(View view, Drawable icon) {
        if (!(view instanceof ImageView)) return;
        ImageView v = (ImageView) view;
        if (icon != null) {
            if (v.getDrawable() == icon) {
                // One UI tints its icons white on a touch, a swipe, an expansion: not ours.
                if (v.getImageTintList() != null) v.setImageTintList(null);
                if (v.getColorFilter() != null) v.clearColorFilter();
                if (v.getBackground() != null) v.setBackground(null);
                return;
            }
            Object[] was = ORIGINAL.get(v);
            if (was == null) {
                ORIGINAL.put(v, new Object[]{v.getDrawable(), new int[]{v.getPaddingLeft(),
                        v.getPaddingTop(), v.getPaddingRight(), v.getPaddingBottom()},
                        v.getImageTintList(), v.getColorFilter(), v.getBackground()});
            } else {
                // One UI bound the view again (another notification): its icon now.
                was[0] = v.getDrawable();
            }
            v.setImageTintList(null);
            v.clearColorFilter();
            v.setBackground(null);
            v.setPadding(0, 0, 0, 0);
            v.setImageDrawable(icon);
        } else {
            Object[] o = ORIGINAL.remove(v);
            if (o != null) restore(v, o);
        }
    }

    private static void restore(ImageView v, Object[] o) {
        int[] pad = (int[]) o[1];
        v.setImageDrawable((Drawable) o[0]);
        v.setPadding(pad[0], pad[1], pad[2], pad[3]);
        v.setImageTintList((android.content.res.ColorStateList) o[2]);
        v.setColorFilter((android.graphics.ColorFilter) o[3]);
        v.setBackground((Drawable) o[4]);
    }

    /** Every icon view put back (the phone was unlocked). */
    static void restoreAll() {
        for (Map.Entry<ImageView, Object[]> e : new java.util.ArrayList<>(ORIGINAL.entrySet())) {
            if (e.getKey() != null) restore(e.getKey(), e.getValue());
        }
        ORIGINAL.clear();
    }

    private static boolean packageFailed;

    /**
     * The app a notification row is from: its entry's StatusBarNotification (getEntry, or
     * Android 16's getEntryLegacy, or the field), else the row's own getter, if any.
     */
    static String packageOf(View row) {
        // The field first: One UI 8.5 has it, and has no getEntry() (6.67 asked for the
        // getters first, failing lookups on every card drawn).
        Object entry = field(row, "mEntry");
        if (entry == null) entry = call(row, "getEntry");
        if (entry == null) entry = call(row, "getEntryLegacy");
        if (entry != null) {
            String known = PACKAGES.get(entry);
            if (known != null) return known;
        }
        Object sbn = entry != null ? field(entry, "mSbn") : null;
        if (sbn == null && entry != null) sbn = call(entry, "getSbn");
        Object pkg = sbn != null ? call(sbn, "getPackageName") : null;
        if (pkg == null) pkg = call(row, "getPackageName");
        if (pkg instanceof String) {
            if (entry != null) PACKAGES.put(entry, (String) pkg);
            return (String) pkg;
        }
        if (!packageFailed) {
            packageFailed = true;
            StringBuilder m = new StringBuilder();
            for (Class<?> k = row.getClass(); k != null && k != View.class;
                    k = k.getSuperclass()) {
                for (java.lang.reflect.Method x : k.getDeclaredMethods()) {
                    String n = x.getName().toLowerCase(java.util.Locale.ROOT);
                    if (x.getParameterCount() == 0 && (n.contains("entry") || n.contains("sbn")
                            || n.contains("package") || n.contains("key"))) {
                        m.append(' ').append(x.getName());
                    }
                }
            }
            XLog.i("lock icons: no package for " + row.getClass().getName() + " (entry "
                    + (entry == null ? "none" : entry.getClass().getName()) + "); methods:" + m);
        }
        return null;
    }

    /** The app of each notification entry, once found (the entry is the row's, kept weakly). */
    private static final Map<Object, String> PACKAGES = new WeakHashMap<>();
    /** Methods and fields by class and name, found or not (each lookup made once). */
    private static final Map<Class<?>, Map<String, Object>> METHODS = new HashMap<>();
    private static final Map<Class<?>, Map<String, Object>> FIELDS = new HashMap<>();

    private static Object member(Class<?> c, String name, boolean method) {
        Map<Class<?>, Map<String, Object>> all = method ? METHODS : FIELDS;
        Map<String, Object> byName = all.get(c);
        if (byName == null) {
            byName = new HashMap<>();
            all.put(c, byName);
        }
        Object m = byName.get(name);
        if (m != null) return m;
        m = NONE;
        for (Class<?> k = c; k != null && m == NONE; k = k.getSuperclass()) {
            try {
                java.lang.reflect.AccessibleObject a = method ? k.getDeclaredMethod(name)
                        : k.getDeclaredField(name);
                a.setAccessible(true);
                m = a;
            } catch (NoSuchMethodException | NoSuchFieldException ignored) {
            } catch (Throwable t) {
                break;
            }
        }
        byName.put(name, m);
        return m;
    }

    private static Object call(Object o, String name) {
        Object m = member(o.getClass(), name, true);
        if (m == NONE) return null;
        try {
            return ((java.lang.reflect.Method) m).invoke(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object field(Object o, String name) {
        Object f = member(o.getClass(), name, false);
        if (f == NONE) return null;
        try {
            return ((java.lang.reflect.Field) f).get(o);
        } catch (Throwable t) {
            return null;
        }
    }
}
