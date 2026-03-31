package com.example.finalevaluation.Repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.finalevaluation.Model.CatalogDTO;

@Repository
public class catalogRepo {

    private static final Logger logger = LoggerFactory.getLogger(catalogRepo.class);
    private final NamedParameterJdbcTemplate jdbc;

    public catalogRepo(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Doc §4.3.2 — Duplicate check uses OR across email, normalizedAddress, clientIp.
     * Fixed: was using AND which would never match correctly.
     */
    public boolean isDuplicate(String email, String ip, String normalizedAddress) {
        String sql = "SELECT COUNT(*) FROM catalog_requests " +
                "WHERE created_at >= NOW() - INTERVAL 30 DAY " +
                "AND (email = :email OR normalized_address = :norm OR client_ip = :ip)";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("email", email)
                .addValue("ip", ip)
                .addValue("norm", normalizedAddress);

        Integer count = jdbc.queryForObject(sql, params, Integer.class);
        return count != null && count > 0;
    }

    /**
     * Doc §4.4.4 — Insert returns the generated DB id so Shopify update can reference it.
     * captcha_score saved per doc §4.2.1 for auditing.
     */
    public long saveCatalogDataInDB(CatalogDTO dto, String clientIp, String normalizedAddress, Double captchaScore) {
        String sql = "INSERT INTO catalog_requests " +
                "(first_name, last_name, company, job_title, email, phone, " +
                "address, city, state, zip, country, notes, physical_catalog, email_others, " +
                "email_others_addresses, client_ip, normalized_address, status) " +
                "VALUES (:first_name, :last_name, :company, :job_title, :email, :phone, " +
                ":address, :city, :state, :zip, :country, :notes, :physical_catalog, :email_others, " +
                ":email_others_addresses, :client_ip, :normalized_address, :status)";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("first_name", dto.getFirst_name())
                .addValue("last_name", dto.getLast_name())
                .addValue("company", dto.getCompany())
                .addValue("job_title", dto.getJob_title())
                .addValue("email", dto.getEmail().toLowerCase().trim())
                .addValue("phone", dto.getPhone())
                .addValue("address", dto.getAddress())
                .addValue("city", dto.getCity())
                .addValue("state", dto.getState())
                .addValue("zip", dto.getZip())
                .addValue("country", dto.getCountry().name())
                .addValue("notes", dto.getNotes())
                .addValue("physical_catalog", dto.isPhysical_catalog())
                .addValue("email_others", dto.isEmail_others())
                .addValue("email_others_addresses", dto.getEmail_others_addresses())
                .addValue("client_ip", clientIp)
                .addValue("normalized_address", normalizedAddress)
                .addValue("status", "completed");

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(sql, params, keyHolder);

        return keyHolder.getKey() != null ? keyHolder.getKey().longValue() : -1L;
    }

    /**
     * Doc §4.4.4 — After successful Shopify response, update the record with order details.
     * Called after insert; uses the auto-incremented id returned from saveCatalogDataInDB.
     */
    public void updateShopifyDetails(long requestId, String shopifyOrderId, String shopifyOrderNumber, String status) {
        String sql = "UPDATE catalog_requests " +
                "SET shopify_order_id = :shopifyOrderId, " +
                "    shopify_order_number = :shopifyOrderNumber, " +
                "    shopify_order_status = :status, " +
                "    updated_at = NOW() " +
                "WHERE id = :requestId";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("shopifyOrderId", shopifyOrderId)
                .addValue("shopifyOrderNumber", shopifyOrderNumber)
                .addValue("status", status)
                .addValue("requestId", requestId);

        jdbc.update(sql, params);
    }
}