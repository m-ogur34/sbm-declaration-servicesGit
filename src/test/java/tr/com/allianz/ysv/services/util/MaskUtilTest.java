package tr.com.allianz.ysv.services.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskUtilTest {

    @Test
    void mask_keepsFirstTenCharacters() {
        assertThat(MaskUtil.mask("MOCK-TEST-ACCESS-TOKEN-VALUE")).isEqualTo("MOCK-TEST-***");
    }

    @Test
    void mask_hidesShortValuesCompletely() {
        assertThat(MaskUtil.mask("8677399731")).isEqualTo("***");
        assertThat(MaskUtil.mask("")).isEqualTo("***");
    }

    @Test
    void mask_returnsNullForNull() {
        assertThat(MaskUtil.mask(null)).isNull();
    }

    @org.junit.jupiter.api.Test
    void maskIdentity_hidesPersonalNumbersButNotTheCompanyTaxNumber() {
        assertThat(MaskUtil.maskIdentity("1", "12345678901")).isEqualTo("12*******01");
        assertThat(MaskUtil.maskIdentity("4", "99123456789")).isEqualTo("99*******89");
        assertThat(MaskUtil.maskIdentity("2", "8000013270")).isEqualTo("8000013270");
        assertThat(MaskUtil.maskIdentity("1", "123")).isEqualTo("***");
        assertThat(MaskUtil.maskIdentity("1", null)).isNull();
    }
}
