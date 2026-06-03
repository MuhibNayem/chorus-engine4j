package com.chorus.engine.sample;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Minimal REST API surface for web-profile deployments.
 * Exposes status and version endpoints alongside the standard actuator endpoints.
 *
 * <p>Active only when {@code --spring.profiles.active=web} is set.
 * In CLI mode (default profile) this controller is not loaded.
 */
@RestController
@Profile("web")
@RequestMapping("/api")
class ChorusWebController {

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "mode", "web",
            "timestamp", Instant.now().toString()
        ));
    }

    @GetMapping("/version")
    public ResponseEntity<Map<String, String>> version() {
        return ResponseEntity.ok(Map.of(
            "version", "0.2.0",
            "engine", "chorus-engine4j"
        ));
    }
}
