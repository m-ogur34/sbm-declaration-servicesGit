package tr.com.allianz.ysv.services.enums;

import lombok.Getter;


@Getter
public enum MovableType {

    MENKUL("MENKUL"),
    GAYRIMENKUL("GAYRIMENKUL");

    /** The value SBM's REST contract expects. */
    private final String sbmValue;

    MovableType(String sbmValue) {
        this.sbmValue = sbmValue;
    }


    public static MovableType fromSbmValue(String sbmValue) {
        if (sbmValue != null) {
            for (MovableType type : values()) {
                if (type.sbmValue.equalsIgnoreCase(sbmValue.trim())) {
                    return type;
                }
            }
        }
        throw new IllegalArgumentException("Gecersiz menkul tipi degeri: " + sbmValue);
    }

    public static MovableType fromExcel(String raw) {
        if (raw != null) {
            String value = raw.trim();
            if (value.endsWith(".0")) {
                value = value.substring(0, value.length() - 2);
            }
            if ("1".equals(value)) {
                return MENKUL;
            }
            if ("2".equals(value)) {
                return GAYRIMENKUL;
            }
            for (MovableType type : values()) {
                if (type.sbmValue.equalsIgnoreCase(value)) {
                    return type;
                }
            }
        }
        throw new IllegalArgumentException("Geçersiz menkulTipi değeri: " + raw
                + " (beklenen: 1/2 veya MENKUL/GAYRIMENKUL)");
    }
}
