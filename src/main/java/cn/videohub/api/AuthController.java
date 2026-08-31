package cn.videohub.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
   private final AuthSessionService auth;
   private final UserAccountService users;

   public AuthController(AuthSessionService auth, UserAccountService users) {
      this.auth = auth;
      this.users = users;
   }

   @PostMapping("/login")
   public Object login(@Valid @RequestBody AuthController.LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
      return this.auth.login(body.username(), body.password(), clientIp(request), response, request.isSecure());
   }

   @GetMapping("/session")
   public Object session(HttpServletRequest request) {
      return !(request.getAttribute("mingqian.auth.session") instanceof AuthSessionService.Session session)
         ? Map.of("username", "API\u4ee4\u724c", "csrfToken", "", "expiresAt", "")
         : Map.of("username", session.username(), "role", session.role(), "csrfToken", session.csrf(), "expiresAt", session.expiresAt());
   }

   @PostMapping("/logout")
   public Object logout(HttpServletRequest request, HttpServletResponse response) {
      this.auth.logout(request, response);
      return Map.of("ok", true);
   }

   @PostMapping("/password")
   public Object changePassword(@Valid @RequestBody AuthController.ChangePasswordRequest body, HttpServletRequest request, HttpServletResponse response) {
      AuthSessionService.Session session = AuthSessionService.sessionFrom(request);
      if (session == null) {
         throw new IllegalStateException("API\u4ee4\u724c\u4e0d\u80fd\u4fee\u6539\u7528\u6237\u5bc6\u7801");
      }

      this.users.changeOwnPassword(session.username(), body.currentPassword(), body.newPassword());
      this.auth.revokeUser(session.username());
      this.auth.logout(request, response);
      return Map.of("ok", true, "message", "\u5bc6\u7801\u5df2\u4fee\u6539\uff0c\u8bf7\u91cd\u65b0\u767b\u5f55");
   }

   @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
   public ResponseEntity<?> loginError(RuntimeException e) {
      return ResponseEntity.status(401).body(Map.of("ok", false, "message", e.getMessage()));
   }

   private static String clientIp(HttpServletRequest request) {
      return request.getRemoteAddr();
   }

   public record ChangePasswordRequest(@NotBlank @Size(max = 256) String currentPassword, @NotBlank @Size(min = 12, max = 256) String newPassword) {
   }

   public record LoginRequest(@NotBlank @Size(min = 3, max = 64) String username, @NotBlank @Size(max = 256) String password) {
   }
}
