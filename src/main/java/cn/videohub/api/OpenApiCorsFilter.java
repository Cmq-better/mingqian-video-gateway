package cn.videohub.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Integer.MIN_VALUE)
public class OpenApiCorsFilter extends OncePerRequestFilter {
   @Value("${video-hub.open-api.allowed-origins:}")
   private String configuredOrigins;

   protected boolean shouldNotFilter(HttpServletRequest request) {
      String path = request.getRequestURI();
      return !path.startsWith("/open-api/") && !path.startsWith("/api/playbacks/");
   }

   protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
      String origin = request.getHeader("Origin");
      Set<String> allowed = this.origins();
      if (origin != null && (allowed.contains("*") || allowed.contains(origin))) {
         response.setHeader("Access-Control-Allow-Origin", allowed.contains("*") ? "*" : origin);
         response.setHeader("Vary", "Origin");
         response.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
         response.setHeader("Access-Control-Allow-Headers", "Authorization,Content-Type,X-Playback-Token");
         response.setHeader("Access-Control-Expose-Headers", "X-RateLimit-Policy,Retry-After,Content-Type");
         response.setHeader("Access-Control-Max-Age", "600");
      }

      if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
         response.setStatus(204);
      } else {
         chain.doFilter(request, response);
      }
   }

   private Set<String> origins() {
      return Arrays.stream(this.configuredOrigins == null ? new String[0] : this.configuredOrigins.split(","))
         .map(String::trim)
         .filter(value -> !value.isBlank())
         .collect(Collectors.toUnmodifiableSet());
   }
}
