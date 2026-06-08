package android.content;

import android.net.Uri;

public final class ContentUris {
    private ContentUris() {}

    public static Uri withAppendedId(Uri contentUri, long id) {
        return Uri.parse(contentUri.toString().replaceAll("/+$", "") + "/" + id);
    }

    public static long parseId(Uri contentUri) {
        String last = contentUri.getLastPathSegment();
        if (last == null) {
            throw new NumberFormatException("No id in URI: " + contentUri);
        }
        return Long.parseLong(last);
    }
}
