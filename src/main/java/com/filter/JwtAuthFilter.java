package com.filter;

import com.util.JwtUtil;
import io.jsonwebtoken.Claims;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class JwtAuthFilter implements GlobalFilter {

    private final JwtUtil jwtService;

    public JwtAuthFilter(JwtUtil jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        String path = exchange.getRequest().getURI().getPath();

        exchange.getRequest().getHeaders().forEach((key, value) -> System.out.println(key + " : " + value));

        // Публичные эндпоинты
        if (path.equals("/register") || path.startsWith("/auth")) {
            return chain.filter(exchange);
        }

        // Получаем Authorization
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {

            return unauthorized(exchange);
        }

        Claims claims;
        try {
            claims = jwtService.parse(authHeader.substring(7));
        } catch (Exception e) {
            System.out.println("JWT PARSE ERROR: " + e.getMessage());
            e.printStackTrace();
            return unauthorized(exchange);
        }
        //

        String userId = claims.getSubject();
        String rolesHeader;

        // Поддержка одной или нескольких ролей
        Object rolesClaim = claims.get("roles");
        if (rolesClaim instanceof List<?> rolesList) {
            rolesHeader = rolesList.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));
        } else if (claims.get("role") != null) {
            rolesHeader = claims.get("role").toString();
        } else {
            return unauthorized(exchange);
        }

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header("X-User-Id", userId)
                .header("X-User-Roles", rolesHeader)
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }
}
