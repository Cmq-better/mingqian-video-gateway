package cn.videohub.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Integer.MIN_VALUE)
public class SecurityHeadersFilter extends OncePerRequestFilter {
   protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
      response.setHeader("X-Content-Type-Options", "nosniff");
      response.setHeader("X-Frame-Options", "DENY");
      response.setHeader("Referrer-Policy", "no-referrer");
      response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
      response.setHeader(
         "Content-Security-Policy",
         "default-src 'self'; script-src 'self' https://cdn.jsdelivr.net 'unsafe-inline'; style-src 'self'; img-src 'self' data: blob:; connect-src 'self'; media-src 'self' blob:; worker-src blob:; frame-ancestors 'none'"
      );
      if (request.getRequestURI().startsWith("/api/") || request.getRequestURI().endsWith("login.html")) {
         response.setHeader("Cache-Control", "no-store");
      }

      chain.doFilter(request, response);
   }
}
