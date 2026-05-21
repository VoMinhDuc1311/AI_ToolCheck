package com.aitoolcheck.ai_toolcheck1_backend.service.analysis;

import com.aitoolcheck.ai_toolcheck1_backend.enums.FileType;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JavaSourceAnalyzerTest {

    private JavaSourceAnalyzer analyzer;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        analyzer = new JavaSourceAnalyzer(new SourceFileClassificationService());
        projectId = UUID.randomUUID();
    }

    // Test A — Modern controller + Java record DTOs: all 4 files parse successfully

    @Test
    void testA_modernControllerWithRecordDtos_allFilesParsedSuccessfully() {
        List<SourceFile> files = List.of(
                buildFile("ModernSampleApplication.java",
                        "src/main/java/com/e2e/modern/ModernSampleApplication.java",
                        modernApplicationSource()),
                buildFile("ModernOrderController.java",
                        "src/main/java/com/e2e/modern/controller/ModernOrderController.java",
                        modernControllerSource()),
                buildFile("CreateOrderRequest.java",
                        "src/main/java/com/e2e/modern/dto/CreateOrderRequest.java",
                        createOrderRequestRecordSource()),
                buildFile("OrderResponse.java",
                        "src/main/java/com/e2e/modern/dto/OrderResponse.java",
                        orderResponseRecordSource())
        );

        AnalysisSignals signals = analyzer.analyze(projectId, files);

        assertThat(signals.parsedSuccessFiles()).isEqualTo(4);
        assertThat(signals.parsedFailedFiles()).isEqualTo(0);
    }

    @Test
    void testA_modernControllerWithRecordDtos_strongModernSignalsDetected() {
        List<SourceFile> files = List.of(
                buildFile("ModernOrderController.java",
                        "src/main/java/com/e2e/modern/controller/ModernOrderController.java",
                        modernControllerSource()),
                buildFile("CreateOrderRequest.java",
                        "src/main/java/com/e2e/modern/dto/CreateOrderRequest.java",
                        createOrderRequestRecordSource()),
                buildFile("OrderResponse.java",
                        "src/main/java/com/e2e/modern/dto/OrderResponse.java",
                        orderResponseRecordSource())
        );

        AnalysisSignals signals = analyzer.analyze(projectId, files);

        assertThat(signals.hasRestControllerAnnotation()).isTrue();
        assertThat(signals.hasComposedMappingAnnotation()).isTrue();
        assertThat(signals.hasStrongModernSpringSignals()).isTrue();
    }

    @Test
    void testA_modernControllerWithRecordDtos_controllerFileClassifiedCorrectly() {
        SourceFile controllerFile = buildFile("ModernOrderController.java",
                "src/main/java/com/e2e/modern/controller/ModernOrderController.java",
                modernControllerSource());

        analyzer.analyze(projectId, List.of(controllerFile));

        assertThat(controllerFile.getParsedFlag()).isTrue();
        assertThat(controllerFile.getFileType()).isEqualTo(FileType.CONTROLLER);
        assertThat(controllerFile.getParseError()).isNull();
    }

    // Test B — Legacy @RequestMapping(method=...) → hasStrongLegacySignals=true

    @Test
    void testB_legacyRequestMappingMethod_hasStrongLegacySignals() {
        SourceFile legacyController = buildFile("OrderController.java",
                "src/main/java/com/legacy/controller/OrderController.java",
                legacyControllerSource());

        AnalysisSignals signals = analyzer.analyze(projectId, List.of(legacyController));

        assertThat(signals.hasRestControllerAnnotation()).isFalse();
        assertThat(signals.hasStrongModernSpringSignals()).isFalse();
        assertThat(signals.hasControllerAnnotation()).isTrue();
        assertThat(signals.hasRequestMappingMethodAttribute()).isTrue();
        assertThat(signals.hasStrongLegacySignals()).isTrue();
    }

    // Test C — Modern controller + one broken DTO: strong modern signals still detected

    @Test
    void testC_modernControllerWithOneBrokenDto_controllerStillParsed() {
        SourceFile controller = buildFile("ModernOrderController.java",
                "src/main/java/com/e2e/modern/controller/ModernOrderController.java",
                modernControllerSource());
        SourceFile brokenDto = buildFile("BrokenDto.java",
                "src/main/java/com/e2e/modern/dto/BrokenDto.java",
                "this is not valid java @@@@@");

        AnalysisSignals signals = analyzer.analyze(projectId, List.of(controller, brokenDto));

        assertThat(signals.parsedSuccessFiles()).isEqualTo(1);
        assertThat(signals.parsedFailedFiles()).isEqualTo(1);
        assertThat(signals.hasStrongModernSpringSignals()).isTrue();
        assertThat(controller.getParsedFlag()).isTrue();
        assertThat(brokenDto.getParsedFlag()).isFalse();
        assertThat(brokenDto.getParseError()).isNotBlank();
    }

    // Source fixtures

    private static String modernApplicationSource() {
        return """
                package com.e2e.modern;
                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class ModernSampleApplication {
                    public static void main(String[] args) {
                        SpringApplication.run(ModernSampleApplication.class, args);
                    }
                }
                """;
    }

    private static String modernControllerSource() {
        return """
                package com.e2e.modern.controller;
                import com.e2e.modern.dto.CreateOrderRequest;
                import com.e2e.modern.dto.OrderResponse;
                import lombok.RequiredArgsConstructor;
                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.*;
                import java.util.List;
                import java.util.UUID;

                @RestController
                @RequestMapping("/api/modern/orders")
                @RequiredArgsConstructor
                public class ModernOrderController {

                    @GetMapping
                    public ResponseEntity<List<OrderResponse>> getAllOrders() {
                        return ResponseEntity.ok(List.of());
                    }

                    @GetMapping("/{id}")
                    public ResponseEntity<OrderResponse> getOrderById(@PathVariable UUID id) {
                        return ResponseEntity.ok(null);
                    }

                    @PostMapping
                    public ResponseEntity<OrderResponse> createOrder(@RequestBody CreateOrderRequest request) {
                        return ResponseEntity.ok(null);
                    }

                    @PatchMapping("/{id}/status")
                    public ResponseEntity<OrderResponse> patchOrderStatus(
                            @PathVariable UUID id,
                            @RequestParam String status) {
                        return ResponseEntity.ok(null);
                    }
                }
                """;
    }

    private static String createOrderRequestRecordSource() {
        return """
                package com.e2e.modern.dto;
                public record CreateOrderRequest(String productName, int quantity) {}
                """;
    }

    private static String orderResponseRecordSource() {
        return """
                package com.e2e.modern.dto;
                import java.util.UUID;
                public record OrderResponse(UUID id, String productName, int quantity, String status) {}
                """;
    }

    private static String legacyControllerSource() {
        return """
                package com.legacy.controller;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.ResponseBody;

                @Controller
                public class OrderController {

                    @RequestMapping(value = "/orders", method = RequestMethod.GET)
                    @ResponseBody
                    public String getOrders() { return "[]"; }

                    @RequestMapping(value = "/orders", method = RequestMethod.POST)
                    @ResponseBody
                    public String createOrder() { return "{}"; }
                }
                """;
    }

    private SourceFile buildFile(String fileName, String filePath, String content) {
        SourceProject project = new SourceProject();
        return SourceFile.builder()
                .id(UUID.randomUUID())
                .fileName(fileName)
                .filePath(filePath)
                .sourceContent(content)
                .fileType(FileType.UNKNOWN)
                .parsedFlag(Boolean.FALSE)
                .sourceProject(project)
                .build();
    }
}
