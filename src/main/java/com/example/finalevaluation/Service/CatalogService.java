package com.example.finalevaluation.Service;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import com.example.finalevaluation.Model.CatalogDTO;
import com.example.finalevaluation.Repository.catalogRepo;
import jakarta.servlet.http.HttpServletRequest;

@Service
public class CatalogService {

    private static final Logger logger = LoggerFactory.getLogger(CatalogService.class);
    private final catalogRepo repo;
    private final RestTemplate restTemplate;

    // BRD §4.4.2
    private final String SHOPIFY_URL = "YOUR_SHOPIFY_STORE_URL/admin/api/2023-10/orders.json";
    private final String SHOPIFY_TOKEN = "YOUR_SHOPIFY_ACCESS_TOKEN";

    // BRD §7 — move to application.properties in production
    private final String RECAPTCHA_SECRET_KEY = "YOUR_RECAPTCHA_SECRET_KEY";
    private final String RECAPTCHA_VERIFY_URL = "https://www.google.com/recaptcha/api/siteverify";
    private final double RECAPTCHA_SCORE_THRESHOLD = 0.5;

    public CatalogService(catalogRepo repo, RestTemplate restTemplate) {
        this.repo = repo;
        this.restTemplate = restTemplate;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BRD §4.1.3 — CAPTCHA VERIFICATION
    // Returns score >= threshold on success, -1.0 on failure.
    // Controller checks this before anything else (BRD Step 3).
    // ─────────────────────────────────────────────────────────────────────────
    public double verifyCaptcha(String token) {
        if (token == null || token.isBlank()) {
            logger.warn("CAPTCHA token missing");
            return -1.0;
        }
        try {
            String url = RECAPTCHA_VERIFY_URL + "?secret=" + RECAPTCHA_SECRET_KEY + "&response=" + token;
            ResponseEntity<Map> response = restTemplate.postForEntity(url, null, Map.class);

            if (response.getBody() == null) return -1.0;

            Map body = response.getBody();
            Boolean success = (Boolean) body.get("success");

            if (Boolean.TRUE.equals(success)) {
                // v3 returns score; v2 does not — default 1.0 for v2
                Object scoreObj = body.get("score");
                double score = scoreObj != null ? ((Number) scoreObj).doubleValue() : 1.0;
                logger.info("reCAPTCHA score: {}", score);
                if (score < RECAPTCHA_SCORE_THRESHOLD) {
                    logger.warn("reCAPTCHA score below threshold: {}", score);
                    return -1.0;
                }
                return score;
            }
        } catch (Exception e) {
            logger.error("reCAPTCHA verification error: {}", e.getMessage());
        }
        return -1.0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BRD §5 Steps 4–11 — MAIN PROCESSING FLOW
    // Controller calls this after validation (Step 2) and CAPTCHA (Step 3).
    // Returns: "duplicate", "error", or ProcessResult on success.
    // ─────────────────────────────────────────────────────────────────────────
    public Object processRequest(CatalogDTO catalog, HttpServletRequest request, double captchaScore) {
        // Step 4
        String clientIp = getClientIp(request);
        // Step 5 & 6
        String email = catalog.getEmail().toLowerCase().trim();
        String normalizedAddress = getNormalizedAddress(catalog);

        logger.info("Processing request — IP: {}, Email: {}", clientIp, email);

        // Step 7 — BRD §4.3.2: OR logic across email / address / IP
        if (repo.isDuplicate(email, clientIp, normalizedAddress)) {
            logger.warn("Duplicate detected — email: {}, ip: {}, addr: {}", email, clientIp, normalizedAddress);
            return "duplicate";
        }

        // Step 8 — Insert record, get generated DB id
        long requestId;
        try {
            requestId = repo.saveCatalogDataInDB(catalog, clientIp, normalizedAddress, captchaScore);
        } catch (Exception e) {
            logger.error("DB insert failed: {}", e.getMessage());
            return "error";
        }

        // Step 9 — Send digital catalog email (non-blocking; failure does not affect user response)
        try {
            sendDigitalCatalog(catalog);
        } catch (Exception e) {
            logger.error("Email notification failed (non-blocking): {}", e.getMessage());
        }

        // Step 10 — BRD §4.4.1: Only when physical_catalog = true
        String shopifyOrderNumber = null;
        if (catalog.isPhysical_catalog()) {
            String[] shopifyResult = createShopifyOrder(catalog); // [orderId, orderNumber]
            if (shopifyResult != null) {
                // BRD §4.4.4 — Update DB with shopify details after successful order
                repo.updateShopifyDetails(requestId, shopifyResult[0], shopifyResult[1], "created");
                shopifyOrderNumber = shopifyResult[1];
            } else {
                // BRD §4.4.5 — Mark failed in DB; still return 200 to user
//                repo.markShopifyFailed(requestId);
            	logger.error("Shopify order creation failed for requestId: {}", requestId);
            }
        }

        // Step 11 — Return result with requestId + optional shopify order number
        return new com.example.finalevaluation.Model.ProcessResult(requestId, shopifyOrderNumber);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BRD §4.4.3 — SHOPIFY ORDER CREATION
    // Returns [orderId, orderNumber] on success, null on failure.
    // ─────────────────────────────────────────────────────────────────────────
    private String[] createShopifyOrder(CatalogDTO catalog) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Shopify-Access-Token", SHOPIFY_TOKEN);

            // BRD §4.4.3 — Full billing + shipping address from user's form data
            Map<String, Object> address = buildAddressMap(catalog);

            Map<String, Object> lineItem = new HashMap<>();
            lineItem.put("title", "Restaurantware Catalog");
            lineItem.put("price", "0.00");
            lineItem.put("sku", "RWCatalogs"); // BRD: Do not change
            lineItem.put("quantity", 1);

            Map<String, Object> orderDetails = new HashMap<>();
            orderDetails.put("email", catalog.getEmail());
            orderDetails.put("billing_address", address);
            orderDetails.put("shipping_address", address);
            orderDetails.put("line_items", Collections.singletonList(lineItem));

            Map<String, Object> orderRoot = new HashMap<>();
            orderRoot.put("order", orderDetails);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(orderRoot, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(SHOPIFY_URL, entity, Map.class);

            if (response.getStatusCode() == HttpStatus.CREATED && response.getBody() != null) {
                Map orderObj = (Map) response.getBody().get("order");
                String orderId = orderObj.get("id").toString();
                String orderNumber = orderObj.get("order_number").toString();
                logger.info("Shopify order created — id: {}, number: #{}", orderId, orderNumber);
                return new String[]{orderId, orderNumber};
            }
        } catch (Exception e) {
            logger.error("Shopify order creation failed: {}", e.getMessage());
        }
        return null;
    }

    private Map<String, Object> buildAddressMap(CatalogDTO catalog) {
        Map<String, Object> address = new HashMap<>();
        address.put("first_name", catalog.getFirst_name());
        address.put("last_name", catalog.getLast_name());
        address.put("address1", catalog.getAddress());
        address.put("address2", null);
        address.put("city", catalog.getCity());
        address.put("province", catalog.getState());
        address.put("zip", catalog.getZip());
        address.put("country", catalog.getCountry() != null ? catalog.getCountry().name() : "");
        address.put("phone", catalog.getPhone());
        address.put("company", catalog.getCompany());
        address.put("name", catalog.getFirst_name() + " " + catalog.getLast_name());
        return address;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BRD §11 — EMAIL NOTIFICATION
    // ─────────────────────────────────────────────────────────────────────────
    private void sendDigitalCatalog(CatalogDTO catalog) {
        String url = "http://192.168.16.212:8069/api/v1/notification/email";

        List<String> toAddresses = new ArrayList<>();
        toAddresses.add(catalog.getEmail());
        toAddresses.add("siddharth@rw.team");

        List<String> ccAddresses = new ArrayList<>();
        ccAddresses.add("sijo.george@rw.team");

        List<String> bccAddresses = new ArrayList<>();
        bccAddresses.add("vigneshwaran@rw.team");
        bccAddresses.add("thanushraj@rw.team");

        // BRD §2.1 — Add extra recipients if email_others = true
        if (catalog.isEmail_others() && !isEmpty(catalog.getEmail_others_addresses())) {
            for (String extra : catalog.getEmail_others_addresses().split(",")) {
                if (!extra.trim().isEmpty()) toAddresses.add(extra.trim());
            }
        }

        Map<String, Object> emailContent = new HashMap<>();
        emailContent.put("userName", catalog.getFirst_name());
        emailContent.put("fileName", "RW Catalogs");
        emailContent.put("message", "Below is your RW Catalog");
        emailContent.put("status", "Sent via Automated System");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("emailTemplateId", "fb261aa5-d372-584f-ba18-a5154dec7f15");
        requestBody.put("toAddresses", toAddresses);
        requestBody.put("ccAddresses", ccAddresses);
        requestBody.put("bccAddresses", bccAddresses);
        requestBody.put("emailSubject", "Final Evaluation");
        requestBody.put("emailContent", emailContent);
        requestBody.put("sentFrom", "vigneshwaran@rw.team");
        requestBody.put("isAlertEnvConfigured", true);
        requestBody.put("alertType", "INFO");
        requestBody.put("mailType", "TRANSCATION"); // BRD §11 spelling kept as-is
        requestBody.put("attachments", "SGVsbG8gV29ybGQ=");
        requestBody.put("attachmentName", "RW_Catalog.pdf");

        restTemplate.postForEntity(url, requestBody, String.class);
        logger.info("Email notification sent to: {}", catalog.getEmail());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BRD §6 — SERVER-SIDE FIELD VALIDATION
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, String> validateFields(CatalogDTO dto) {
        Map<String, String> errors = new HashMap<>();

        if (isEmpty(dto.getFirst_name()))   errors.put("first_name", "First name is required.");
        if (isEmpty(dto.getLast_name()))    errors.put("last_name", "Last name is required.");
        if (isEmpty(dto.getCompany()))      errors.put("company", "Company is required.");
        if (isEmpty(dto.getJob_title()))    errors.put("job_title", "Job title is required.");
        if (isEmpty(dto.getPhone()))        errors.put("phone", "Phone number is required.");
        if (isEmpty(dto.getAddress()))      errors.put("address", "Address is required.");
        if (isEmpty(dto.getCity()))         errors.put("city", "City is required.");
        if (isEmpty(dto.getState()))        errors.put("state", "State is required.");
        if (isEmpty(dto.getZip()))          errors.put("zip", "Zip code is required.");
        if (dto.getCountry() == null)       errors.put("country", "Country is required.");

        if (isEmpty(dto.getEmail())) {
            errors.put("email", "Email is required.");
        } else if (!dto.getEmail().matches("^[A-Za-z0-9+_.-]+@(.+)$")) {
            errors.put("email", "A valid email address is required.");
        }

        if (dto.isEmail_others() && isEmpty(dto.getEmail_others_addresses())) {
            errors.put("email_others_addresses", "Please specify the email addresses.");
        }

        if (dto.isEmail_others() && !isEmpty(dto.getEmail_others_addresses())) {
            String[] extras = dto.getEmail_others_addresses().split(",");
            for (String extra : extras) {
                if (!extra.trim().matches("^[A-Za-z0-9+_.-]+@(.+)$")) {
                    errors.put("email_others_addresses", "One or more email addresses are invalid.");
                    break;
                }
            }
        }

        // NOTE: captcha_token is intentionally NOT validated here.
        // It is handled separately in the controller (Step 3) via verifyCaptcha().
        // While CAPTCHA is not yet configured, the controller bypasses the check.

        return errors;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }

    // BRD §4.3.4 — IP extraction priority
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return (ip != null && ip.contains(",")) ? ip.split(",")[0].trim() : ip;
    }

    // BRD §4.3.1 — normalized_address: lowercase, trim, collapse whitespace, concat all parts
    private String getNormalizedAddress(CatalogDTO dto) {
        String addr    = dto.getAddress()  != null ? dto.getAddress()  : "";
        String city    = dto.getCity()     != null ? dto.getCity()     : "";
        String state   = dto.getState()    != null ? dto.getState()    : "";
        String zip     = dto.getZip()      != null ? dto.getZip()      : "";
        String country = dto.getCountry()  != null ? dto.getCountry().name() : "";

        return (addr + city + state + zip + country).toLowerCase().replaceAll("\\s+", "").trim();
    }
}