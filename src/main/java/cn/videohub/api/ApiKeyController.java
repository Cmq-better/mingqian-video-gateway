package cn.videohub.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/api-keys")
public class ApiKeyController {
   private final ApiKeyService apiKeys;

   public ApiKeyController(ApiKeyService apiKeys) {
      this.apiKeys = apiKeys;
   }

   @GetMapping
   public Object list() {
      return this.apiKeys.list();
   }

   @PostMapping
   public Object create(@Valid @RequestBody ApiKeyController.CreateRequest request) {
      return this.apiKeys.create(request.name(), request.scopes());
   }

   @PutMapping("/{id}/enabled")
   public Object enabled(@PathVariable String id, @RequestBody ApiKeyController.EnabledRequest request) {
      return this.apiKeys.setEnabled(id, request.enabled());
   }

   @DeleteMapping("/{id}")
   public Object delete(@PathVariable String id) {
      this.apiKeys.delete(id);
      return Map.of("ok", true);
   }

   @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
   public ResponseEntity<?> error(RuntimeException e) {
      return ResponseEntity.badRequest().body(Map.of("ok", false, "message", e.getMessage()));
   }

   public record CreateRequest(@NotBlank @Size(max = 64) String name, Set<ApiKeyService.Scope> scopes) {
   }

   public record EnabledRequest(boolean enabled) {
   }
}
