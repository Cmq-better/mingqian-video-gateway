package cn.videohub.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ApiKeyService {
   private static final SecureRandom RANDOM = new SecureRandom();
   private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N} ._-]{2,64}");
   private final ObjectMapper mapper;
   private final Map<String, ApiKeyService.StoredKey> keys = new LinkedHashMap<>();
   private final Map<String, ApiKeyService.Usage> usage = new ConcurrentHashMap<>();
   @Value("${video-hub.open-api.keys-file:./data/api-keys.json}")
   private String keysFile;
   @Value("${video-hub.open-api.requests-per-minute:120}")
   private int requestsPerMinute;

   public ApiKeyService(ObjectMapper mapper) {
      this.mapper = mapper.copy().findAndRegisterModules();
   }

   @PostConstruct
   synchronized void initialize() {
      Path path = this.path();
      if (Files.exists(path)) {
         try {
            for (ApiKeyService.StoredKey key : this.mapper.readValue(path.toFile(), new TypeReference<List<ApiKeyService.StoredKey>>() {})) {
               if (key.id() == null || key.tokenHash() == null || key.name() == null || key.scopes() == null) {
                  throw new IllegalStateException("API Key \u6587\u4ef6\u5305\u542b\u65e0\u6548\u8bb0\u5f55");
               }

               this.keys.put(key.id(), key);
            }
         } catch (Exception e) {
            throw new IllegalStateException("\u65e0\u6cd5\u8bfb\u53d6 API Key \u6587\u4ef6: " + path, e);
         }
      }
   }

   public synchronized ApiKeyService.IssuedKey create(String name, Set<ApiKeyService.Scope> requestedScopes) {
      String safeName = name == null ? "" : name.trim();
      if (!NAME.matcher(safeName).matches()) {
         throw new IllegalArgumentException(
            "\u5bc6\u94a5\u540d\u79f0\u957f\u5ea6\u9700\u4e3a2\u81f364\u4f4d\uff0c\u4e14\u4e0d\u80fd\u5305\u542b\u7279\u6b8a\u63a7\u5236\u5b57\u7b26"
         );
      }

      if (this.keys.size() >= 100) {
         throw new IllegalStateException("API Key \u6570\u91cf\u5df2\u8fbe\u4e0a\u9650");
      }

      EnumSet<ApiKeyService.Scope> scopes = requestedScopes != null && !requestedScopes.isEmpty()
         ? EnumSet.copyOf(requestedScopes)
         : EnumSet.of(ApiKeyService.Scope.READ);
      String id = UUID.randomUUID().toString();
      String raw = "vhk_" + id.substring(0, 8) + "_" + randomToken(32);
      Instant now = Instant.now();
      ApiKeyService.StoredKey stored = new ApiKeyService.StoredKey(
         id, safeName, AuthSessionService.sha256(raw), raw.substring(0, 13), Set.copyOf(scopes), true, now
      );
      this.keys.put(id, stored);
      this.persistOrRollback(id, null);
      return new ApiKeyService.IssuedKey(this.view(stored), raw);
   }

   public synchronized List<ApiKeyService.KeyView> list() {
      return this.keys.values().stream().sorted(Comparator.comparing(ApiKeyService.StoredKey::createdAt).reversed()).map(this::view).toList();
   }

   public synchronized ApiKeyService.KeyView setEnabled(String id, boolean enabled) {
      ApiKeyService.StoredKey old = this.require(id);
      ApiKeyService.StoredKey changed = new ApiKeyService.StoredKey(old.id(), old.name(), old.tokenHash(), old.prefix(), old.scopes(), enabled, old.createdAt());
      this.keys.put(id, changed);
      this.persistOrRollback(id, old);
      if (!enabled) {
         this.usage.remove(id);
      }

      return this.view(changed);
   }

   public synchronized void delete(String id) {
      ApiKeyService.StoredKey old = this.require(id);
      this.keys.remove(id);

      try {
         this.persist();
      } catch (RuntimeException e) {
         this.keys.put(id, old);
         throw e;
      }

      this.usage.remove(id);
   }

   public synchronized ApiKeyService.Authentication authenticate(String rawToken) {
      if (rawToken != null && rawToken.startsWith("vhk_") && rawToken.length() <= 160) {
         String hash = AuthSessionService.sha256(rawToken);
         ApiKeyService.StoredKey key = this.keys
            .values()
            .stream()
            .filter(candidate -> candidate.enabled() && AuthSessionService.constantEquals(candidate.tokenHash(), hash))
            .findFirst()
            .orElse(null);
         if (key == null) {
            return null;
         }

         ApiKeyService.Usage current = this.usage.computeIfAbsent(key.id(), ignored -> new ApiKeyService.Usage());
         int limit = Math.max(10, Math.min(10000, this.requestsPerMinute));
         synchronized (current) {
            long minute = System.currentTimeMillis() / 60000L;
            if (current.minute != minute) {
               current.minute = minute;
               current.count = 0;
            }

            if (++current.count > limit) {
               throw new ApiKeyService.RateLimitException();
            }

            current.total++;
            current.lastUsedAt = Instant.now();
         }

         return new ApiKeyService.Authentication(key.id(), key.name(), key.scopes());
      } else {
         return null;
      }
   }

   private ApiKeyService.KeyView view(ApiKeyService.StoredKey key) {
      ApiKeyService.Usage current = this.usage.get(key.id());
      return new ApiKeyService.KeyView(
         key.id(),
         key.name(),
         key.prefix(),
         key.scopes(),
         key.enabled(),
         key.createdAt(),
         current == null ? null : current.lastUsedAt,
         current == null ? 0L : current.total
      );
   }

   private ApiKeyService.StoredKey require(String id) {
      ApiKeyService.StoredKey key = this.keys.get(id);
      if (key == null) {
         throw new IllegalArgumentException("API Key \u4e0d\u5b58\u5728");
      } else {
         return key;
      }
   }

   private void persistOrRollback(String id, ApiKeyService.StoredKey previous) {
      try {
         this.persist();
      } catch (RuntimeException e) {
         if (previous == null) {
            this.keys.remove(id);
         } else {
            this.keys.put(id, previous);
         }

         throw e;
      }
   }

   private void persist() {
      Path target = this.path();

      try {
         if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
         }

         Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
         this.mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), new ArrayList<>(this.keys.values()));

         try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
         }
      } catch (Exception e) {
         throw new IllegalStateException("\u65e0\u6cd5\u4fdd\u5b58 API Key \u6587\u4ef6: " + target, e);
      }
   }

   private Path path() {
      return Path.of(this.keysFile).toAbsolutePath().normalize();
   }

   private static String randomToken(int bytes) {
      byte[] value = new byte[bytes];
      RANDOM.nextBytes(value);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
   }

   public record Authentication(String id, String name, Set<ApiKeyService.Scope> scopes) {
      public boolean has(ApiKeyService.Scope scope) {
         return this.scopes.contains(scope);
      }
   }

   public record IssuedKey(ApiKeyService.KeyView key, String token) {
   }

   public record KeyView(
      String id, String name, String prefix, Set<ApiKeyService.Scope> scopes, boolean enabled, Instant createdAt, Instant lastUsedAt, long requestCount
   ) {
   }

   public static final class RateLimitException extends RuntimeException {
   }

   public enum Scope {
      READ,
      PLAYBACK,
      CONTROL;
   }

   private record StoredKey(String id, String name, String tokenHash, String prefix, Set<ApiKeyService.Scope> scopes, boolean enabled, Instant createdAt) {
   }

   private static final class Usage {
      long minute;
      int count;
      long total;
      volatile Instant lastUsedAt;
   }
}
