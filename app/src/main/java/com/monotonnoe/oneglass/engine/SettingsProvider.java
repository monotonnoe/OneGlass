package com.monotonnoe.oneglass.engine;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/** Read-only view of the module's settings for the launcher process. */
public final class SettingsProvider extends ContentProvider {
    static final String PREFS = "settings";
    /** The picture for live wallpapers, in the module's files. */
    static final String PICTURE_FILE = "glass_picture.jpg";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        SharedPreferences p = getContext().getSharedPreferences(PREFS, 0);
        Settings.migrate(p);
        MatrixCursor c = new MatrixCursor(new String[]{"key", "value"});
        c.addRow(new Object[]{Settings.KEY_OPACITY,
                p.getInt(Settings.KEY_OPACITY, Settings.DEFAULT_OPACITY)});
        c.addRow(new Object[]{Settings.KEY_WHITE,
                p.getBoolean(Settings.KEY_WHITE, Settings.DEFAULT_WHITE) ? 1 : 0});
        c.addRow(new Object[]{Settings.KEY_STYLE,
                p.getInt(Settings.KEY_STYLE, Settings.DEFAULT_STYLE)});
        c.addRow(new Object[]{Settings.KEY_TINT_COLOR,
                p.getInt(Settings.KEY_TINT_COLOR, Settings.DEFAULT_TINT)});
        for (int i = 0; i < Settings.GLASS_KEYS.length; i++) {
            c.addRow(new Object[]{Settings.GLASS_KEYS[i],
                    p.getInt(Settings.GLASS_KEYS[i], Settings.GLASS_DEFAULTS[i])});
        }
        return c;
    }

    /** The picture for live wallpapers, to the launcher only. */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String path = uri.getPath();
        if (path != null && path.startsWith("/font/")) return openFont(path.substring(6));
        if (path != null && path.startsWith("/lockicon/")) return openLockIcon(path.substring(10));
        if (!Settings.PICTURE_URI.getPath().equals(uri.getPath())
                || !Settings.LAUNCHER.equals(getCallingPackage())) {
            throw new FileNotFoundException(uri.toString());
        }
        File f = new File(getContext().getFilesDir(), PICTURE_FILE);
        if (!f.isFile()) throw new FileNotFoundException("no picture");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** The icon an app's notifications get on the lock screen, to System UI only. */
    private ParcelFileDescriptor openLockIcon(String pkg) throws FileNotFoundException {
        if (!LockScreenDiag.SYSTEMUI.equals(getCallingPackage()) || pkg.contains("/")) {
            throw new FileNotFoundException(pkg);
        }
        File f = IconPacks.lockIconFile(getContext(),
                getContext().getSharedPreferences(PREFS, 0), pkg);
        if (f == null) throw new FileNotFoundException("One UI's icon for " + pkg);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** A font chosen for the lock screen notifications, to System UI only. */
    private ParcelFileDescriptor openFont(String which) throws FileNotFoundException {
        if (!LockScreenDiag.SYSTEMUI.equals(getCallingPackage())) {
            throw new FileNotFoundException(which);
        }
        File f = fontFile(getContext(), which);
        if (f == null || !f.isFile()) throw new FileNotFoundException("no font " + which);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** Where the font {@code which} (title or text) is kept, or null for another name. */
    static File fontFile(android.content.Context c, String which) {
        if (!LockFonts.TITLE.equals(which) && !LockFonts.TEXT.equals(which)) return null;
        return new File(c.getFilesDir(), "font_" + which);
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    /** The launcher reports what its liquid glass is doing; the settings screen shows it. */
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        if (values != null && LockScreenDiag.SYSTEMUI.equals(getCallingPackage())) {
            // An app whose notifications showed on the lock screen (Secure Folder's too).
            String seen = values.getAsString(IconPacks.KEY_SEEN);
            if (seen == null || seen.isEmpty()) return 0;
            SharedPreferences p = getContext().getSharedPreferences(PREFS, 0);
            java.util.Set<String> set = new java.util.TreeSet<>(
                    p.getStringSet(IconPacks.KEY_SEEN, new java.util.HashSet<>()));
            if (!set.add(seen)) return 0;
            p.edit().putStringSet(IconPacks.KEY_SEEN, set).apply();
            return 1;
        }
        if (values == null || !Settings.LAUNCHER.equals(getCallingPackage())) return 0;
        String status = values.getAsString(Settings.KEY_STATUS);
        if (status == null) return 0;
        getContext().getSharedPreferences(PREFS, 0).edit()
                .putString(Settings.KEY_STATUS, status).apply();
        return 1;
    }
}
