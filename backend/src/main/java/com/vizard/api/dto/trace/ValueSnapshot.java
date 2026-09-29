package com.vizard.api.dto.trace;

/**
 * One Java value at one moment.
 *
 * <ul>
 *   <li>{@code primitive}: int, double, char, boolean... (boxed Integer etc. are shown the same way)</li>
 *   <li>{@code string}: a String, copied by value</li>
 *   <li>{@code null}</li>
 *   <li>{@code ref}: an array or object; its contents are in the step's heap under {@code ref}</li>
 * </ul>
 *
 * @param value   the raw value for primitives/strings (null for NaN/Infinity, which JSON can't hold)
 * @param display how a Java programmer would write the value: 5, 2.5, 'a', "hi", null, int[]
 * @param ref     heap id for arrays/objects, else null
 */
public record ValueSnapshot(String kind, String type, Object value, String display, Long ref) {

    public static ValueSnapshot primitive(String type, Object value, String display) {
        return new ValueSnapshot("primitive", type, value, display, null);
    }

    public static ValueSnapshot string(String value) {
        return new ValueSnapshot("string", "String", value, "\"" + value + "\"", null);
    }

    public static ValueSnapshot nullValue() {
        return new ValueSnapshot("null", null, null, "null", null);
    }

    public static ValueSnapshot reference(long id, String type) {
        return new ValueSnapshot("ref", type, null, type, id);
    }
}
