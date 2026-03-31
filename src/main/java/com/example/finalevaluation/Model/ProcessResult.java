package com.example.finalevaluation.Model;

public class ProcessResult {
    private final long requestId;
    private final String shopifyOrderNumber;

    public ProcessResult(long requestId, String shopifyOrderNumber) {
        this.requestId = requestId;
        this.shopifyOrderNumber = shopifyOrderNumber;
    }

    public long getRequestId() { return requestId; }
    public String getShopifyOrderNumber() { return shopifyOrderNumber; }
}