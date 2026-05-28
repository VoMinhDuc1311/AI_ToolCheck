package com.aitoolcheck.ai_toolcheck1_backend.service.legacy;

import com.aitoolcheck.ai_toolcheck1_backend.dto.aiskill.res.AiInferenceResultDto;
import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyRuleBasedEndpointExtractorServiceTest {

    private LegacyRuleBasedEndpointExtractorService extractor;

    @BeforeEach
    void setUp() {
        extractor = new LegacyRuleBasedEndpointExtractorService();
    }

    @Test
    void servletOrderGateway_extractsGetAndPostClassPath() {
        AiInferenceResultDto result = extractor.extract(sourceFile("OrderGateway.java",
                "public class OrderGateway extends HttpServlet {\n"
                        + "  protected void doGet(HttpServletRequest request, HttpServletResponse response) {}\n"
                        + "  protected void doPost(HttpServletRequest request, HttpServletResponse response) {}\n"
                        + "}"));

        assertEndpoints(result, Set.of("GET /OrderGateway", "POST /OrderGateway"));
    }

    @Test
    void servletStocktakeGateway_extractsGetAndPostClassPath() {
        AiInferenceResultDto result = extractor.extract(sourceFile("StocktakeGateway.java",
                "public class StocktakeGateway extends HttpServlet {\n"
                        + "  public void doGet(HttpServletRequest request, HttpServletResponse response) {}\n"
                        + "  public void doPost(HttpServletRequest request, HttpServletResponse response) {}\n"
                        + "}"));

        assertEndpoints(result, Set.of("GET /StocktakeGateway", "POST /StocktakeGateway"));
    }

    @Test
    void strutsActionWithExecute_extractsNormalizedLegacyRoute() {
        AiInferenceResultDto result = extractor.extract(sourceFile("InventoryAction.java",
                "public class InventoryAction {\n"
                        + "  public ActionForward execute(ActionMapping mapping, HttpServletRequest request, HttpServletResponse response) {\n"
                        + "    return mapping.findForward(\"success\");\n"
                        + "  }\n"
                        + "}"));

        assertEndpoints(result, Set.of("POST /legacy/inventory"));
    }

    @Test
    void dbBridgeHelper_returnsNoEndpoints() {
        AiInferenceResultDto result = extractor.extract(sourceFile("LegacyDbBridge.java",
                "public class LegacyDbBridge { public Connection connect() { return null; } }"));

        assertTrue(result.getEndpoints().isEmpty());
    }

    @Test
    void responseWriterHelper_returnsNoEndpoints() {
        AiInferenceResultDto result = extractor.extract(sourceFile("ResponseWriter.java",
                "public class ResponseWriter { public void write(HttpServletResponse response) {} }"));

        assertTrue(result.getEndpoints().isEmpty());
    }

    private SourceFile sourceFile(String fileName, String source) {
        SourceFile sourceFile = new SourceFile();
        sourceFile.setFileName(fileName);
        sourceFile.setFilePath("/src/main/java/com/acme/" + fileName);
        sourceFile.setSourceContent(source);
        return sourceFile;
    }

    private void assertEndpoints(AiInferenceResultDto result, Set<String> expected) {
        Set<String> actual = result.getEndpoints().stream()
                .map(endpoint -> endpoint.getHttpMethod() + " " + endpoint.getPath())
                .collect(Collectors.toSet());
        assertEquals(expected, actual);
    }
}
