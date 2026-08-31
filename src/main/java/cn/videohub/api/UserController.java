package cn.videohub.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {
   private final UserAccountService users;
   private final AuthSessionService auth;

   public UserController(UserAccountService users, AuthSessionService auth) {
      this.users = users;
      this.auth = auth;
   }

   @GetMapping
   public List<UserAccountService.UserView> list() {
      return this.users.list();
   }

   @PostMapping
   public ResponseEntity<?> create(@Valid @RequestBody UserController.CreateUserRequest body) {
      return ResponseEntity.status(201).body(this.users.create(body.username(), body.password(), body.role()));
   }

   @PutMapping("/{username}/password")
   public Map<String, Object> changePassword(@PathVariable String username, @Valid @RequestBody UserController.PasswordRequest body) {
      this.users.changePassword(username, body.password());
      this.auth.revokeUser(username);
      return Map.of("ok", true);
   }

   @PutMapping("/{username}/enabled")
   public UserAccountService.UserView setEnabled(
      @PathVariable String username, @Valid @RequestBody UserController.EnabledRequest body, HttpServletRequest request
   ) {
      AuthSessionService.Session current = AuthSessionService.sessionFrom(request);
      if (!body.enabled() && current != null && current.username().equalsIgnoreCase(username)) {
         throw new IllegalArgumentException("\u4e0d\u80fd\u505c\u7528\u5f53\u524d\u767b\u5f55\u7528\u6237");
      }

      UserAccountService.UserView changed = this.users.setEnabled(username, body.enabled());
      if (!body.enabled()) {
         this.auth.revokeUser(username);
      }

      return changed;
   }

   @PutMapping("/{username}/role")
   public UserAccountService.UserView setRole(@PathVariable String username, @Valid @RequestBody UserController.RoleRequest body, HttpServletRequest request) {
      AuthSessionService.Session current = AuthSessionService.sessionFrom(request);
      if (current != null && current.username().equalsIgnoreCase(username) && body.role() != UserAccountService.Role.ADMIN) {
         throw new IllegalArgumentException("\u4e0d\u80fd\u964d\u7ea7\u5f53\u524d\u767b\u5f55\u7528\u6237");
      }

      UserAccountService.UserView changed = this.users.setRole(username, body.role());
      this.auth.revokeUser(username);
      return changed;
   }

   @ExceptionHandler(IllegalArgumentException.class)
   public ResponseEntity<?> invalid(IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("ok", false, "message", e.getMessage()));
   }

   public record CreateUserRequest(
      @NotBlank @Size(min = 3, max = 64) String username, @NotBlank @Size(min = 12, max = 256) String password, UserAccountService.Role role
   ) {
   }

   public record EnabledRequest(@NotNull Boolean enabled) {
   }

   public record PasswordRequest(@NotBlank @Size(min = 12, max = 256) String password) {
   }

   public record RoleRequest(@NotNull UserAccountService.Role role) {
   }
}
