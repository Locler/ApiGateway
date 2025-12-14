package com.contoller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@RestController
public class RegistrationController {

    private final Logger log = LoggerFactory.getLogger(RegistrationController.class);

    private final WebClient authClient;

    private final WebClient userClient;

    public RegistrationController(@Qualifier("authClient") WebClient authClient,
                                  @Qualifier("userClient") WebClient userClient) {
        this.authClient = authClient;
        this.userClient = userClient;
    }

    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Map<String, Object>>>> register(
            @RequestBody Map<String, Object> body) {

        Map<String, Object> credentials = (Map<String, Object>) body.get("credentials");
        Map<String, Object> profile = (Map<String, Object>) body.get("profile");

        if (credentials == null || profile == null) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("error", Map.of("message", "credentials and profile required"))));
        }

        // 1) create credentials in auth-service
        return authClient.post()
                .uri("/auth/register")
                .bodyValue(credentials)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .defaultIfEmpty(Map.of())
                .flatMap(authResp -> {
                    // authId для rollback
                    final Object finalAuthId = authResp.getOrDefault("username", authResp.get("id"));
                    final Map<String, Object> profileWithAuth = new HashMap<>(profile);
                    if (finalAuthId != null) {
                        profileWithAuth.put("authIdentifier", finalAuthId);
                    }

                    // 2) create user profile
                    return userClient.post()
                            .uri("/users")
                            .bodyValue(profileWithAuth)
                            .retrieve()
                            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                            .map(userResp -> {
                                Map<String, Map<String, Object>> result = new HashMap<>();
                                result.put("auth", authResp);
                                result.put("user", userResp);
                                return ResponseEntity.status(201).body(result);
                            })
                            .onErrorResume(userErr -> {
                                log.error("User creation failed, rolling back auth. Error: {}", userErr.getMessage());
                                if (finalAuthId != null) {
                                    return authClient.delete()
                                            .uri(uriBuilder -> uriBuilder.path("/auth/{idOrUsername}")
                                                    .build(finalAuthId))
                                            .retrieve()
                                            .bodyToMono(Void.class)
                                            .onErrorResume(delErr -> {
                                                log.error("Rollback DELETE /auth/{} failed: {}", finalAuthId, delErr.getMessage());
                                                return Mono.error(new RuntimeException("User creation failed and rollback failed"));
                                            })
                                            .then(Mono.error(new RuntimeException("User creation failed, auth rolled back")));
                                } else {
                                    return Mono.error(new RuntimeException("User creation failed and no auth id for rollback"));
                                }
                            });
                })
                .onErrorResume(authErr -> {
                    log.error("Auth registration failed: {}", authErr.getMessage());
                    Map<String, Map<String, Object>> errorBody = Map.of(
                            "error", Map.of(
                                    "message", "auth registration failed",
                                    "detail", authErr.getMessage()
                            )
                    );
                    return Mono.just(ResponseEntity.status(500).body(errorBody));
                });
    }
}
