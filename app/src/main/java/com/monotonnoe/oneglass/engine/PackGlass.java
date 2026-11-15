package com.monotonnoe.oneglass.engine;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

/**
 * The glass that iGlass OS bakes under every icon: a white squircle whose opacity fades from
 * the top down, with a bright rim. Measured from the pack itself (the 3rd percentile of the
 * opacity over 600 of its icons, so no glyph survives), 192 by 192, as a PNG. An icon from the
 * pack is that glass plus the glyph, so the glyph is what each pixel has above the glass.
 */
final class PackGlass {
    private PackGlass() {
    }

    private static final int SIZE = 192;
    /** Where the glass is (opacity above 48) in the template, as {@code contentBounds} sees it. */
    private static final int EDGE_LO = 7;
    private static final int EDGE_HI = 185;
    /** A pixel must beat the glass by this much to be glyph at all. */
    private static final int DEAD = 12;

    private static final String TEMPLATE_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAMAAAADACAAAAAB3tzPbAAAUy0lEQVR42u1dW48cx3U+p3ouu7MXLsm9UKYkiiJs" + 
            "S7HsIHEA58F5iGEgD0GABAj8ECCBH/xiIAjyxwzEMAInDw4iIA/ODXDg2BBFyYpE8Sruklzu7uxMd9fnh+6qOnXp" + 
            "nt7hcncYsAdc7sz29NTpOpfvfOdUDdGr49Xx6nh1vDqe4+CTvmG4vrrSH/YG/d7w4uryaDjqV5eA+EmA+UWTJoAI" + 
            "JRFBA6Q1CCVpaAD6eDzZz589LnRRFrrMpzp/cQKMdr6085XXVcZEpBmEaoBE0AwiBYAIjGrgujpB61LXJ5aatAYA" + 
            "raFJk67FBHGZgXQGYqXo84+Ojg8npy3A5mtf+9aAGYAuSp2Xuih1WZakNTNQAEykWWtm1mAQlyDSUASgqEUFmAgg" + 
            "ACAGAwQCQykiKFAGxdRj1ctUVvzPw6dHpybA1jvfvcwaZT4dH4/HRaGnGqWu7j4xnOYQWOgRUfQkPhAOgFXGmRr0" + 
            "++rZf+4enIYAX/nu72gqjw/2948nOi+rYdvRcTUGeBcDu8d8pqmyfrY07H/yvw+eV4Cv/uUO8mdP9g+Oco2z9S9Z" + 
            "f2nU3/+3+88jwPU/fwvHX+zuH5U6Nd1zHeDOF1K94drwwb8/nFeA4Z/8MR0/vrc3Qa0rREyotL5WfjZqRFypEdcK" + 
            "RNUPawQMYgKDq6s4VTPSVO6L7UXqsQ2G68P/+vVkLgHe+eul4tHdvekZa044C4PRKv/z3ZMLkP3ZH9Hjzx9Oznf4" + 
            "RETZ8troF7/ITyjA1g828zu3D/RCoIX+6trBTw9OJMCNH/Lh7TsTkFPRNjfebJUNJ4jnYPlcnujeq1Y2Bj9Ju6Ms" + 
            "+ep7P8QXt+5L7RcmJl+h8AWmyoLNC+zsO7ht7hzPLXjXNc4A+TT7xsP9zgL8wffLB7f2ygXCnCgK9bXHjzsK8M2/" + 
            "Ke59uA9aqKPM1bu7TzoJ8O4Pik9vjtEdvpyRBIV6785BBwGu/p3+9ONxFY2I6oAUhK86HsGqMBscbVQ/1Pz6LVSb" + 
            "CZkwZwG41X1zJtcB0eYYZa6+fmsyU4Dlvx/evXm0YPpTz4EevP1hOUuA7914/MGzhRw/kdbruDdDgD/808MPdvVz" + 
            "aXzjO57beFDwjb0nrQJs/W3x8e3CqL507ealODRI12//q2yEHZSrNT480xmaMSsXK2yiYYeiy+yrN/M2Af5q58Gt" + 
            "4wXmIKBH2ec+2POefeP3Dj86WuDxkz5+8vWdZgHU98rPHuvmZHZe2wb8C3S4TtOZ5dHht70XevLJty4++GzqFNyD" + 
            "PyJ5Dz2+iw5pG6nfyYSmcYkoIRM3ea659vTg8tU7DTOQ/cX09rG7A/A+ComJsDLVPxDfRLiz0BLcE3+sSaNg8kiP" + 
            "j77dpEK/v/rofk6LfuTj0WsNAnynvD8NbqGlCOHfLYSKAHMb4e54cFvtZCChRmgyt+CTQHo8eS9tA9fevv9FGZFS" + 
            "iD7BjjKWgAIJ6EVIkB++vr6fmoHfLXYPS1r8ozwutlMqpL4zfli82Cg02xujmxV8M6VCN0Z390uy2Nn5NYMnpOsM" + 
            "sbIL//5rbGG55YjY81byz8bd+tkrh0CFy/GW0yE3A28VTydSlQMlhGcXSBO4nq0b1+r8AFKqH5YUYqWHH9dQ5vml" + 
            "hAq9lz99GSyAiKiYXokF6L87fVKmcUAYuCCVAEgHOe/uerMIEf/EteUUNMa96tPLyTsqsoEr9KyRxopCL4Ixd5SA" + 
            "HK7w4nRs5bGysfO9KHI9Oghn4Ar2zyIK41TeWBQrkQpt6mOcxXDwPFJYVJ0PIhXaKA5FGOb0ZzA8Go2F1wuxKAVU" + 
            "g/3Vk0D4a8PCNZN/7mlZLkUz8MZ0jJm3Fj5S95Eo2mMXqNEoA4TSNArhOcqtSIA3p2PQy3KU+q1QhUb94xyiPMIi" + 
            "qeCaiCK2r3kKZLJ6k8lDqpojumDDsanziHPZK+VU+byf5suIXBaclb4AF2hSwguhfjiNVMb/RSJUBHqOKD/yvHHg" + 
            "lmUGFLhwoYcleqWvQmuUl/TyHBrDwAZWKNcmMoYBEsnwIqOUSf8A2MmAlyfYXAcQEV04AISxq7pYKqkCAXoQ2MAq" + 
            "pgBp9gOkqbzb/xskiGc6lMDPdZIuLAn1kkkVSJdZMANLmILApuRU3xWuRn9OhDq1sQD9QIDl8ljX4zXg3bCLVV9G" + 
            "G92TSFeABqzUSHQgMW5tAwzgQyyoQIX6xRS+Q7BTrrkpDJGX6jSyV/NLwHAhUpysuSxDAYbN08UvCJ7NfzBBhzYw" + 
            "0Hr2sBrRwAvDp81gIpyBdV0AXp9M9Yw9I54tAWNeERJlW1T5MzPAUDBjAqPUq8EMrIfNNLXj1mjAmacLsFPvlLZM" + 
            "RPC6kPRKCOa09huVwLVT5ed2o3Pz3FwncBw9ENEqPa117aDYuSPN4Lq/zxZUHKhyU+PX2KOypsBkgnN2DA48+/Ry" + 
            "C4ZR4zqimrgdQomR9rlvx/SwT5N4UTPw/SHLmFCQKD6EaTH83AJU3W6gViQzgFCAqrFz0Y5agYwaGQ2RU2ZUaO3Q" + 
            "TBFDaYbSzJrhevpcBmAarSCK3qImKDNNh+5dShFTerIiGJxZt3MylGZiYs1aaSZofTGiFnXJlcYwmEwvGCIML7RI" + 
            "6BHCbFOqnFSVVM4IHyki+Fg7ntqsmcA6JrZScOe8QFw0CoQPp0RKGA9IV2JoaKrCGkgbzgxAirEiUT8CWQhvIFjd" + 
            "qCswGWS+IeYUiLi+qrGZKpRc3fwaZybY6bojulZ8AlhzBeNcvtomQaAqfhnDw2RebogE7Hd/56obu25VZlA1Pi2s" + 
            "2AUyH0jUnVLs/Oi5KRBa8aS0ARfmfAaz7b4HVK9lgxGaqNQN+AECISEFoW8OSxARWDMx4DIvq0LQRms0V79V9q89" + 
            "JBFJwBHV6xHMDU4plCAmhys1q8eja19qNBosrEiRjHkcadEMJIQXmSMwGh9uBpSvbukHmjFjZ642cb8x4531XZRJ" + 
            "uqUwOFQhKkGxu5U+I65vNUrAqQShgwSMlCImVRRxke8U0T7m1KZ5NE/Nkx1i3nNOp7xBRFaDnBcCUEcxL19QdTho" + 
            "uKDoqQpQvZcEQBZnQ0WJ2r28SkSVUtZKowAFhhyPCq1eo3az1QNAW0Jl/hqj+pjDS7cYJHIHP64zVXCmGg1rmxeE" + 
            "gYzClN6i73OMxU1+kWIvRJpsFIY/lW5tUp0gwKkOuWTA/lrXjVxRAQae27e7KwjVEaDBaZxLI3Wd3YJBmlI2QDbh" + 
            "sKtHGAF5H3ShIAi78AF9y+tojxGUbu8ybG0CTte4ybG6RLQo6YD537JVAKt2KJGkxUApHBNn9JbEn7EmLrZspN9n" + 
            "HJ0cX8jMUUEgp2YQKM25uRNKQHNKENf+UXO69pFxiIWgROofpaxYDF2SbiiygbImgdh17nShFJ9ftM6Ali1bx8I8" + 
            "rQpBy4BYOULR+8yJViRzpmiwDiJy5NPD6OyqDJABuFZghkfzsaVpiCMVorIqiIBA2vbUiPpeXPcMsy3MUDc0INFE" + 
            "doMUI0O25gfhIQM3ymzIXDBoUUr3aCm5KKFBAfZmpOIKKGZE0UVWJKtghGRKGbkhWeutcF2UE5cgzUSCo6bOEtCc" + 
            "EoTkASPd26htdl6NTcc2UKuQA6K10eJMdQOdMZ0KBdBN0G9RGOpU7cXPibVLitn+E8UBFnCK7ZphsZzY4o/Umqwa" + 
            "jVqcKLpS2V2U6uXJJDtYYGAEySJHBCWQSNQlhUdB46jfJu2ZC1KtKkj3+fnN4j42RTSg6qZqBnGWyolj1VuwgofN" + 
            "FNO8UNS+0c0CcIZW7iAxAhUqNIUVHBAhWILiTw57nios4/mN0xKIyN6r8JJI27AAcwSWRqwcOU2x60GyryGoCVBT" + 
            "GQ+JjhyvUasDyecSV+t92DMPJeiFIKdflJTMjd6kjETci2agDFI49tNVv9sFDbyy7FBHgoPxSjDS2yDcHscDeZJr" + 
            "tnc7Suq1rN5BpNBJbNLEjDdKwK5kGlWSDf/QKEHt0GVxkdvcKLuFj6fsT+al46MtZKI4gGS3DZ+Rc5zjCqEKaYDA" + 
            "pBlcMYzMgJLZULT9gOM1be+qXdfqLWCpGyzEJfysjEnkdQFByiSabNj+6ozSCFCWVMFpV82B+8mxgobUA9IFR9EV" + 
            "iwRkCQpOSBmbdh7IRjHdmJElcd/5elMmRpiBqkw1xIGYxkimwPFsIFFzQaKPmZJrhiTj5MVIpJMlFbESdT4vC4NV" + 
            "cp9O4hmnIgGlJPC1yVLk8Hf+iSJxEXTMAXbyzhjApV2os2XfP7ZnZDRncQCn6m1T9QG7lr4n8YHX0KDrtoTAJ0qS" + 
            "y7UOhVUm+KBVbBHTRH0FWNRSW4Y7s4odoLSemIEYOjn33QQWmlOtBFHVxrSm2ZX6w3TdrIIoo3QqpCnRNeoKzefq" + 
            "R03/qCgQxLRKaU2FbImx4lksrQfr4+T6Rwi+CeT6/i0dBUf7U9Bu4y0bAFK1GpjqHQFEXEUwqKjQXQIg2+dROyqI" + 
            "fnafgooSeHgeG8HbfMDsSZDqBUlIYEgGo0WcyshEIzovTlYPEnyK6AiKIrEOy4ZWpHRDB9KxKChGpdakz8bciJgI" + 
            "6eS9a/ScEUMoq9HhptanGRKECQ+jiwSMQIIoKpFFC1GVskRM4oNObXvC+c5ybidsoKl2PfUysobOtFYrwAuTpXtt" + 
            "zgdzglexOSwYzaIgFjNISKJGXfjr82fbr+k9ltcAUrSKQBoQq7tadnRBA7HacA/jBWHdJkvyp1pwA34kFj1yth7I" + 
            "XkvDuVKiAdrmLBHIWOZlLmcVUI1d3zeLFaNElNyswXVgm4zZUx6H8hxidiu/yPL07qNdgtCLknquOhrJrngQbEyY" + 
            "2iJuq6TUauBoqUG03wjC+AtJSSJYtgBhdDryQhCFbnqhrMppKFWU0OyZ9g+Rp2p4NeCGu52q9Pkba0BupwHM6glE" + 
            "Q+rszSI/DVSIdewbKhrYbxnqLkG8lA+pimZiET0aUmchQVylPMB51C/m/SxW2SQQIFdEKQtt4+5P8uGnfRt4msqJ" + 
            "GTVUUsZ1qEgnmko2lOrCZbRKEG2GcwIBQi80AdfUhAvXycui4z1Fl3MwF0QCkVLTQIDCriA22I+JFqXbA7LzlInB" + 
            "mcqjQKZEaDJchlhL5fZasJVusdm1bHaECbFeqU8s+yP4BUHDTqdAnigkulIfgXuhAKVhSxM8SbBTAiheBh9luIgD" + 
            "cmKXD0RhOZkZeetw6gWueahCGb1MR0+FbnRqAJ/ZDIHIrqny2nWSbscLtPINHnsB2fjlCBXTc+tRK0ASgJsx9fpH" + 
            "gQoVmhO9Doj2LGyVwN/Eg9NlZEr3bEYKBmqMyqAsOwwDGdQZhNXT8mrcy8aRCqnWD8OLc5En/wSoYe8gUKG8JbJy" + 
            "Akgk090GOoDD5RLeix0kiFJuNeDD0AZawD86UAYtITs9xHnX1oCIKFtWoQ2U0746Ewd4GrrISk1DN0r3M7aMsrc8" + 
            "tV6MCufwALfAFeap3MUmiIN+Gy2CLW6sa7Jb43jdFHY89SAAgAfL90JeiKb9zOwsUdVmRN+cnwGndyKJA3PINYct" + 
            "FvHGSREMgM9P2+rA8vB+mFLSNFOipct7LEYWLB5qebgXCTDxt6AGnezZrO1v0PAnRLlv8npy5SyIh71HkQodoocu" + 
            "Y463umnrQ8FMNxVvawOi+EsjZC6ObJQlZgDd3BDOxB21f8pwTX0RCUB3Ri8LIFWrq7fKSIXoUPVzu6GD6MEn20Rr" + 
            "6UXRnmtzF/HtPj53aHt7zSXlrnQUlI/FNjoMsTs9ycJ0trr0GcUC7NNwjCBhiQgZJPpqko0QCD2rvCRib4xEpThK" + 
            "q+o39i8OfkOxCh0dvCQ6pFY3nt1NCEAPBoP57K77UiF0Yi5mmHd28cKvKKFCtPv28hG8BbNescVl8eJPDMeLMxJ8" + 
            "D9nNIcxmKQi27vS/foHTWx9LCZauLP2SUjMwubvWRwM5Ge5nkdJgpAKYNAfEl4kyQMyklXqXt24/TApAe7yiFt8E" + 
            "htuj/6C0AE+ervfT+/+ifde9E3B1qRlqqKWn+dje5tbeh+K553iK7XLSga1MtZm37Vfb0Jfehtwar8ajt668/6Bh" + 
            "Bmhv9+Kwgy9oul2tU3MiCNJ8td7Wzt4vqUkAukvrvYU2AHXhjY33qVmA/Tury4oamx1TLE6a70FDlUXaAZrBd8qH" + 
            "EYFo8KWdD/7P+3sQfI+2l8ZFoHrcdVe4EwPVE7+j/9r1pR+1fpFOOd1SU72oCpRtXt/8l+ALEkP4c8SbxRSLOX5e" + 
            "+/LVmz8PrSI86/PdSyvsEbLJqNyUbvmrZuIVumGZEjO+W0dSWitv7Oz9LJqV6B2HV1YmBTror79IgmU7Z6tys3xX" + 
            "F0Oo84GV62+OfjSeKQAV+zuDqV44LeLRm9fW/yH+mtZECjA53h4unAS8eu2NjZ98Sl0EoKPj7WFRnpIEp9Ozptau" + 
            "Xdv46cfUTQA6Gm+vTAvtNNCtXRVfJChBvOuOMbsrmmqg2C5AvEF8067ck4W9nQAcDZ5dvHF1/R8/oq4C0NH+pXWt" + 
            "y0XpVhns3Lja+/Gn1F0Amuz2N1WxGIagRq9/eevRjx/RSQSg8ilvLqM8/6DMg8vX39789T8dtzjlhuPSteHB/qTa" + 
            "r9Fs1sOzrLP9W3SD5+0GDibi/srO61vTn91qjSqN0OnqFTx9lusGFJZayuBbX1gcCrfhkufJzVYNhca91e2d7eGv" + 
            "/jWnuQQgWt/ZLJ4e5ueiSCrrr25vbqw8fP8u0bwCEG1sXS6fjfPibGVg7i2tbqxtXhje/fnHs3DGrGPj0g6OjyZF" + 
            "WZzJyEmprNdfubB2aWWkPvnv38wGSh2IjEsXNmhyXBRFqXXjVGh1ok9i73cmRZxlKsuWhqPlpaW1pUF274MP9zqI" + 
            "3O3OjFZWLg4Y07ycaF2tV4GuKyYuVNuFywxlNpCoFsAzmBVBEYEVMbg6hcGsOFO9fqZUX/X7g1623Buymt785P5e" + 
            "tzk7QTxcWh4OLhMRFVySphKaSLsFsKSq3XEUlCKVEStmJkWsmEkpRczMUKwUmJmYFZhVxpSpLMu4xxmXjOLh7sOH" + 
            "e92/nvTEaKGv+kr1lGKlsgxZtjKoVLdutGbFxKQUKyaFLINSihUrKCZWiplYcVZ/LhMVhzrHNJ/meTEtJuPDo4X+" + 
            "ZtVXx6vj1fHq+P93/BajrnqVzFmvIwAAAABJRU5ErkJggg==";

