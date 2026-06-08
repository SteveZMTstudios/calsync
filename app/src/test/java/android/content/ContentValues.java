package android.content;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class ContentValues {
    private final HashMap<String, Object> mValues;

    public ContentValues() { mValues = new HashMap<>(8); }
    public ContentValues(int size) { mValues = new HashMap<>(size, 1.0f); }
    public ContentValues(ContentValues from) { mValues = new HashMap<>(from.mValues); }
    
    public void put(String key, String value) { mValues.put(key, value); }
    public void put(String key, Byte value) { mValues.put(key, value); }
    public void put(String key, Short value) { mValues.put(key, value); }
    public void put(String key, Integer value) { mValues.put(key, value); }
    public void put(String key, Long value) { mValues.put(key, value); }
    public void put(String key, Float value) { mValues.put(key, value); }
    public void put(String key, Double value) { mValues.put(key, value); }
    public void put(String key, Boolean value) { mValues.put(key, value); }
    public void put(String key, byte[] value) { mValues.put(key, value); }
    public void putNull(String key) { mValues.put(key, null); }
    
    public int size() { return mValues.size(); }
    public boolean isEmpty() { return mValues.isEmpty(); }
    public void remove(String key) { mValues.remove(key); }
    public void clear() { mValues.clear(); }
    public boolean containsKey(String key) { return mValues.containsKey(key); }
    public Object get(String key) { return mValues.get(key); }
    public String getAsString(String key) { Object v = mValues.get(key); return v != null ? v.toString() : null; }
    public Long getAsLong(String key) { Object v = mValues.get(key); return v instanceof Number ? ((Number)v).longValue() : null; }
    public Integer getAsInteger(String key) { Object v = mValues.get(key); return v instanceof Number ? ((Number)v).intValue() : null; }
    public Short getAsShort(String key) { Object v = mValues.get(key); return v instanceof Number ? ((Number)v).shortValue() : null; }
    public Byte getAsByte(String key) { Object v = mValues.get(key); return v instanceof Number ? ((Number)v).byteValue() : null; }
    public Double getAsDouble(String key) { Object v = mValues.get(key); return v instanceof Number ? ((Number)v).doubleValue() : null; }
    public Float getAsFloat(String key) { Object v = mValues.get(key); return v instanceof Number ? ((Number)v).floatValue() : null; }
    public Boolean getAsBoolean(String key) { Object v = mValues.get(key); return v instanceof Boolean ? (Boolean)v : null; }
    public byte[] getAsByteArray(String key) { Object v = mValues.get(key); return v instanceof byte[] ? (byte[])v : null; }
    public Set<Map.Entry<String, Object>> valueSet() { return mValues.entrySet(); }
}
