package tr.com.allianz.ysv.services.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;


@Slf4j
@Component
@RequiredArgsConstructor
public class JsonUtil {


    public static final int ERROR_DETAILS_MAX_LENGTH = 2000;

    private static final String UNSERIALIZABLE = "{\"error\":\"payload serialize edilemedi\"}";

    private final ObjectMapper objectMapper;


    public String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            log.warn("Payload could not be serialized to JSON: {}", ex.getMessage());
            return UNSERIALIZABLE;
        }
    }


    public <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            log.warn("Response body could not be parsed as {}: {}", type.getSimpleName(), ex.getMessage());
            return null;
        }
    }


    /** @return JSON ağacı; değer boş ya da JSON değilse {@code null} */
    public JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    public JsonNode toTree(Object value) {
        return objectMapper.valueToTree(value);
    }

    public static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