    /** The template's opacity, and the same widened by a pixel (resampling tolerance). */
    private static int[] glass;
    private static int[] wide;
    /** Why the last {@link #peel} answered as it did, for the log. */
    static volatile String why = "";

    private static synchronized boolean load() {
        if (glass != null) return true;
        try {
            byte[] png = Base64.decode(TEMPLATE_PNG, Base64.DEFAULT);
            Bitmap b = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (b == null || b.getWidth() != SIZE || b.getHeight() != SIZE) return false;
            int[] px = new int[SIZE * SIZE];
            b.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE);
            b.recycle();
            int[] g = new int[SIZE * SIZE];
            // A grey PNG decodes to opaque grey: the value is in any channel.
            for (int i = 0; i < g.length; i++) g[i] = px[i] & 0xFF;
            int[] w = new int[SIZE * SIZE];
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    int m = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        int yy = y + dy;
                        if (yy < 0 || yy >= SIZE) continue;
                        for (int dx = -1; dx <= 1; dx++) {
                            int xx = x + dx;
                            if (xx < 0 || xx >= SIZE) continue;
                            m = Math.max(m, g[yy * SIZE + xx]);
                        }
                    }
                    w[y * SIZE + x] = m;
                }
            }
            glass = g;
            wide = w;
            return true;
        } catch (Throwable t) {
            XLog.e("pack glass template", t);
            return false;
        }
    }

    /**
     * The glyph of {@code src} (an icon drawn anywhere in it) without the pack's glass, the
     * same size as {@code src}, in the icon's own colours; null when the icon is not on this
     * glass. The glass is fitted to the icon's extent (its pixels above 48 of opacity, the
     * template's own extent being known), so the icon may sit in padding or be scaled.
     */
    static Bitmap peel(Bitmap src) {
        if (!load()) {
            why = "no template";
            return null;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int left = w, top = h, right = -1, bottom = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) > 48) {
                    if (x < left) left = x;
                    if (x > right) right = x;
                    if (y < top) top = y;
                    bottom = y;
                }
            }
        }
        if (right < left || bottom < top) {
            why = "empty";
            return null;
        }
        int ew = right + 1 - left;
        int eh = bottom + 1 - top;
        if (ew < 16 || eh < 16) {
            why = "tiny " + ew + "x" + eh;
            return null;
        }
        float sx = (float) (EDGE_HI - EDGE_LO) / ew;
        float sy = (float) (EDGE_HI - EDGE_LO) / eh;

        // The icon must carry the glass: where the template is glass, at least that much.
        int glassPx = 0;
        int matched = 0;
        int glyphPx = 0;
        for (int y = 0; y < h; y++) {
            int ty = Math.max(0, Math.min(SIZE - 1, (int) ((y + 0.5f - top) * sy + EDGE_LO)));
            boolean rowIn = y >= top && y <= bottom;
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int a = px[i] >>> 24;
                int t = 0;
                int tw = 0;
                if (rowIn && x >= left && x <= right) {
                    int tx = Math.max(0, Math.min(SIZE - 1,
                            (int) ((x + 0.5f - left) * sx + EDGE_LO)));
                    t = glass[ty * SIZE + tx];
                    tw = wide[ty * SIZE + tx];
                }
                if (t > 24) {
                    glassPx++;
                    if (a >= t - 20) matched++;
                }
                float g = a == 0 ? 0f : Math.max(0f, Math.min(1f,
                        (a - tw - DEAD) / (float) Math.max(1, 255 - tw - DEAD)));
                if (g > 0.5f) glyphPx++;
                px[i] = (Math.round(255 * g) << 24) | (px[i] & 0x00FFFFFF);
            }
        }
        if (glassPx == 0 || matched < glassPx * 0.9f) {
            why = "no glass: " + matched + "/" + glassPx;
            return null;
        }
        why = "ok, glyph " + glyphPx + "px";
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }
}
