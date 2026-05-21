package com.aitoolcheck.ai_toolcheck1_backend.service.analysis;

import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class JavaParserSupportTest {

    @Test
    void parseStandardClass_succeeds() {
        String source = """
                package com.example;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class SampleController {
                    public String hello() { return "hello"; }
                }
                """;
        assertThatCode(() -> JavaParserSupport.parse(source)).doesNotThrowAnyException();
    }

    @Test
    void parseJavaRecord_succeeds() {
        // Java records require JAVA_17 language level — this is the core regression test.
        String source = """
                package com.example.dto;
                public record CreateOrderRequest(String productName, int quantity) {}
                """;
        assertThatCode(() -> JavaParserSupport.parse(source)).doesNotThrowAnyException();
    }

    @Test
    void parseJavaRecord_hasCorrectClassName() {
        String source = """
                package com.example.dto;
                public record OrderResponse(java.util.UUID id, String productName, int quantity, String status) {}
                """;
        CompilationUnit cu = JavaParserSupport.parse(source);
        assertThat(cu.getTypes()).isNotEmpty();
        assertThat(cu.getTypes().get(0).getNameAsString()).isEqualTo("OrderResponse");
    }

    @Test
    void parseInvalidSource_throwsException() {
        String source = "this is not valid java @@@@";
        assertThatCode(() -> JavaParserSupport.parse(source))
                .isInstanceOf(com.github.javaparser.ParseProblemException.class);
    }
}
