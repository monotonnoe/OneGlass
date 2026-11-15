package com.monotonnoe.oneglass.ui;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.monotonnoe.oneglass.Config;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Serves the picture chosen for live wallpapers to One UI Home (only), which uses it as the
 * backdrop seen through the glass. A live wallpaper has no image the launcher could read.
 */
public final class PictureProvider extends ContentProvider {

    static final String FILE = "glass_picture.jpg";

    static File file(android.content.Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String caller = getCallingPackage();
        if (!Config.LAUNCHER.equals(caller) && !getContext().getPackageName().equals(caller)) {
            throw new FileNotFoundException("not for " + caller);
        }
        File f = file(getContext());
        if (!f.isFile()) {
            throw new FileNotFoundException("no picture");
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }
}
