package com.monotonnoe.oneglass.glass;

import android.graphics.Matrix;
import android.view.View;
import android.view.ViewParent;

/** Public-API version of the hidden View#transformMatrixToGlobal. */
public final class MatrixUtil {

    private static final int[] LOC = new int[2];

    private MatrixUtil() {
    }

    /** Maps a point in {@code v}'s own coordinates (its top-left = 0,0) to display coordinates. */
    public static void localToScreen(View v, Matrix out) {
        out.reset();
        View cur = v;
        while (true) {
            Matrix m = cur.getMatrix();
            if (!m.isIdentity()) {
                out.postConcat(m);
            }
            out.postTranslate(cur.getLeft(), cur.getTop());
            ViewParent p = cur.getParent();
            if (!(p instanceof View)) {
                break;
            }
            View pv = (View) p;
            out.postTranslate(-pv.getScrollX(), -pv.getScrollY());
            cur = pv;
        }
        // cur is the window's root view: add where the window sits on the display.
        cur.getLocationOnScreen(LOC);
        out.postTranslate(LOC[0] - cur.getLeft(), LOC[1] - cur.getTop());
    }
}
