package com.aitoolcheck.ai_toolcheck1_backend.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class SensitiveDataMasker {

    private final ObjectMapper objectMapper;

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "authorization", "bearer", "accesstoken", "refreshtoken", "token",
            "password", "oldpassword", "newpassword", "confirmpassword",
            "apikey", "api_key", "secret", "clientsecret", "privatekey",
            "cookie", "set-cookie"
    );

    private static final String MASKED_VALUE = "***MASKED***";

    // Fallback regex pattern for non-JSON strings (e.g. raw headers "Authorization: Bearer abc")
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "(?i)(authorization|bearer|accesstoken|refreshtoken|token|password|apikey|api_key|secret|cookie)\\s*[:=]\\s*[\"']?([^\"'\\s,;]+)[\"']?",
            Pattern.CASE_INSENSITIVE
    );

    public SensitiveDataMasker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String mask(String input) {
        if (input == null || input.trim().isEmpty()) {
            return input;
        }

        try {
            JsonNode rootNode = objectMapper.readTree(input);
            if (rootNode.isObject() || rootNode.isArray()) {
                maskJsonNode(rootNode);
                return objectMapper.writeValueAsString(rootNode);
            }
        } catch (JsonProcessingException e) {
            // Not a valid JSON, fallback to simple regex replacement
            log.debug("Input is not valid JSON, applying regex masking");
        }

        return maskStringRegex(input);
    }

    private void maskJsonNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                JsonNode valueNode = field.getValue();

                if (isSensitiveKey(key)) {
                    objectNode.put(key, MASKED_VALUE);
                } else if (valueNode.isObject() || valueNode.isArray()) {
                    maskJsonNode(valueNode);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (JsonNode elementNode : arrayNode) {
                if (elementNode.isObject() || elementNode.isArray()) {
                    maskJsonNode(elementNode);
                }
            }
        }
    }

    private boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String lowerKey = key.toLowerCase().replace("_", "").replace("-", "");
        for (String sensitiveKey : SENSITIVE_KEYS) {
            if (lowerKey.contains(sensitiveKey.replace("-", "").replace("_", ""))) {
                return true;
            }
        }
        return false;
    }

    private String maskStringRegex(String input) {
        Matcher matcher = SENSITIVE_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String match = matcher.group(0);
            String replacement = match.replace(matcher.group(2), MASKED_VALUE);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
