package tr.com.allianz.ysv.services.util;


public final class DistrictCodeResolver {

    private DistrictCodeResolver() {
    }


    public static Integer resolve(Integer districtCode) {
        if (districtCode == null || districtCode == 0) {
            return null;
        }
        return districtCode;
    }
}
