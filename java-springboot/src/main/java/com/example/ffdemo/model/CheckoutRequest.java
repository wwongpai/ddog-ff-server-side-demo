package com.example.ffdemo.model;

public class CheckoutRequest {

    private String userId;
    private String plan;
    private double amount;

    public CheckoutRequest() {}

    public CheckoutRequest(String userId, String plan, double amount) {
        this.userId = userId;
        this.plan = plan;
        this.amount = amount;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getPlan() { return plan; }
    public void setPlan(String plan) { this.plan = plan; }

    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }
}
