package com.costonomy.mp.experiment.web;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.experiment.domain.ExperimentStatus;
import com.costonomy.mp.experiment.dto.ExperimentDtos;
import com.costonomy.mp.experiment.service.ExperimentService;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Built-in visual dashboard for operators and engineers to view and inspect all experiments
 * in real-time from any web browser.
 */
@RestController
@RequestMapping("/api/v1/admin/experiments/dashboard")
@RequiredArgsConstructor
@Tag(name = "Experiments Dashboard", description = "Visual HTML inspection dashboard for experiments")
public class ExperimentDashboardController {

    private final ExperimentService experimentService;
    private final AccessControlService accessControl;

    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "View visual experiments dashboard", description = "Renders responsive real-time visual dashboard HTML for viewing experiments. Requires CONFIG_VIEW.")
    public String renderDashboard(
            @RequestParam(required = false) ExperimentStatus status,
            @RequestParam(required = false) String search) {

        Long actorId = ActorContext.requireUserId();
        accessControl.require(actorId, Permissions.CONFIG_VIEW, ScopeType.PLATFORM, null);

        List<ExperimentDtos.ExperimentDto> experiments = experimentService.listExperiments(status, null, search);
        var snapshot = experimentService.getLiveSnapshot();

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
            .append("<meta charset=\"UTF-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
            .append("<title>Costonomy Experimentation & A/B Platform</title>\n")
            .append("<style>\n")
            .append(":root { --primary: #0284c7; --bg: #f8fafc; --card: #ffffff; --text: #0f172a; --border: #e2e8f0; }\n")
            .append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: var(--bg); color: var(--text); margin: 0; padding: 24px; line-height: 1.5; }\n")
            .append(".container { max-width: 1200px; margin: 0 auto; }\n")
            .append(".header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; padding-bottom: 16px; border-bottom: 2px solid var(--border); }\n")
            .append(".title { font-size: 24px; font-weight: 700; color: #0f172a; }\n")
            .append(".subtitle { color: #64748b; font-size: 14px; margin-top: 4px; }\n")
            .append(".metrics-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; margin-bottom: 24px; }\n")
            .append(".metric-card { background: var(--card); border: 1px solid var(--border); border-radius: 8px; padding: 16px; box-shadow: 0 1px 3px rgba(0,0,0,0.05); }\n")
            .append(".metric-val { font-size: 28px; font-weight: 700; color: var(--primary); }\n")
            .append(".metric-lbl { color: #64748b; font-size: 13px; font-weight: 500; text-transform: uppercase; letter-spacing: 0.5px; }\n")
            .append(".exp-card { background: var(--card); border: 1px solid var(--border); border-radius: 10px; padding: 20px; margin-bottom: 20px; box-shadow: 0 1px 4px rgba(0,0,0,0.04); }\n")
            .append(".exp-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12px; }\n")
            .append(".exp-key { font-family: monospace; font-size: 15px; font-weight: 600; background: #e0f2fe; color: #0369a1; padding: 4px 8px; border-radius: 4px; }\n")
            .append(".badge { padding: 4px 10px; border-radius: 20px; font-size: 12px; font-weight: 600; text-transform: uppercase; }\n")
            .append(".badge-ACTIVE { background: #dcfce7; color: #15803d; }\n")
            .append(".badge-DRAFT { background: #f1f5f9; color: #475569; }\n")
            .append(".badge-PAUSED { background: #fef9c3; color: #a16207; }\n")
            .append(".badge-ARCHIVED { background: #fee2e2; color: #b91c1c; }\n")
            .append(".progress-container { background: #e2e8f0; border-radius: 4px; height: 8px; width: 100%; overflow: hidden; margin-top: 6px; }\n")
            .append(".progress-bar { background: var(--primary); height: 100%; }\n")
            .append("table { width: 100%; border-collapse: collapse; margin-top: 12px; font-size: 13px; }\n")
            .append("th, td { text-align: left; padding: 8px 12px; border-bottom: 1px solid var(--border); }\n")
            .append("th { background: #f8fafc; font-weight: 600; color: #475569; }\n")
            .append("code { font-family: monospace; background: #f1f5f9; padding: 2px 4px; border-radius: 3px; font-size: 12px; }\n")
            .append(".inspector-box { background: #f8fafc; border: 1px dashed var(--primary); border-radius: 8px; padding: 14px; margin-top: 14px; }\n")
            .append("</style>\n</head>\n<body>\n")
            .append("<div class=\"container\">\n")
            .append("<div class=\"header\">\n")
            .append("  <div>\n")
            .append("    <div class=\"title\">Experimentation & A/B Testing Platform</div>\n")
            .append("    <div class=\"subtitle\">Live status snapshot generated at ")
            .append(DateTimeFormatter.ISO_INSTANT.format(snapshot.getSnapshotTimestamp()))
            .append("</div>\n  </div>\n</div>\n");

        // Metrics Summary
        html.append("<div class=\"metrics-grid\">\n")
            .append("  <div class=\"metric-card\"><div class=\"metric-lbl\">Active Experiments</div><div class=\"metric-val\">")
            .append(snapshot.getActiveExperimentsCount()).append("</div></div>\n")
            .append("  <div class=\"metric-card\"><div class=\"metric-lbl\">Total Configured</div><div class=\"metric-val\">")
            .append(snapshot.getTotalExperimentsCached()).append("</div></div>\n")
            .append("  <div class=\"metric-card\"><div class=\"metric-lbl\">Paused / Draft</div><div class=\"metric-val\">")
            .append(snapshot.getPausedExperimentsCount() + snapshot.getDraftExperimentsCount()).append("</div></div>\n")
            .append("  <div class=\"metric-card\"><div class=\"metric-lbl\">Engine Status</div><div class=\"metric-val\" style=\"color:#16a34a;\">HEALTHY</div></div>\n")
            .append("</div>\n");

        // Experiments List
        if (experiments.isEmpty()) {
            html.append("<div class=\"exp-card\" style=\"text-align:center; padding:40px; color:#64748b;\">No experiments match the filter criteria.</div>\n");
        } else {
            for (var exp : experiments) {
                html.append("<div class=\"exp-card\">\n")
                    .append("  <div class=\"exp-header\">\n")
                    .append("    <div>\n")
                    .append("      <span class=\"exp-key\">").append(exp.experimentKey()).append("</span>\n")
                    .append("      <span style=\"margin-left: 10px; font-size: 16px; font-weight: 600;\">").append(exp.name()).append("</span>\n")
                    .append("      <div style=\"color:#64748b; font-size: 13px; margin-top:4px;\">").append(exp.description() != null ? exp.description() : "").append("</div>\n")
                    .append("    </div>\n")
                    .append("    <span class=\"badge badge-").append(exp.status().name()).append("\">").append(exp.status().name()).append("</span>\n")
                    .append("  </div>\n");

                // Rollout bar
                html.append("  <div style=\"margin: 12px 0;\">\n")
                    .append("    <div style=\"display:flex; justify-content:space-between; font-size:12px; color:#64748b;\">")
                    .append("      <span>Traffic Rollout: <strong>").append(exp.trafficAllocationPct()).append("%</strong></span>")
                    .append("      <span>Subject Type: <strong>").append(exp.subjectType().name()).append("</strong></span>")
                    .append("    </div>\n")
                    .append("    <div class=\"progress-container\"><div class=\"progress-bar\" style=\"width: ")
                    .append(exp.trafficAllocationPct()).append("%;\"></div></div>\n")
                    .append("  </div>\n");

                // Variants table
                html.append("  <table>\n")
                    .append("    <thead><tr><th>Variant Key</th><th>Name</th><th>Split Weight</th><th>Control?</th><th>Config Payload</th></tr></thead>\n")
                    .append("    <tbody>\n");
                for (var v : exp.variants()) {
                    html.append("    <tr>\n")
                        .append("      <td><code>").append(v.variantKey()).append("</code></td>\n")
                        .append("      <td>").append(v.name()).append("</td>\n")
                        .append("      <td>").append(v.weight()).append("</td>\n")
                        .append("      <td>").append(v.isControl() ? "<span style=\"color:#16a34a; font-weight:600;\">YES</span>" : "No").append("</td>\n")
                        .append("      <td><code>").append(v.payloadJson() != null ? v.payloadJson() : "{}").append("</code></td>\n")
                        .append("    </tr>\n");
                }
                html.append("    </tbody></table>\n");

                if (exp.targetingRules() != null && !exp.targetingRules().isBlank()) {
                    html.append("  <div style=\"margin-top:10px; font-size:12px; color:#475569;\"><strong>Targeting Criteria:</strong> <code>")
                        .append(exp.targetingRules()).append("</code></div>\n");
                }

                if (exp.overrides() != null && !exp.overrides().isBlank()) {
                    html.append("  <div style=\"margin-top:4px; font-size:12px; color:#475569;\"><strong>Overrides / Whitelist:</strong> <code>")
                        .append(exp.overrides()).append("</code></div>\n");
                }

                html.append("</div>\n");
            }
        }

        html.append("</div>\n</body>\n</html>");
        return html.toString();
    }
}
