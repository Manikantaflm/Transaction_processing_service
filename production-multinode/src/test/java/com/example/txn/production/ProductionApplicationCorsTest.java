package com.example.txn.production;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionApplicationCorsTest {

    @Test
    void defaultAllowedOriginsIncludeVitePorts() {
        Set<String> origins = ProductionApplication.allowedOrigins();
        assertTrue(origins.contains("http://localhost:5173"));
        assertTrue(origins.contains("http://localhost:5174"));
    }
}
