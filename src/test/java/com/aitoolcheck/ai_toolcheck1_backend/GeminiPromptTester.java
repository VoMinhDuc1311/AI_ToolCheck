package com.aitoolcheck.ai_toolcheck1_backend;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;

public class GeminiPromptTester {

  // TODO: Thay bằng API Key thật của bạn
  private static final String GEMINI_API_KEY = "AIzaSyDK9udrfHO-DdWwtILOyEqJQhKl7kj1-as";

  private static final String MODEL_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:generateContent?key=" + GEMINI_API_KEY;
  // Master System Prompt (Đã cập nhật Rule chi tiết và nhúng sẵn JSON Schema)
  private static final String SYSTEM_PROMPT = """
        You are an Expert System Architect specializing in modernizing legacy Java applications. 
        Your task is to analyze raw legacy Java source code and extract all HTTP API endpoints into a strict, deterministic JSON format.
        
        CRITICAL RULES:
        1. LEGACY CONTEXT: The code lacks modern annotations (no @RestController). You MUST infer endpoints from:
           - HttpServlet methods: doGet, doPost, doPut, doDelete.
           - Custom routing: Analysis of if/else or switch blocks checking request.getRequestURI().
           - Action dispatchers: Parameters like request.getParameter("action") that route to different logic.
        2. STRICT PATHING: NEVER include query strings (e.g., ?action=val) in the "path" field. Extract them into the "parameters" array with in: "QUERY".
        3. PARAMETER MAPPING: 
           - Map request.getParameter() to "QUERY" for GET and "BODY" for POST (form-urlencoded).
           - Detect request.getHeader() as "HEADER".
        4. SOURCE TRACING: You must include the className and the specific methodName where the logic is handled.
        5. CONFIDENCE SCORING: Provide a score (0.0 to 1.0) based on how explicit the routing is.
        6. DETERMINISTIC OUTPUT: Return ONLY valid JSON matching the schema. NO markdown, NO text explanations.
        7. NON-API FILES: If no HTTP logic is found (e.g., a pure Utility or Entity), return {"endpoints": []}.
        
        OUTPUT SCHEMA: You must strictly adhere to the following JSON schema:
        {
          "type": "object",
          "properties": {
            "endpoints": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "source": { "type": "object", "properties": { "className": { "type": "string" }, "methodName": { "type": "string" } }, "required": ["className", "methodName"] },
                  "path": { "type": "string", "description": "Clean URL path" },
                  "httpMethod": { "type": "string", "enum": ["GET", "POST", "PUT", "DELETE", "PATCH"] },
                  "description": { "type": "string" },
                  "parameters": { "type": "array", "items": { "type": "object", "properties": { "name": { "type": "string" }, "in": { "type": "string", "enum": ["QUERY", "HEADER", "PATH", "BODY"] }, "type": { "type": "string" }, "required": { "type": "boolean" }, "example": { "type": "string" } }, "required": ["name", "in", "type", "required"] } },
                  "responses": { "type": "array", "items": { "type": "object", "properties": { "statusCode": { "type": "number" }, "contentType": { "type": "string" } }, "required": ["statusCode", "contentType"] } },
                  "confidence": { "type": "number", "maximum": 1 }
                },
                "required": ["source", "path", "httpMethod", "parameters", "responses", "confidence"]
              }
            }
          },
          "required": ["endpoints"]
        }
        """;

  public static void main(String[] args) {
    // Danh sách 4 file cần test
    String[] testFiles = {
             "LegacyUserServlet.java",
             "ProductDispatcher.java",
            "ReportManager.java" ,
             "StringFormatUtil.java"
    };

    HttpClient client = HttpClient.newHttpClient();

    for (String fileName : testFiles) {
      System.out.println("\n=======================================================");
      System.out.println("ĐANG PHÂN TÍCH FILE: " + fileName);
      System.out.println("=======================================================");

      try {
        // 1. Đọc nội dung file
        String filePath = "src/test/resources/legacy_samples/" + fileName;
        String sourceCode = new String(Files.readAllBytes(Paths.get(filePath)));

        // 2. Xử lý escape ký tự đặc biệt cực kỳ chặt chẽ (Tránh lỗi 400 Bad Request)
        String safeSourceCode = escapeForJson(sourceCode);
        String safePrompt = escapeForJson(SYSTEM_PROMPT);

        // 3. Build JSON Payload ép kiểu trả về JSON Mode (Không markdown)
        String jsonPayload = """
                    {
                      "system_instruction": {
                        "parts": { "text": "%s" }
                      },
                      "contents": [{
                        "parts":[{ "text": "Analyze the following Java code:\\n\\n%s" }]
                      }],
                      "generationConfig": {
                        "responseMimeType": "application/json"
                      }
                    }
                    """.formatted(safePrompt, safeSourceCode);

        // 4. Gửi Request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MODEL_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // 5. In kết quả thuần JSON ra console
        System.out.println(response.body());

        // Tạm dừng 3 giây giữa các lần gọi để tránh bị Rate Limit (429) của bản Free
        Thread.sleep(25000);

      } catch (Exception e) {
        System.err.println("Lỗi khi xử lý file " + fileName + ": " + e.getMessage());
      }
    }
  }

  /**
   * Hàm helper để escape các ký tự đặc biệt, đảm bảo chuỗi không làm vỡ cấu trúc JSON
   */
  private static String escapeForJson(String input) {
    if (input == null) return "";
    return input.replace("\\", "\\\\")   // Escape backslash
            .replace("\"", "\\\"")   // Escape ngoặc kép
            .replace("\n", "\\n")    // Escape xuống dòng
            .replace("\r", "");      // Xóa ký tự return
  }
}