package scratch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class TestJackson {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        
        // Scenario 1: Literal unescaped quotes inside string
        // "{"id": 1}"
        String json1 = "{ \"example\": \"{\"id\": 1}\" }";
        try {
            mapper.readTree(json1);
            System.out.println("json1 success");
        } catch (Exception e) {
            System.out.println("json1 failed: " + e.getMessage());
        }
        
        // Scenario 2: Literal single backslash before quote
        // "{\"id\": 1}"
        String json2 = "{ \"example\": \"{\\\"id\\\": 1}\" }";
        try {
            mapper.readTree(json2);
            System.out.println("json2 success");
        } catch (Exception e) {
            System.out.println("json2 failed: " + e.getMessage());
        }

        // Scenario 3: Literal double backslash before quote
        // "{\\"id\\": 1}"
        String json3 = "{ \"example\": \"{\\\\\"id\\\\\": 1}\" }";
        try {
            mapper.readTree(json3);
            System.out.println("json3 success");
        } catch (Exception e) {
            System.out.println("json3 failed: " + e.getMessage());
        }
    }
}
