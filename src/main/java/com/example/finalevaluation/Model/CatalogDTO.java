package com.example.finalevaluation.Model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class CatalogDTO {

    public enum countryEnum {
        @JsonProperty("United States") UNITED_STATES,
        @JsonProperty("Canada") CANADA
    }

    private String first_name;
    private String last_name;
    private String company;
    private String job_title;
    private String email;
    private String phone;
    private String address;
    private String city;
    private String state;
    private String zip;
    private countryEnum country;
    private String notes;
    private boolean physical_catalog;
    private boolean email_others;
    private String email_others_addresses;

    // ✅ ADDED: Required by doc §4.1 — reCAPTCHA token from frontend
    private String captcha_token;

    public CatalogDTO() {}

    // Getters & Setters

    public String getFirst_name() { return first_name; }
    public void setFirst_name(String first_name) { this.first_name = first_name; }

    public String getLast_name() { return last_name; }
    public void setLast_name(String last_name) { this.last_name = last_name; }

    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }

    public String getJob_title() { return job_title; }
    public void setJob_title(String job_title) { this.job_title = job_title; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getZip() { return zip; }
    public void setZip(String zip) { this.zip = zip; }

    public countryEnum getCountry() { return country; }
    public void setCountry(countryEnum country) { this.country = country; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public boolean isPhysical_catalog() { return physical_catalog; }
    public void setPhysical_catalog(boolean physical_catalog) { this.physical_catalog = physical_catalog; }

    public boolean isEmail_others() { return email_others; }
    public void setEmail_others(boolean email_others) { this.email_others = email_others; }

    public String getEmail_others_addresses() { return email_others_addresses; }
    public void setEmail_others_addresses(String email_others_addresses) { this.email_others_addresses = email_others_addresses; }

    public String getCaptcha_token() { return captcha_token; }
    public void setCaptcha_token(String captcha_token) { this.captcha_token = captcha_token; }
}