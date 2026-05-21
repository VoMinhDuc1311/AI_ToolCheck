package com.aitoolcheck.ai_toolcheck1_backend.service.analysis;

import com.aitoolcheck.ai_toolcheck1_backend.enums.SourceStyle;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceAnalysisDecisionServiceTest {

    private SourceAnalysisDecisionService decisionService;

    @BeforeEach
    void setUp() {
        decisionService = new SourceAnalysisDecisionService(new SourceAnalysisScoringService());
    }

    // Test A — strong modern signals override low parseSuccessRate

    @Test
    void testA_strongModernSignals_classifiedAsModern_evenWithLowParseRate() {
        AnalysisSignals signals = buildSignalsFromSource(modernControllerSource());
        SourceStyle style = decisionService.determineSourceStyle(signals, 0.50, 70, 45);
        assertThat(style).isEqualTo(SourceStyle.MODERN);
    }

    @Test
    void testA_strongModernSignals_matchesExactObservedScores() {
        AnalysisSignals signals = buildSignalsFromSource(modernControllerSource());
        SourceStyle style = decisionService.determineSourceStyle(signals, 0.50, 55, 45);
        assertThat(style).isEqualTo(SourceStyle.MODERN);
    }

    @Test
    void testA_hasStrongModernSignals_returnsTrueForRestControllerPlusComposedMapping() {
        AnalysisSignals signals = buildSignalsFromSource(modernControllerSource());
        assertThat(signals.hasStrongModernSpringSignals()).isTrue();
        assertThat(signals.hasRestControllerAnnotation()).isTrue();
        assertThat(signals.hasComposedMappingAnnotation()).isTrue();
    }

    // Test B — @RequestMapping(method=...) → LEGACY

    @Test
    void testB_legacyRequestMappingMethod_classifiedAsLegacy() {
        AnalysisSignals signals = buildSignalsFromSource(legacyControllerWithRequestMappingMethodSource());
        SourceStyle style = decisionService.determineSourceStyle(signals, 0.80, 60, 55);
        assertThat(style).isEqualTo(SourceStyle.LEGACY);
    }

    @Test
    void testB_hasRequestMappingMethodAttribute_detected() {
        AnalysisSignals signals = buildSignalsFromSource(legacyControllerWithRequestMappingMethodSource());
        assertThat(signals.hasRequestMappingMethodAttribute()).isTrue();
        assertThat(signals.hasStrongLegacySignals()).isTrue();
        assertThat(signals.hasStrongModernSpringSignals()).isFalse();
    }

    // Test B2 — @Controller + @ResponseBody (no @RestController, no composed mapping) → LEGACY

    @Test
    void testB2_legacyControllerPlusResponseBody_classifiedAsLegacy() {
        AnalysisSignals signals = buildSignalsFromSource(legacyControllerWithResponseBodySource());
        SourceStyle style = decisionService.determineSourceStyle(signals, 0.80, 60, 55);
        assertThat(style).isEqualTo(SourceStyle.LEGACY);
    }

    @Test
    void testB2_controllerPlusResponseBody_hasStrongLegacySignals() {
        AnalysisSignals signals = buildSignalsFromSource(legacyControllerWithResponseBodySource());
        assertThat(signals.hasResponseBodyAnnotation()).isTrue();
        assertThat(signals.hasControllerAnnotation()).isTrue();
        assertThat(signals.hasRestControllerAnnotation()).isFalse();
        assertThat(signals.hasComposedMappingAnnotation()).isFalse();
        assertThat(signals.hasStrongLegacySignals()).isTrue();
    }

    @Test
    void testB2_controllerAloneWithoutResponseBody_doesNotTriggerLegacySignal() {
        // @Controller without @ResponseBody = view-rendering controller, not a legacy REST signal.
        AnalysisSignals signals = buildSignalsFromSource("""
                package com.example;
                import org.springframework.stereotype.Controller;
                @Controller
                public class ViewController {
                    public String home() { return "home"; }
                }
                """);
        assertThat(signals.hasStrongLegacySignals()).isFalse();
        assertThat(signals.hasStrongModernSpringSignals()).isFalse();
    }

    // Test C — strong modern + partial DTO failures → MODERN, aiRecommended=true

    @Test
    void testC_strongModernWithPartialDtoFailures_isModern() {
        AnalysisSignals signals = buildSignalsFromSource(modernControllerSource());
        SourceStyle style = decisionService.determineSourceStyle(signals, 0.50, 70, 45);
        assertThat(style).isEqualTo(SourceStyle.MODERN);
    }

    @Test
    void testC_strongModernWithPartialDtoFailures_aiStillRecommended() {
        AnalysisSignals signals = buildSignalsFromSource(modernControllerSource());
        SourceStyle style = decisionService.determineSourceStyle(signals, 0.50, 70, 45);
        boolean aiRecommended = decisionService.determineAiRecommended(style, 0.50, 70, 45, 2, 4);
        assertThat(aiRecommended).isTrue();
    }

    @Test
    void testC_strongModernWithPartialDtoFailures_parserNotRecommended() {
        boolean parserRecommended = decisionService.determineParserRecommended(0.50, 70, 45);
        assertThat(parserRecommended).isFalse();
    }

    // Test E — no strong signals + weak scores → LEGACY (score gate regression guard)

    @Test
    void testE_noStrongSignals_weakScores_remainLegacy() {
        AnalysisSignals signals = new AnalysisSignals();
        SourceStyle style = decisionService.determineSourceStyle(signals, 0.40, 20, 20);
        assertThat(style).isEqualTo(SourceStyle.LEGACY);
    }

    @Test
    void testE2_noStrongSignals_allScoresPassGate_classifiedModern() {
        AnalysisSignals signals = new AnalysisSignals();
        SourceStyle style = decisionService.determineSourceStyle(signals, 0.80, 60, 55);
        assertThat(style).isEqualTo(SourceStyle.MODERN);
    }

    // Helpers

    private AnalysisSignals buildSignalsFromSource(String javaSource) {
        AnalysisSignals signals = new AnalysisSignals();
        com.github.javaparser.ast.CompilationUnit cu = JavaParserSupport.parse(javaSource);
        signals.accept(cu.findAll(AnnotationExpr.class));
        return signals;
    }

    private static String modernControllerSource() {
        return """
                package com.e2e.modern.controller;
                import org.springframework.web.bind.annotation.*;
                import lombok.RequiredArgsConstructor;

                @RestController
                @RequestMapping("/api/modern/orders")
                @RequiredArgsConstructor
                public class ModernOrderController {

                    @GetMapping
                    public Object getAllOrders() { return null; }

                    @GetMapping("/{id}")
                    public Object getById() { return null; }

                    @PostMapping
                    public Object createOrder(@RequestBody Object req) { return null; }

                    @PatchMapping("/{id}/status")
                    public Object patchStatus() { return null; }
                }
                """;
    }

    private static String legacyControllerWithRequestMappingMethodSource() {
        return """
                package com.legacy.controller;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.ResponseBody;

                @Controller
                public class LegacyOrderController {

                    @RequestMapping(value = "/orders", method = RequestMethod.GET)
                    @ResponseBody
                    public String getOrders() { return "[]"; }

                    @RequestMapping(value = "/orders", method = RequestMethod.POST)
                    @ResponseBody
                    public String createOrder() { return "{}"; }
                }
                """;
    }

    private static String legacyControllerWithResponseBodySource() {
        return """
                package com.legacy.controller;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.ResponseBody;

                @Controller
                public class OldStyleController {

                    @RequestMapping("/items")
                    @ResponseBody
                    public String listItems() { return "[]"; }
                }
                """;
    }
}
