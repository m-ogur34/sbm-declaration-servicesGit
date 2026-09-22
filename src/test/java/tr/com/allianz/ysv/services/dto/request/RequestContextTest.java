package tr.com.allianz.ysv.services.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RequestContextTest {

    @Test
    @DisplayName("no headers: SYSTEM user, no requester -> the token service uses the company VKN")
    void empty_isSystemWithoutRequester() {
        RequestContext context = RequestContext.of(null, null, null);

        assertThat(context).isEqualTo(RequestContext.system());
        assertThat(context.userName()).isEqualTo("SYSTEM");
        assertThat(context.hasRequester()).isFalse();
    }

    @Test
    void blankValues_areTreatedAsMissing() {
        RequestContext context = RequestContext.of("  ", " ", "");

        assertThat(context.userName()).isEqualTo("SYSTEM");
        assertThat(context.hasRequester()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"1, 12345678901", "2, 8000013270", "4, 99123456789"})
    void validIdentities_areAccepted(String type, String number) {
        RequestContext context = RequestContext.of(" muhammed.ogur ", type, " " + number + " ");

        assertThat(context.userName()).isEqualTo("muhammed.ogur");
        assertThat(context.requesterIdType()).isEqualTo(type);
        assertThat(context.requesterIdNo()).isEqualTo(number);
        assertThat(context.hasRequester()).isTrue();
    }

    @Test
    void typeWithoutNumber_isRejected() {
        assertThatThrownBy(() -> RequestContext.of("u", "1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("birlikte");
    }

    @Test
    void numberWithoutType_isRejected() {
        assertThatThrownBy(() -> RequestContext.of("u", null, "12345678901"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("birlikte");
    }

    @Test
    void unknownType_isRejected() {
        assertThatThrownBy(() -> RequestContext.of("u", "3", "12345678901"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 (T.C. Kimlik No)");
    }

    @ParameterizedTest
    @CsvSource({"1, 1234567890", "1, 123456789012", "2, 12345678901", "4, 1234567890A", "1, 1234567890'"})
    void wrongLengthOrNonDigits_isRejected(String type, String number) {
        assertThatThrownBy(() -> RequestContext.of("u", type, number))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("haneli sayı");
    }

    @Test
    void tooLongUserName_isRejected() {
        assertThatThrownBy(() -> RequestContext.of("x".repeat(RequestContext.USER_NAME_MAX_LENGTH + 1), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a user name with control characters is refused (log / header injection)")
    void controlCharactersInUserName_areRejected() {
        assertThatThrownBy(() -> RequestContext.of("ali\r\nX-Injected: 1", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
