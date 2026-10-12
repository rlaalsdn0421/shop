package com.shop.backend.domain.entity;

/** Version of the terms/privacy policy shown at signup. Set by the server so a client can never choose it. */
public final class PolicyVersion {

    /** Keep equal to the version the frontend policy pages display; bump it whenever the policy text changes. */
    public static final String CURRENT = "2026-10-12";

    private PolicyVersion() {
    }
}
