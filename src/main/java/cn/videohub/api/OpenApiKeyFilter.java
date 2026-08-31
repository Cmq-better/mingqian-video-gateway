package cn.videohub.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-100)
public class OpenApiKeyFilter extends OncePerRequestFilter {
   public static final String ATTRIBUTE = "mingqian.open-api.key";
   private final ApiKeyService apiKeys;

   public OpenApiKeyFilter(ApiKeyService apiKeys) {
      this.apiKeys = apiKeys;
   }

   protected boolean shouldNotFilter(HttpServletRequest request) {
      String path = request.getRequestURI();
      boolean publicDocumentation = "GET".equalsIgnoreCase(request.getMethod()) && ("/open-api/v1".equals(path) || "/open-api/v1/spec".equals(path));
      return "OPTIONS".equalsIgnoreCase(request.getMethod())
         || publicDocumentation
         || !path.startsWith("/open-api/") && (!path.startsWith("/api/playbacks/") || !bearer(request).startsWith("vhk_"));
   }

   protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
      try {
         ApiKeyService.Authentication key = this.apiKeys.authenticate(bearer(request));
         if (key == null) {
            deny(response, 401, "API Key \u65e0\u6548\u6216\u5df2\u64a4\u9500");
            return;
         }

         ApiKeyService.Scope required = requiredScope(request);
         if (!key.has(required)) {
            deny(response, 403, "API Key \u7f3a\u5c11 " + required + " \u6743\u9650");
            return;
         }

         request.setAttribute("mingqian.open-api.key", key);
         request.setAttribute("mingqian.auth.session", "open-api:" + key.id());
         response.setHeader("X-RateLimit-Policy", "per-key-per-minute");
         chain.doFilter(request, response);
      } catch (ApiKeyService.RateLimitException e) {
         response.setHeader("Retry-After", "60");
         deny(response, 429, "\u8bf7\u6c42\u8fc7\u4e8e\u9891\u7e41\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5");
      }
   }

   private static ApiKeyService.Scope requiredScope(HttpServletRequest request) {
      String path = request.getRequestURI();
      if (path.contains("/ptz")) {
         return ApiKeyService.Scope.CONTROL;
      } else if (path.contains("/snapshot") && !path.contains("/snapshots/status")) {
         return ApiKeyService.Scope.PLAYBACK;
      } else {
         return !path.contains("/play") && !path.startsWith("/api/playbacks/") ? ApiKeyService.Scope.READ : ApiKeyService.Scope.PLAYBACK;
      }
   }

   private static String bearer(HttpServletRequest request) {
      String value = request.getHeader("Authorization");
      return value != null && value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7).trim() : "";
   }

   private static void deny(HttpServletResponse response, int status, String message) throws IOException {
      response.setStatus(status);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.setContentType("application/json");
      response.getWriter().write("{\"ok\":false,\"message\":\"" + message + "\"}");
   }
}
