package tr.com.allianz.ysv.services.util;


public final class MaskUtil {

    private static final int VISIBLE_PREFIX_LENGTH = 10;
    private static final String SUFFIX = "***";

    private MaskUtil() {
    }


    public static String mask(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= VISIBLE_PREFIX_LENGTH) {
            return SUFFIX;
        }
        return value.substring(0, VISIBLE_PREFIX_LENGTH) + SUFFIX;
    }
}
