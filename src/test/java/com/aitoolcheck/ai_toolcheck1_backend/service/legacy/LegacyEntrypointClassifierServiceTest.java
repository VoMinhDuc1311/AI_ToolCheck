package com.aitoolcheck.ai_toolcheck1_backend.service.legacy;

import com.aitoolcheck.ai_toolcheck1_backend.model.SourceFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LegacyEntrypointClassifierServiceTest {

    private LegacyEntrypointClassifierService classifier;

    @BeforeEach
    void setUp() {
        // This will test the static initializer implicitly
        classifier = new LegacyEntrypointClassifierService();
    }

    @Test
    void testOrderGateway_isCandidate() {
        SourceFile file = new SourceFile();
        file.setId(UUID.randomUUID());
        file.setFileName("OrderGateway.java");
        file.setFilePath("/src/main/java/com/app/gateway/OrderGateway.java");
        file.setSourceContent("package com.app.gateway;\n\npublic class OrderGateway {\n    public void process(HttpServletRequest request) {\n        // ...\n    }\n}");

        LegacyEntrypointCandidateResult result = classifier.classify(file);
        
        assertTrue(result.isCandidate(), "OrderGateway.java should be a candidate");
    }

    @Test
    void testStocktakeGateway_isCandidate() {
        SourceFile file = new SourceFile();
        file.setId(UUID.randomUUID());
        file.setFileName("StocktakeGateway.java");
        file.setFilePath("/src/main/java/com/app/gateway/StocktakeGateway.java");
        file.setSourceContent("package com.app.gateway;\n\npublic class StocktakeGateway {\n    public void update(HttpServletRequest request) {\n        // ...\n    }\n}");

        LegacyEntrypointCandidateResult result = classifier.classify(file);
        
        assertTrue(result.isCandidate(), "StocktakeGateway.java should be a candidate");
    }

    @Test
    void testInventoryAction_withExecute_isCandidate() {
        SourceFile file = new SourceFile();
        file.setId(UUID.randomUUID());
        file.setFileName("InventoryAction.java");
        file.setFilePath("/src/main/java/com/app/action/InventoryAction.java");
        file.setSourceContent("package com.app.action;\n\nimport org.apache.struts.action.ActionMapping;\n\npublic class InventoryAction {\n    public ActionForward execute(ActionMapping mapping, HttpServletRequest request, HttpServletResponse response) {\n        return mapping.findForward(\"success\");\n    }\n}");

        LegacyEntrypointCandidateResult result = classifier.classify(file);
        
        assertTrue(result.isCandidate(), "InventoryAction.java with execute() should be a candidate");
        assertEquals("STRUTS_ACTION", result.getCandidateType());
    }

    @Test
    void testLegacyDbBridge_isNotCandidate() {
        SourceFile file = new SourceFile();
        file.setId(UUID.randomUUID());
        file.setFileName("LegacyDbBridge.java");
        file.setFilePath("/src/main/java/com/app/db/LegacyDbBridge.java");
        file.setSourceContent("package com.app.db;\n\nimport java.sql.Connection;\nimport java.sql.PreparedStatement;\n\npublic class LegacyDbBridge {\n    public void connect() {\n        // JDBC stuff\n    }\n}");

        LegacyEntrypointCandidateResult result = classifier.classify(file);
        
        assertFalse(result.isCandidate(), "LegacyDbBridge.java should not be a candidate");
        assertEquals("HELPER", result.getCandidateType());
    }

    @Test
    void testResponseWriter_isNotCandidate() {
        SourceFile file = new SourceFile();
        file.setId(UUID.randomUUID());
        file.setFileName("ResponseWriter.java");
        file.setFilePath("/src/main/java/com/app/util/ResponseWriter.java");
        file.setSourceContent("package com.app.util;\n\npublic class ResponseWriter {\n    public void write() {\n        // util\n    }\n}");

        LegacyEntrypointCandidateResult result = classifier.classify(file);
        
        assertFalse(result.isCandidate(), "ResponseWriter.java should not be a candidate");
        assertEquals("HELPER", result.getCandidateType());
    }
}
