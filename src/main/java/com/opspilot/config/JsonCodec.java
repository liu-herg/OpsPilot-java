package com.opspilot.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** JSON 列（data / payload / result）和 Map 之间的转换。 */
@Component
public class JsonCodec {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public JsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("无法序列化为 JSON", e);
        }
    }

    public Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 格式错误", e);
        }
    }

    /** 对象（例如网关返回的 record）转成 Map，字段名按全局配置是下划线风格。 */
    public Map<String, Object> toMap(Object value) {
        return objectMapper.convertValue(value, MAP);
    }

    /** 构造事件 payload：k1, v1, k2, v2…，值为 null 的键跳过。 */
    public static Map<String, Object> fields(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("参数必须成对出现");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        return map;
    }
}
