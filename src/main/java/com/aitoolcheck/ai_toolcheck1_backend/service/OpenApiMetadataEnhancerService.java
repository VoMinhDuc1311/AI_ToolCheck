package com.aitoolcheck.ai_toolcheck1_backend.service;

import com.aitoolcheck.ai_toolcheck1_backend.enums.HttpMethod;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiEndpoint;
import com.aitoolcheck.ai_toolcheck1_backend.model.ApiParameter;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class OpenApiMetadataEnhancerService {

    // -------------------------------------------------------------------------
    // Tag Inference
    // -------------------------------------------------------------------------

    public String inferTag(ApiEndpoint endpoint) {
        if (endpoint.getTagName() != null && !endpoint.getTagName().isBlank()) {
            String tag = endpoint.getTagName().trim();
            if (!"Default".equalsIgnoreCase(tag) && !"Servlet".equalsIgnoreCase(tag) && !"Struts".equalsIgnoreCase(tag)) {
                return tag;
            }
        }

        String controller = endpoint.getControllerName();
        if (controller != null && !controller.isBlank()) {
            String cleaned = cleanTechnicalSuffix(controller);
            if (!cleaned.isBlank()) {
                return pluralizeAndCapitalize(cleaned);
            }
        }

        String path = endpoint.getEndpointPath();
        if (path != null && !path.isBlank()) {
            String normalized = normalizePath(path);
            String[] segments = normalized.split("/");
            for (String segment : segments) {
                if (segment.isEmpty()) continue;
                String lower = segment.toLowerCase();
                if ("api".equals(lower) || "legacy".equals(lower) || "v1".equals(lower) || "v2".equals(lower) || "v3".equals(lower)) {
                    continue;
                }
                String cleaned = cleanTechnicalSuffix(segment);
                if (!cleaned.isBlank()) {
                    return pluralizeAndCapitalize(cleaned);
                }
            }
        }

        if (endpoint.getTagName() != null && !endpoint.getTagName().isBlank()) {
            return endpoint.getTagName().trim();
        }
        return "Default";
    }

    private String cleanTechnicalSuffix(String name) {
        if (name == null) return "";
        String cleaned = name.trim();
        String[] suffixes = {"Gateway", "Controller", "Servlet", "Action", "Resource"};
        boolean matched;
        do {
            matched = false;
            for (String suffix : suffixes) {
                if (cleaned.length() > suffix.length() && cleaned.toLowerCase().endsWith(suffix.toLowerCase())) {
                    cleaned = cleaned.substring(0, cleaned.length() - suffix.length()).trim();
                    matched = true;
                }
            }
        } while (matched);
        return cleaned;
    }

    private String pluralizeAndCapitalize(String name) {
        if (name == null || name.isBlank()) return "";
        String lower = name.toLowerCase();
        if ("inventory".equals(lower)) {
            return "Inventory";
        }
        if ("order".equals(lower)) {
            return "Orders";
        }
        if ("stocktake".equals(lower)) {
            return "Stocktakes";
        }

        String capitalized = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        if (lower.endsWith("s")) {
            return capitalized;
        }
        if (lower.endsWith("y")) {
            return capitalized.substring(0, capitalized.length() - 1) + "ies";
        }
        return capitalized + "s";
    }

    private String getResourceSingular(ApiEndpoint endpoint) {
        String tag = inferTag(endpoint);
        if ("Orders".equalsIgnoreCase(tag)) return "order";
        if ("Stocktakes".equalsIgnoreCase(tag)) return "stocktake";
        if ("Inventory".equalsIgnoreCase(tag)) return "inventory";

        if (tag.endsWith("ies")) {
            return tag.substring(0, tag.length() - 3) + "y";
        }
        if (tag.endsWith("s") && !tag.endsWith("ss")) {
            return tag.substring(0, tag.length() - 1).toLowerCase();
        }
        return tag.toLowerCase();
    }

    private String getResourcePlural(ApiEndpoint endpoint) {
        String tag = inferTag(endpoint);
        return tag.toLowerCase();
    }

    // -------------------------------------------------------------------------
    // Summary Inference
    // -------------------------------------------------------------------------

    public String inferSummary(ApiEndpoint endpoint, List<ApiParameter> params) {
        if (endpoint.getAiSummary() != null && !endpoint.getAiSummary().isBlank()) {
            String aiSummary = endpoint.getAiSummary().trim();
            if (!isGenericMethodName(aiSummary)) {
                return aiSummary;
            }
        }
        if (endpoint.getDescription() != null && !endpoint.getDescription().isBlank()) {
            String desc = endpoint.getDescription().trim();
            if (desc.length() <= 60 && !isGenericMethodName(desc)) {
                return desc;
            }
        }

        HttpMethod method = endpoint.getHttpMethod() == null ? HttpMethod.GET : endpoint.getHttpMethod();
        String path = endpoint.getEndpointPath();
        String lastSegment = lastPathSegment(path);

        String actionVal = null;
        if (params != null) {
            for (ApiParameter p : params) {
                if ("action".equalsIgnoreCase(p.getParamName())) {
                    actionVal = p.getExampleValue();
                    break;
                }
            }
        }

        if (method == HttpMethod.GET) {
            if (path != null && path.toLowerCase().endsWith("/detail")) {
                return "Get " + getResourceSingular(endpoint) + " detail";
            }
            boolean hasIdParam = false;
            if (params != null) {
                for (ApiParameter p : params) {
                    String pName = p.getParamName();
                    if (pName != null && (pName.equalsIgnoreCase("id") || (pName.toLowerCase().endsWith("id") && !pName.equalsIgnoreCase("tenantid")))) {
                        hasIdParam = true;
                        break;
                    }
                }
            }
            if (hasIdParam || (path != null && path.contains("{"))) {
                return "Get " + getResourceSingular(endpoint);
            }
            return "List " + getResourcePlural(endpoint);
        }

        if (method == HttpMethod.POST) {
            Set<String> actionVerbs = Set.of("open", "create", "cancel", "approve", "close", "submit", "add", "delete", "remove", "update", "edit");
            if (actionVal != null && !actionVal.isBlank()) {
                return capitalize(actionVal.trim()) + " " + getResourceSingular(endpoint);
            } else if (lastSegment != null && actionVerbs.contains(lastSegment.toLowerCase())) {
                return capitalize(lastSegment.trim()) + " " + getResourceSingular(endpoint);
            }

            String controller = endpoint.getControllerName();
            boolean isGenericAction = (controller != null && (controller.toLowerCase().contains("action") || controller.toLowerCase().contains("servlet")))
                    || (path != null && path.toLowerCase().contains("action"));
            if (isGenericAction) {
                return "Submit " + getResourceSingular(endpoint) + " action";
            }
            return "Create " + getResourceSingular(endpoint);
        }

        if (method == HttpMethod.PUT || method == HttpMethod.PATCH) {
            return "Update " + getResourceSingular(endpoint);
        }

        if (method == HttpMethod.DELETE) {
            return "Delete " + getResourceSingular(endpoint);
        }

        return "Execute operation";
    }

    private boolean isGenericMethodName(String text) {
        String lower = text.toLowerCase();
        return "doget".equals(lower) || "dopost".equals(lower) || "doput".equals(lower) || "dodelete".equals(lower)
                || "service".equals(lower) || "execute".equals(lower);
    }

    private String lastPathSegment(String path) {
        if (path == null || path.isBlank()) return "";
        String normalized = normalizePath(path);
        int lastSlash = normalized.lastIndexOf('/');
        return lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
    }

    private String capitalize(String text) {
        if (text == null || text.isBlank()) return "";
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // -------------------------------------------------------------------------
    // Description Inference
    // -------------------------------------------------------------------------

    public String inferDescription(ApiEndpoint endpoint, String summary) {
        if (endpoint.getAiDescription() != null && !endpoint.getAiDescription().isBlank()) {
            String aiDesc = endpoint.getAiDescription().trim();
            if (isMeaningfulDescription(aiDesc)) {
                return aiDesc;
            }
        }
        if (endpoint.getDescription() != null && !endpoint.getDescription().isBlank()) {
            String desc = endpoint.getDescription().trim();
            if (isMeaningfulDescription(desc)) {
                return desc;
            }
        }

        if (summary != null) {
            if ("List orders".equalsIgnoreCase(summary)) return "Retrieves orders by tenant ID.";
            if ("Create order".equalsIgnoreCase(summary)) return "Creates a new order.";
            if ("Get order detail".equalsIgnoreCase(summary)) return "Retrieves order detail by order ID.";
            if ("List stocktakes".equalsIgnoreCase(summary)) return "Find all stocktakes for a given tenant.";
            if ("Open stocktake".equalsIgnoreCase(summary)) return "Opens a new stocktake.";
            if ("Get stocktake detail".equalsIgnoreCase(summary)) return "Find detail for a specific stocktake ticket.";

            if (summary.startsWith("List ")) {
                return "Retrieves list of " + summary.substring(5).toLowerCase() + ".";
            }
            if (summary.startsWith("Get ") && summary.endsWith(" detail")) {
                return "Retrieves " + summary.substring(4).toLowerCase() + ".";
            }
            if (summary.startsWith("Get ")) {
                return "Retrieves " + summary.substring(4).toLowerCase() + ".";
            }
            if (summary.startsWith("Create ")) {
                return "Creates a new " + summary.substring(7).toLowerCase() + ".";
            }
            if (summary.startsWith("Open ")) {
                return "Opens a new " + summary.substring(5).toLowerCase() + ".";
            }
            if (summary.startsWith("Update ")) {
                return "Updates an existing " + summary.substring(7).toLowerCase() + ".";
            }
            if (summary.startsWith("Delete ")) {
                return "Deletes an existing " + summary.substring(7).toLowerCase() + ".";
            }
        }
        return "No description available.";
    }

    private static final Set<String> JUNK_DESCRIPTIONS = Set.of(
            "n/a", "na", "-", "--", "todo", "tbd", "placeholder"
    );

    private boolean isMeaningfulDescription(String desc) {
        if (desc == null || desc.isBlank()) return false;
        if (desc.length() < 4) return false;
        if (desc.length() > 500) return false;
        String lower = desc.trim().toLowerCase();
        if (JUNK_DESCRIPTIONS.contains(lower)) return false;
        if (lower.contains("rule-based fallback")) return false;
        if (lower.contains("inferred from legacy java")) return false;
        return true;
    }

    // -------------------------------------------------------------------------
    // OperationId Inference
    // -------------------------------------------------------------------------

    public String inferOperationId(ApiEndpoint endpoint, List<ApiParameter> params, Set<String> usedOperationIds) {
        String base = null;
        if (endpoint.getOperationId() != null && !endpoint.getOperationId().isBlank()) {
            String opId = endpoint.getOperationId().trim();
            if (!isGenericMethodName(opId)) {
                base = opId;
            }
        }

        if (base == null) {
            String summary = inferSummary(endpoint, params);
            base = toLowerCamelCase(summary);
        }

        // Cleanup invalid characters
        base = base.replaceAll("[^a-zA-Z0-9]", "");
        if (base.isEmpty()) {
            base = "operation";
        }

        if (usedOperationIds.add(base)) {
            return base;
        }

        // Collision handling: append method
        String method = endpoint.getHttpMethod() == null ? "GET" : endpoint.getHttpMethod().name();
        String candidate = base + capitalize(method.toLowerCase());
        if (usedOperationIds.add(candidate)) {
            return candidate;
        }

        // Last resort: append numeric suffix — no underscore to preserve lowerCamelCase contract
        int suffix = 2;
        String finalCandidate;
        do {
            finalCandidate = base + suffix++;
        } while (!usedOperationIds.add(finalCandidate));

        return finalCandidate;
    }

    private String toLowerCamelCase(String text) {
        if (text == null || text.isBlank()) return "operation";
        String[] words = text.replaceAll("[^a-zA-Z0-9 ]", "").split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (w.isEmpty()) continue;
            if (i == 0) {
                sb.append(w.toLowerCase());
            } else {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Parameter Description Inference
    // -------------------------------------------------------------------------

    public String enrichParameterDescription(ApiParameter param) {
        String name = param.getParamName();
        if (name == null || name.isBlank()) {
            return "";
        }

        String nameLower = name.trim().toLowerCase();
        switch (nameLower) {
            case "tenantid":
                return "Tenant identifier used to filter records.";
            case "orderid":
                return "Order identifier.";
            case "ticketid":
                return "Ticket identifier.";
            case "warehousecode":
                return "Warehouse code.";
            case "action":
                return "Legacy action command.";
            case "id":
                return "Resource identifier.";
            case "page":
                return "Page number.";
            case "size":
                return "Page size.";
            case "sort":
                return "Sort expression.";
            case "status":
                return "Status filter.";
            case "code":
                return "Business code.";
        }

        return convertCamelCaseToPhrase(name) + ".";
    }

    private String convertCamelCaseToPhrase(String name) {
        if (name == null || name.isBlank()) return "";
        String[] parts = name.split("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) continue;
            if (i == 0) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase());
            } else {
                sb.append(" ").append(part.toLowerCase());
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Response Descriptions
    // -------------------------------------------------------------------------

    public Map<String, String> getResponseDescriptions(ApiEndpoint endpoint, String summary) {
        Map<String, String> descMap = new LinkedHashMap<>();

        // Resolve 200 description
        String desc200 = "OK";
        if (summary != null) {
            if (summary.startsWith("List ")) {
                desc200 = "Successful retrieval of resource list.";
            } else if (summary.startsWith("Get ") && summary.endsWith(" detail")) {
                desc200 = "Successful retrieval of resource detail.";
            } else if (summary.startsWith("Get ")) {
                desc200 = "Successful retrieval of resource detail.";
            } else if (summary.startsWith("Create ") || summary.startsWith("Open ") || summary.startsWith("Submit ")) {
                desc200 = "Operation completed successfully.";
            } else if (summary.startsWith("Update ")) {
                desc200 = "Resource updated successfully.";
            } else if (summary.startsWith("Delete ")) {
                desc200 = "Resource deleted successfully.";
            }
        }
        descMap.put("200", desc200);

        // Resolve other status codes based on endpoint
        HttpMethod method = endpoint.getHttpMethod();
        String path = endpoint.getEndpointPath();
        boolean isDetail = path != null && path.toLowerCase().contains("/detail");

        if (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH) {
            descMap.put("400", "Invalid request.");
        }

        if (isDetail || (path != null && path.contains("{"))) {
            descMap.put("404", "Resource not found.");
        }

        descMap.put("500", "Internal server error.");

        return descMap;
    }

    // -------------------------------------------------------------------------
    // Path normalization helper
    // -------------------------------------------------------------------------

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String normalized = path.trim().replace("\\", "/").replaceAll("/+", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
