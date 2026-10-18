package com.costonomy.mp.experiment;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.GlobalExceptionHandler;
import com.costonomy.mp.experiment.domain.EvaluationReason;
import com.costonomy.mp.experiment.domain.ExperimentStatus;
import com.costonomy.mp.experiment.domain.SubjectType;
import com.costonomy.mp.experiment.dto.ExperimentDtos;
import com.costonomy.mp.experiment.dto.ExperimentEvaluation;
import com.costonomy.mp.experiment.service.ExperimentService;
import com.costonomy.mp.experiment.web.ExperimentAdminController;
import com.costonomy.mp.experiment.web.ExperimentClientController;
import com.costonomy.mp.identity.security.AuthenticatedActor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ExperimentControllerTest {

    @Mock
    private ExperimentService experimentService;

    @Mock
    private AccessControlService accessControl;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new ExperimentAdminController(experimentService, accessControl),
                        new ExperimentClientController(experimentService)
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        AuthenticatedActor actor = new AuthenticatedActor(1L, "+919876543210");
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(actor, null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── Authorization Tests ──────────────────────────────────────────────

    @Test
    @DisplayName("Admin endpoint denies unauthenticated requests with 401 UNAUTHORIZED")
    void adminEndpointsDenyUnauthenticated() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(get("/api/v1/admin/experiments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("Admin endpoint denies ordinary authenticated user without CONFIG_VIEW with 403 FORBIDDEN")
    void adminEndpointsDenyUnauthorizedUser() throws Exception {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(accessControl).require(eq(1L), eq(Permissions.CONFIG_VIEW), eq(ScopeType.PLATFORM), any());

        mockMvc.perform(get("/api/v1/admin/experiments"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("Admin mutation endpoint denies user with CONFIG_VIEW but without CONFIG_MANAGE with 403 FORBIDDEN")
    void adminMutationDeniesWithoutConfigManage() throws Exception {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(accessControl).require(eq(1L), eq(Permissions.CONFIG_MANAGE), eq(ScopeType.PLATFORM), any());

        ExperimentDtos.CreateVariantRequest variant = new ExperimentDtos.CreateVariantRequest(
                "control", "Control Variant", null, 100, true, null
        );
        ExperimentDtos.CreateExperimentRequest request = new ExperimentDtos.CreateExperimentRequest(
                "cart_v2", "Cart V2", "Description", SubjectType.USER, 100,
                null, null, null, null, null, List.of(variant)
        );

        mockMvc.perform(post("/api/v1/admin/experiments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("Admin GET /api/v1/admin/experiments returns all experiments with filtering when authorized")
    void listExperiments() throws Exception {
        ExperimentDtos.ExperimentDto dto = new ExperimentDtos.ExperimentDto(
                1L, "checkout_v2", "Checkout Flow V2", "Tests 1-step checkout",
                ExperimentStatus.ACTIVE, SubjectType.USER, 100, "salt_1", null, null, null,
                null, null, List.of(), Instant.now(), Instant.now()
        );
        when(experimentService.listExperiments(null, null, null)).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/v1/admin/experiments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].experimentKey").value("checkout_v2"))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"));

        verify(accessControl).require(1L, Permissions.CONFIG_VIEW, ScopeType.PLATFORM, null);
    }

    @Test
    @DisplayName("Admin GET /api/v1/admin/experiments/live returns live snapshot when authorized")
    void getLiveSnapshot() throws Exception {
        com.costonomy.mp.experiment.dto.LiveExperimentSnapshot snapshot =
                com.costonomy.mp.experiment.dto.LiveExperimentSnapshot.builder()
                        .snapshotTimestamp(Instant.now())
                        .totalExperimentsCached(5)
                        .activeExperimentsCount(3)
                        .pausedExperimentsCount(1)
                        .draftExperimentsCount(1)
                        .cacheLastRefreshedAt(Instant.now())
                        .activeExperiments(List.of())
                        .engineMetadata(Map.of("hashing", "MurmurHash3"))
                        .build();

        when(experimentService.getLiveSnapshot()).thenReturn(snapshot);

        mockMvc.perform(get("/api/v1/admin/experiments/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeExperimentsCount").value(3))
                .andExpect(jsonPath("$.data.totalExperimentsCached").value(5));

        verify(accessControl).require(1L, Permissions.CONFIG_VIEW, ScopeType.PLATFORM, null);
    }

    @Test
    @DisplayName("Admin POST /api/v1/admin/experiments/{key}/inspect returns dry-run inspection")
    void inspectExperiment() throws Exception {
        ExperimentDtos.InspectExperimentRequest request = new ExperimentDtos.InspectExperimentRequest(
                "user_999", SubjectType.USER, Map.of("city", "Bangalore")
        );

        com.costonomy.mp.experiment.dto.InspectionResult result =
                com.costonomy.mp.experiment.dto.InspectionResult.builder()
                        .experimentKey("checkout_v2")
                        .subjectId("user_999")
                        .assignedVariantKey("control")
                        .isControl(true)
                        .inExperiment(true)
                        .reason(EvaluationReason.BUCKETED)
                        .targetingMatched(true)
                        .trafficBucket(1200)
                        .trafficThreshold(10000)
                        .inTrafficRollout(true)
                        .variantBucket(35)
                        .totalVariantWeight(100)
                        .evaluatedAt(Instant.now())
                        .build();

        when(experimentService.inspect(eq("checkout_v2"), eq("user_999"), eq(SubjectType.USER), any()))
                .thenReturn(result);

        mockMvc.perform(post("/api/v1/admin/experiments/checkout_v2/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignedVariantKey").value("control"))
                .andExpect(jsonPath("$.data.targetingMatched").value(true));

        verify(accessControl).require(1L, Permissions.CONFIG_VIEW, ScopeType.PLATFORM, null);
    }

    @Test
    @DisplayName("Admin POST /api/v1/admin/experiments creates experiment with CONFIG_MANAGE")
    void createExperiment() throws Exception {
        ExperimentDtos.CreateVariantRequest variant = new ExperimentDtos.CreateVariantRequest(
                "control", "Control Variant", null, 100, true, null
        );
        ExperimentDtos.CreateExperimentRequest request = new ExperimentDtos.CreateExperimentRequest(
                "pricing_algo", "Dynamic Pricing Algo", "Description", SubjectType.RESTAURANT, 100,
                null, null, null, null, null, List.of(variant)
        );

        ExperimentDtos.ExperimentDto created = new ExperimentDtos.ExperimentDto(
                2L, "pricing_algo", "Dynamic Pricing Algo", "Description",
                ExperimentStatus.DRAFT, SubjectType.RESTAURANT, 100, "salt_2", null, null, null,
                null, null, List.of(), Instant.now(), Instant.now()
        );
        when(experimentService.createExperiment(any())).thenReturn(created);

        mockMvc.perform(post("/api/v1/admin/experiments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.experimentKey").value("pricing_algo"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        verify(accessControl).require(1L, Permissions.CONFIG_MANAGE, ScopeType.PLATFORM, null);
    }

    @Test
    @DisplayName("Admin PUT /api/v1/admin/experiments/{key}/status updates status with CONFIG_MANAGE")
    void updateStatus() throws Exception {
        ExperimentDtos.UpdateStatusRequest request = new ExperimentDtos.UpdateStatusRequest(ExperimentStatus.ACTIVE);
        ExperimentDtos.ExperimentDto updated = new ExperimentDtos.ExperimentDto(
                2L, "pricing_algo", "Dynamic Pricing Algo", "Description",
                ExperimentStatus.ACTIVE, SubjectType.RESTAURANT, 100, "salt_2", null, null, null,
                null, null, List.of(), Instant.now(), Instant.now()
        );
        when(experimentService.updateStatus("pricing_algo", ExperimentStatus.ACTIVE)).thenReturn(updated);

        mockMvc.perform(put("/api/v1/admin/experiments/pricing_algo/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        verify(accessControl).require(1L, Permissions.CONFIG_MANAGE, ScopeType.PLATFORM, null);
    }

    @Test
    @DisplayName("Admin DELETE /api/v1/admin/experiments/{key} deletes experiment with CONFIG_MANAGE")
    void deleteExperiment() throws Exception {
        doNothing().when(experimentService).deleteExperiment("old_exp");

        mockMvc.perform(delete("/api/v1/admin/experiments/old_exp"))
                .andExpect(status().isNoContent());

        verify(accessControl).require(1L, Permissions.CONFIG_MANAGE, ScopeType.PLATFORM, null);
        verify(experimentService).deleteExperiment("old_exp");
    }

    @Test
    @DisplayName("Admin GET /api/v1/admin/experiments/{key}/analytics returns analytics with CONFIG_VIEW")
    void getAnalytics() throws Exception {
        ExperimentDtos.ExperimentAnalyticsResponse response = new ExperimentDtos.ExperimentAnalyticsResponse(
                "checkout_v2", "order_placed", null,
                List.of(new ExperimentDtos.VariantAnalyticsDto(
                        "control", "Control", true, 100L, 80L, 20L, 20L, new BigDecimal("2000.00"), 100.0, 25.0
                ))
        );
        when(experimentService.getAnalytics(eq("checkout_v2"), any(), any())).thenReturn(response);

        mockMvc.perform(get("/api/v1/admin/experiments/checkout_v2/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.experimentKey").value("checkout_v2"))
                .andExpect(jsonPath("$.data.variants[0].conversionRatePct").value(25.0));

        verify(accessControl).require(1L, Permissions.CONFIG_VIEW, ScopeType.PLATFORM, null);
    }

    // ── Client Endpoint Tests & Sanitization ─────────────────────────────

    @Test
    @DisplayName("Client GET /api/v1/experiments/active hides internal salts and overrides")
    void clientGetActiveExperimentsSanitized() throws Exception {
        ExperimentDtos.ClientVariantDto vDto = new ExperimentDtos.ClientVariantDto(
                "treatment", "Treatment Variant", "Desc", false, "{\"speed\":\"fast\"}"
        );
        ExperimentDtos.ClientExperimentDto expDto = new ExperimentDtos.ClientExperimentDto(
                1L, "fast_checkout", "Fast Checkout", "Speedy checkout test",
                SubjectType.USER, List.of(vDto)
        );
        when(experimentService.getActiveClientExperiments()).thenReturn(List.of(expDto));

        mockMvc.perform(get("/api/v1/experiments/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].experimentKey").value("fast_checkout"))
                .andExpect(jsonPath("$.data[0].salt").doesNotExist())
                .andExpect(jsonPath("$.data[0].overrides").doesNotExist())
                .andExpect(jsonPath("$.data[0].targetingRules").doesNotExist());
    }

    @Test
    @DisplayName("Client POST /api/v1/experiments/evaluate evaluates experiment")
    void clientEvaluate() throws Exception {
        ExperimentDtos.EvaluationRequest request = new ExperimentDtos.EvaluationRequest(
                "courier_selection_strategy", "outlet_123", SubjectType.RESTAURANT, Map.of("city", "Bangalore")
        );

        ExperimentEvaluation eval = ExperimentEvaluation.builder()
                .experimentKey("courier_selection_strategy")
                .variantKey("treatment")
                .isControl(false)
                .inExperiment(true)
                .reason(EvaluationReason.BUCKETED)
                .payloadJson("{\"strategy\":\"RELIABILITY_WEIGHTED\"}")
                .build();
        when(experimentService.evaluate(eq("courier_selection_strategy"), any(com.costonomy.mp.experiment.context.ExperimentContext.class))).thenReturn(eval);

        mockMvc.perform(post("/api/v1/experiments/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.experimentKey").value("courier_selection_strategy"))
                .andExpect(jsonPath("$.data.variantKey").value("treatment"))
                .andExpect(jsonPath("$.data.inExperiment").value(true));
    }

    @Test
    @DisplayName("Client POST /api/v1/experiments/evaluate-batch evaluates multiple experiments")
    void clientEvaluateBatch() throws Exception {
        ExperimentDtos.BatchEvaluationRequest request = new ExperimentDtos.BatchEvaluationRequest(
                "user_456", SubjectType.USER, Map.of(), List.of("flag_a", "flag_b")
        );

        ExperimentEvaluation evalA = ExperimentEvaluation.builder()
                .experimentKey("flag_a").variantKey("control").isControl(true).inExperiment(true)
                .reason(EvaluationReason.BUCKETED).build();
        ExperimentEvaluation evalB = ExperimentEvaluation.builder()
                .experimentKey("flag_b").variantKey("treatment").isControl(false).inExperiment(true)
                .reason(EvaluationReason.BUCKETED).build();

        when(experimentService.evaluateBatch(any(), any()))
                .thenReturn(Map.of("flag_a", evalA, "flag_b", evalB));

        mockMvc.perform(post("/api/v1/experiments/evaluate-batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.flag_a.variantKey").value("control"))
                .andExpect(jsonPath("$.data.flag_b.variantKey").value("treatment"));
    }

    @Test
    @DisplayName("Client POST /api/v1/experiments/exposures reports UI component mount impression")
    void clientRecordExposure() throws Exception {
        ExperimentDtos.RecordExposureRequest request = new ExperimentDtos.RecordExposureRequest(
                "cart_redesign", "treatment_b", "user_456", SubjectType.USER, Map.of("screen", "CartScreen")
        );

        mockMvc.perform(post("/api/v1/experiments/exposures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("recorded"))
                .andExpect(jsonPath("$.data.experimentKey").value("cart_redesign"));

        verify(experimentService).recordExposure(
                eq("cart_redesign"), eq("treatment_b"), eq("user_456"), eq(SubjectType.USER), any()
        );
    }

    @Test
    @DisplayName("Client POST /api/v1/experiments/metrics records conversion event with explicit attribution")
    void clientTrackMetric() throws Exception {
        ExperimentDtos.TrackMetricRequest request = new ExperimentDtos.TrackMetricRequest(
                "user_456", "add_to_cart", new BigDecimal("45.00"), Map.of("itemId", 123),
                "cart_redesign", "treatment_b", 999L
        );

        mockMvc.perform(post("/api/v1/experiments/metrics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("recorded"))
                .andExpect(jsonPath("$.data.metric").value("add_to_cart"));

        verify(experimentService).trackMetric(
                eq("user_456"), eq("add_to_cart"), eq(new BigDecimal("45.00")), any(),
                eq("cart_redesign"), eq("treatment_b"), eq(999L)
        );
    }
}
