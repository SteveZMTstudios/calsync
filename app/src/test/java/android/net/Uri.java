package android.net;

import java.util.Objects;

public class Uri {
    private final String raw;

    private Uri(String raw) {
        this.raw = raw;
    }

    public static Uri parse(String raw) {
        return new Uri(raw);
    }

    public String getLastPathSegment() {
        int idx = raw.lastIndexOf('/');
        if (idx < 0 || idx == raw.length() - 1) {
            return null;
        }
        return raw.substring(idx + 1);
    }

    @Override
    public String toString() {
        return raw;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Uri)) return false;
        Uri that = (Uri) other;
        return Objects.equals(raw, that.raw);
    }

    @Override
    public int hashCode() {
        return Objects.hash(raw);
    }
}
