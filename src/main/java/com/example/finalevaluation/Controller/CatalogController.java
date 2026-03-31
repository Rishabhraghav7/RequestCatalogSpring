package com.example.finalevaluation.Controller;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.example.finalevaluation.Model.CatalogDTO;
import com.example.finalevaluation.Service.CatalogService;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:5173")
public class CatalogController {

    private static final Logger logger = LoggerFactory.getLogger(CatalogController.class);
    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @PostMapping("/catalog-request")
    public ResponseEntity<Map<String, Object>> receiveRequest(
            @RequestBody CatalogDTO catalog,
            HttpServletRequest request) {

        Map<String, Object> response = new HashMap<>();

        // ── Step 2: Server-side field validation (BRD §6) ──────────────────
        Map<String, String> fieldErrors = catalogService.validateFields(catalog);
        if (!fieldErrors.isEmpty()) {
            response.put("success", false);
            response.put("error", "Validation failed.");
            response.put("fields", fieldErrors);
            return ResponseEntity.status(422).body(response);
        }

        // ── Step 3: CAPTCHA verification (BRD §4.1.3) ──────────────────────
        // TODO: Remove the bypass below once VITE_RECAPTCHA_SITE_KEY is configured.
        // When reCAPTCHA is live, delete the next two lines so the block runs properly.
        boolean captchaEnabled = false; // set to true once keys are configured
        double captchaScore = captchaEnabled ? catalogService.verifyCaptcha(catalog.getCaptcha_token()) : 1.0;
        if (captchaEnabled && captchaScore < 0) {
            response.put("success", false);
            response.put("error", "CAPTCHA verification failed. Please try again.");
            return ResponseEntity.status(400).body(response);
        }

        // ── Steps 4–11: Main processing (duplicate check, DB, email, Shopify) ──
        Object result = catalogService.processRequest(catalog, request, captchaScore);

        if ("duplicate".equals(result)) {
            // BRD §4.5.5 — intentionally generic message
            response.put("success", false);
            response.put("error", "Internal error. Please wait — our team will validate and get back to you.");
            return ResponseEntity.status(429).body(response);
        }

        if ("error".equals(result)) {
            // BRD §4.5.7
            response.put("success", false);
            response.put("error", "An unexpected error occurred. Please try again later.");
            return ResponseEntity.status(500).body(response);
        }

        if (!(result instanceof com.example.finalevaluation.Model.ProcessResult)) {
            response.put("success", false);
            response.put("error", "An unexpected error occurred. Please try again later.");
            return ResponseEntity.status(500).body(response);
        }

        // ── Step 11: Success response (BRD §4.5.3) ─────────────────────────
        com.example.finalevaluation.Model.ProcessResult pr =
                (com.example.finalevaluation.Model.ProcessResult) result;

        // BRD §4.5.3 — request_id formatted as REQ-YYYYMMDD-NNNN
        String formattedRequestId = String.format("REQ-%s-%04d",
                java.time.LocalDate.now().toString().replace("-", ""),
                pr.getRequestId());

        response.put("success", true);
        response.put("message", "Your catalog request has been submitted successfully!");
        response.put("request_id", formattedRequestId);

        // BRD §4.5.3 — include shopify_order_number only when present
        if (pr.getShopifyOrderNumber() != null) {
            response.put("shopify_order_number", "#" + pr.getShopifyOrderNumber());
        }

        return ResponseEntity.ok(response);
    }
}