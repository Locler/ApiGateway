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
@RequestMapping("/register")
public class RegistrationController {

    private final Logger log = LoggerFactory.getLogger(RegistrationController.class);

    private final WebClient userClient;  // Прямой вызов UserService (8082)
    private final WebClient authClient;  // AuthService


    public RegistrationController(@Qualifier("userClient") WebClient userClient,
                                  @Qualifier("authClient") WebClient authClient) {
        this.userClient = userClient;
        this.authClient = authClient;
    }

    private Mono<Void> rollbackUser(Long userId) {
        return userClient.delete()
                .uri("/users/{id}", userId)
                .header("X-Service-Call", "true")
                .retrieve()
                .bodyToMono(Void.class)
                .doOnSuccess(v ->
                        log.warn("Rollback executed: user {} deleted", userId)
                )
                .onErrorResume(e -> {
                    log.error("Rollback failed for user {}", userId, e);
                    return Mono.empty(); // чтобы не затирать оригинальную ошибку
                });
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Map<String, Object>>>> register(
            @RequestBody Map<String, Object> body) {

        Map<String, Object> credentials = (Map<String, Object>) body.get("credentials");
        Map<String, Object> profile = (Map<String, Object>) body.get("profile");

        if (credentials == null || profile == null) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("error", Map.of("message", "credentials and profile required"))));
        }

        // 1) Создание пользователя напрямую на UserService
        return userClient.post()
                .uri("/users")
                .header("X-Service-Call", "true")  // сервисный вызов
                .bodyValue(profile)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                })
                .flatMap(userResp -> {
                    Long userId = Long.valueOf(userResp.get("id").toString());

                    // 2) тело запроса для AuthService
                    Map<String, Object> authRequest = new HashMap<>();
                    authRequest.put("userId", userId);
                    authRequest.put("username", credentials.get("username"));
                    authRequest.put("password", credentials.get("password"));
                    authRequest.put("role", credentials.getOrDefault("role", "USER"));

                    // 3) отправка на AuthService
                    return authClient.post()
                            .uri("/auth/register")
                            .bodyValue(authRequest)
                            .retrieve()
                            .bodyToMono(Void.class)

                            // rollback если AuthService упал
                            .onErrorResume(authErr ->
                                    rollbackUser(userId)
                                            .then(Mono.error(authErr))
                            )

                            .then(Mono.fromCallable(() -> {
                                Map<String, Map<String, Object>> result = new HashMap<>();
                                result.put("user", userResp);
                                result.put("auth", Map.of("status", "success"));
                                return ResponseEntity.status(201).body(result);
                            }));

                })
                .onErrorResume(err -> {
                    log.error("Registration failed: {}", err.getMessage());
                    Map<String, Map<String, Object>> errorBody = Map.of(
                            "error", Map.of("message", "registration failed", "detail", err.getMessage())
                    );
                    return Mono.just(ResponseEntity.status(500).body(errorBody));
                });
    }
}
