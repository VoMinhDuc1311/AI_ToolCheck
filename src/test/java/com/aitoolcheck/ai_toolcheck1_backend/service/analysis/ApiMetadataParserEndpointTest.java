package com.aitoolcheck.ai_toolcheck1_backend.service.analysis;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Regression: ApiMetadataParserServiceImpl must still find 4 endpoints from ModernOrderController.
// Pure unit test — no Spring context or DB required.
class ApiMetadataParserEndpointTest {

    private static final String MODERN_CONTROLLER_SOURCE = """
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

    @Test
    void testD_modernController_parsesSuccessfully() {
        CompilationUnit cu = JavaParserSupport.parse(MODERN_CONTROLLER_SOURCE);
        assertThat(cu).isNotNull();
    }

    @Test
    void testD_modernController_findsRestControllerClass() {
        CompilationUnit cu = JavaParserSupport.parse(MODERN_CONTROLLER_SOURCE);

        List<ClassOrInterfaceDeclaration> controllers = cu.findAll(ClassOrInterfaceDeclaration.class)
                .stream()
                .filter(c -> c.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals("RestController")))
                .toList();

        assertThat(controllers).hasSize(1);
        assertThat(controllers.get(0).getNameAsString()).isEqualTo("ModernOrderController");
    }

    @Test
    void testD_modernController_finds4EndpointMethods() {
        CompilationUnit cu = JavaParserSupport.parse(MODERN_CONTROLLER_SOURCE);
        ClassOrInterfaceDeclaration controller = cu.findAll(ClassOrInterfaceDeclaration.class).get(0);

        List<MethodDeclaration> endpointMethods = controller.getMethods().stream()
                .filter(m -> m.getAnnotations().stream().anyMatch(a -> {
                    String name = a.getNameAsString();
                    return name.equals("GetMapping") || name.equals("PostMapping")
                            || name.equals("PutMapping") || name.equals("PatchMapping")
                            || name.equals("DeleteMapping") || name.equals("RequestMapping");
                }))
                .toList();

        assertThat(endpointMethods).hasSize(4);
    }

    @Test
    void testD_modernController_findsCorrectMethodNames() {
        CompilationUnit cu = JavaParserSupport.parse(MODERN_CONTROLLER_SOURCE);
        ClassOrInterfaceDeclaration controller = cu.findAll(ClassOrInterfaceDeclaration.class).get(0);

        List<String> methodNames = controller.getMethods().stream()
                .filter(m -> m.getAnnotations().stream().anyMatch(a -> {
                    String name = a.getNameAsString();
                    return name.equals("GetMapping") || name.equals("PostMapping")
                            || name.equals("PatchMapping") || name.equals("DeleteMapping")
                            || name.equals("PutMapping");
                }))
                .map(MethodDeclaration::getNameAsString)
                .toList();

        assertThat(methodNames).containsExactlyInAnyOrder(
                "getAllOrders", "getOrderById", "createOrder", "patchOrderStatus"
        );
    }
}
