package cn.videohub.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AdminTokenFilter extends OncePerRequestFilter {
   @Value("${video-hub.admin-token:}")
   private String configuredToken;
   private final AuthSessionService auth;

   public AdminTokenFilter(AuthSessionService auth) {
      this.auth = auth;
   }

   protected boolean shouldNotFilter(HttpServletRequest request) {
      String path = request.getRequestURI();
      String authorization = request.getHeader("Authorization");
      boolean openPlayback = path.startsWith("/api/playbacks/") && authorization != null && authorization.regionMatches(true, 0, "Bearer vhk_", 0, 11);
      return "OPTIONS".equalsIgnoreCase(request.getMethod())
         || "/api/auth/login".equals(path)
         || openPlayback
         || !path.startsWith("/api/") && !path.startsWith("/actuator/");
   }

   protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
      AuthSessionService.Session session = this.auth.authenticate(request);
      if (session != null) {
         request.setAttribute("mingqian.auth.session", session);
         if (changesState(request.getMethod()) && !this.auth.csrfValid(session, request.getHeader("X-CSRF-Token"))) {
            deny(response, 403, "\u5b89\u5168\u6821\u9a8c\u5931\u8d25\uff0c\u8bf7\u5237\u65b0\u9875\u9762\u540e\u91cd\u8bd5");
         } else if (requiresAdmin(request) && session.role() != UserAccountService.Role.ADMIN) {
            deny(response, 403, "\u5f53\u524d\u7528\u6237\u6ca1\u6709\u7ba1\u7406\u5458\u6743\u9650");
         } else {
            chain.doFilter(request, response);
         }
      } else {
         String supplied = request.getHeader("X-Admin-Token");
         String authorization;
         if ((supplied == null || supplied.isBlank())
            && (authorization = request.getHeader("Authorization")) != null
            && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            supplied = authorization.substring(7).trim();
         }

         if (this.configuredToken != null && !this.configuredToken.isBlank() && AuthSessionService.constantEquals(this.configuredToken, supplied)) {
            request.setAttribute("mingqian.auth.session", "api:" + AuthSessionService.sha256(supplied));
            chain.doFilter(request, response);
         } else {
            deny(response, 401, "\u767b\u5f55\u5df2\u5931\u6548\uff0c\u8bf7\u91cd\u65b0\u767b\u5f55");
         }
      }
   }

   private static boolean changesState(String method) {
      return !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method) && !"OPTIONS".equalsIgnoreCase(method);
   }

   private static boolean requiresAdmin(HttpServletRequest request) {
      String path = request.getRequestURI();
      if (path.startsWith("/actuator/") || path.startsWith("/api/users") || path.startsWith("/api/api-keys")) {
         return true;
      } else if (!changesState(request.getMethod())) {
         return false;
      } else {
         return !"/api/auth/logout".equals(path) && !"/api/auth/password".equals(path)
            ? !path.matches("^/api/devices/[^/]+/play(?:/[^/]+/(?:heartbeat|stop))?$")
            : false;
      }
   }

   private static void deny(HttpServletResponse response, int status, String message) throws IOException {
      response.setStatus(status);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.setContentType("application/json");
      response.getWriter().write("{\"ok\":false,\"message\":\"" + message + "\"}");
   }
}
