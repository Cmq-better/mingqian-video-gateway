package cn.videohub.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class UserAccountService {
   private static final Logger log = LoggerFactory.getLogger(UserAccountService.class);
   private static final SecureRandom RANDOM = new SecureRandom();
   private static final Pattern USERNAME = Pattern.compile("[a-zA-Z0-9._-]{3,64}");
   private static final int SALT_BYTES = 16;
   private static final int HASH_BITS = 256;
   private static final byte[] DUMMY_SALT = "video-hub-login".getBytes(StandardCharsets.UTF_8);
   private static final byte[] DUMMY_HASH = new byte[32];
   private final ObjectMapper mapper;
   private final Map<String, UserAccountService.StoredUser> users = new LinkedHashMap<>();
   @Value("${video-hub.auth.users-file:./data/users.json}")
   private String usersFile;
   @Value("${video-hub.auth.pbkdf2-iterations:210000}")
   private int configuredIterations;
   @Value("${video-hub.auth.username:admin}")
   private String bootstrapUsername;
   @Value("${video-hub.auth.password:${video-hub.admin-token:}}")
   private String bootstrapPassword;

   public UserAccountService(ObjectMapper mapper) {
      this.mapper = mapper.copy().findAndRegisterModules();
   }

   @PostConstruct
   synchronized void initialize() {
      this.load();
      if (this.users.isEmpty()) {
         if (this.bootstrapPassword != null && this.bootstrapPassword.length() >= 12) {
            String username = normalizeAndValidateUsername(this.bootstrapUsername);
            Instant now = Instant.now();
            this.users.put(username, this.passwordRecord(username, this.bootstrapPassword, UserAccountService.Role.ADMIN, true, now, now));
            this.persist();
            log.warn("Created bootstrap administrator '{}'. Remove PLATFORM_ADMIN_PASSWORD after verifying login.", username);
         } else {
            log.error("No users exist. Set PLATFORM_ADMIN_PASSWORD (at least 12 characters) for first startup.");
         }
      }
   }

   public synchronized Optional<UserAccountService.UserPrincipal> authenticate(String username, String password) {
      String normalized = normalizeForLookup(username);
      UserAccountService.StoredUser stored = this.users.get(normalized);
      byte[] salt = stored == null ? DUMMY_SALT : decode(stored.passwordSalt());
      int iterations = stored == null ? this.iterations() : stored.passwordIterations();
      byte[] expected = stored == null ? DUMMY_HASH : decode(stored.passwordHash());
      byte[] actual = derive(password == null ? new char[0] : password.toCharArray(), salt, iterations);
      boolean valid = MessageDigest.isEqual(expected, actual);
      return valid && stored != null && stored.enabled()
         ? Optional.of(new UserAccountService.UserPrincipal(stored.username(), stored.role()))
         : Optional.empty();
   }

   public synchronized boolean isActive(String username, UserAccountService.Role role) {
      UserAccountService.StoredUser user = this.users.get(normalizeForLookup(username));
      return user != null && user.enabled() && user.role() == role;
   }

   public synchronized List<UserAccountService.UserView> list() {
      return this.users
         .values()
         .stream()
         .sorted(Comparator.comparing(UserAccountService.StoredUser::username))
         .map(user -> new UserAccountService.UserView(user.username(), user.role(), user.enabled(), user.createdAt(), user.updatedAt()))
         .toList();
   }

   public synchronized UserAccountService.UserView create(String username, String password, UserAccountService.Role role) {
      String normalized = normalizeAndValidateUsername(username);
      validatePassword(password);
      if (this.users.containsKey(normalized)) {
         throw new IllegalArgumentException("\u7528\u6237\u5df2\u5b58\u5728");
      }

      UserAccountService.Role safeRole = role == null ? UserAccountService.Role.VIEWER : role;
      Instant now = Instant.now();
      UserAccountService.StoredUser user = this.passwordRecord(normalized, password, safeRole, true, now, now);
      this.users.put(normalized, user);
      this.persistOrRollback(normalized, null);
      return new UserAccountService.UserView(user.username(), user.role(), user.enabled(), user.createdAt(), user.updatedAt());
   }

   public synchronized void changePassword(String username, String password) {
      String normalized = normalizeForLookup(username);
      validatePassword(password);
      UserAccountService.StoredUser old = this.require(normalized);
      UserAccountService.StoredUser changed = this.passwordRecord(old.username(), password, old.role(), old.enabled(), old.createdAt(), Instant.now());
      this.users.put(normalized, changed);
      this.persistOrRollback(normalized, old);
   }

   public synchronized void changeOwnPassword(String username, String currentPassword, String newPassword) {
      if (this.authenticate(username, currentPassword).isEmpty()) {
         throw new IllegalArgumentException("\u5f53\u524d\u5bc6\u7801\u9519\u8bef");
      }

      this.changePassword(username, newPassword);
   }

   public synchronized UserAccountService.UserView setEnabled(String username, boolean enabled) {
      String normalized = normalizeForLookup(username);
      UserAccountService.StoredUser old = this.require(normalized);
      if (!enabled && old.role() == UserAccountService.Role.ADMIN && this.enabledAdminCount() <= 1) {
         throw new IllegalArgumentException("\u4e0d\u80fd\u505c\u7528\u6700\u540e\u4e00\u4e2a\u7ba1\u7406\u5458");
      }

      UserAccountService.StoredUser changed = new UserAccountService.StoredUser(
         old.username(), old.passwordSalt(), old.passwordHash(), old.passwordIterations(), old.role(), enabled, old.createdAt(), Instant.now()
      );
      this.users.put(normalized, changed);
      this.persistOrRollback(normalized, old);
      return view(changed);
   }

   public synchronized UserAccountService.UserView setRole(String username, UserAccountService.Role role) {
      String normalized = normalizeForLookup(username);
      UserAccountService.StoredUser old = this.require(normalized);
      UserAccountService.Role safeRole = role == null ? UserAccountService.Role.VIEWER : role;
      if (old.role() == UserAccountService.Role.ADMIN && safeRole != UserAccountService.Role.ADMIN && old.enabled() && this.enabledAdminCount() <= 1) {
         throw new IllegalArgumentException("\u4e0d\u80fd\u964d\u7ea7\u6700\u540e\u4e00\u4e2a\u7ba1\u7406\u5458");
      }

      UserAccountService.StoredUser changed = new UserAccountService.StoredUser(
         old.username(), old.passwordSalt(), old.passwordHash(), old.passwordIterations(), safeRole, old.enabled(), old.createdAt(), Instant.now()
      );
      this.users.put(normalized, changed);
      this.persistOrRollback(normalized, old);
      return view(changed);
   }

   private UserAccountService.StoredUser passwordRecord(
      String username, String password, UserAccountService.Role role, boolean enabled, Instant createdAt, Instant updatedAt
   ) {
      validatePassword(password);
      byte[] salt = new byte[16];
      RANDOM.nextBytes(salt);
      int iterations = this.iterations();
      byte[] hash = derive(password.toCharArray(), salt, iterations);
      return new UserAccountService.StoredUser(username, encode(salt), encode(hash), iterations, role, enabled, createdAt, updatedAt);
   }

   private int enabledAdminCount() {
      return (int)this.users.values().stream().filter(user -> user.enabled() && user.role() == UserAccountService.Role.ADMIN).count();
   }

   private UserAccountService.StoredUser require(String normalized) {
      UserAccountService.StoredUser user = this.users.get(normalized);
      if (user == null) {
         throw new IllegalArgumentException("\u7528\u6237\u4e0d\u5b58\u5728");
      } else {
         return user;
      }
   }

   private static UserAccountService.UserView view(UserAccountService.StoredUser user) {
      return new UserAccountService.UserView(user.username(), user.role(), user.enabled(), user.createdAt(), user.updatedAt());
   }

   private int iterations() {
      return Math.max(100000, Math.min(2000000, this.configuredIterations));
   }

   private static void validatePassword(String password) {
      if (password == null || password.length() < 12 || password.length() > 256) {
         throw new IllegalArgumentException("\u5bc6\u7801\u957f\u5ea6\u5fc5\u987b\u4e3a12\u81f3256\u4e2a\u5b57\u7b26");
      }
   }

   private static String normalizeAndValidateUsername(String username) {
      String normalized = normalizeForLookup(username);
      if (!USERNAME.matcher(normalized).matches()) {
         throw new IllegalArgumentException(
            "\u7528\u6237\u540d\u53ea\u80fd\u5305\u542b\u5b57\u6bcd\u3001\u6570\u5b57\u3001\u70b9\u3001\u4e0b\u5212\u7ebf\u548c\u8fde\u5b57\u7b26\uff0c\u957f\u5ea63\u81f364\u4f4d"
         );
      } else {
         return normalized;
      }
   }

   private static String normalizeForLookup(String username) {
      return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
   }

   private static byte[] derive(char[] password, byte[] salt, int iterations) {
      PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);

      try {
         return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
      } catch (Exception e) {
         throw new IllegalStateException("\u65e0\u6cd5\u8ba1\u7b97\u5bc6\u7801\u54c8\u5e0c", e);
      } finally {
         spec.clearPassword();
         Arrays.fill(password, '\u0000');
      }
   }

   private void load() {
      Path path = this.path();
      if (Files.exists(path)) {
         try {
            for (UserAccountService.StoredUser user : this.mapper.readValue(path.toFile(), new TypeReference<List<UserAccountService.StoredUser>>() {})) {
               String normalized = normalizeAndValidateUsername(user.username());
               if (user.passwordIterations() < 100000
                  || user.passwordIterations() > 2000000
                  || user.role() == null
                  || user.passwordSalt() == null
                  || user.passwordHash() == null) {
                  throw new IllegalStateException("\u7528\u6237\u6587\u4ef6\u5305\u542b\u4e0d\u5b89\u5168\u6216\u65e0\u6548\u8bb0\u5f55");
               }

               decode(user.passwordSalt());
               decode(user.passwordHash());
               if (this.users.containsKey(normalized)) {
                  throw new IllegalStateException("\u7528\u6237\u6587\u4ef6\u5305\u542b\u91cd\u590d\u7528\u6237\u540d");
               }

               this.users.put(normalized, user);
            }
         } catch (Exception e) {
            throw new IllegalStateException("\u65e0\u6cd5\u8bfb\u53d6\u7528\u6237\u6587\u4ef6: " + path, e);
         }
      }
   }

   private void persistOrRollback(String username, UserAccountService.StoredUser previous) {
      try {
         this.persist();
      } catch (RuntimeException e) {
         if (previous == null) {
            this.users.remove(username);
         } else {
            this.users.put(username, previous);
         }

         throw e;
      }
   }

   private void persist() {
      Path target = this.path();
      Path parent = target.getParent();

      try {
         if (parent != null) {
            Files.createDirectories(parent);
         }

         Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
         this.mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), new ArrayList<>(this.users.values()));

         try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
         }
      } catch (Exception e) {
         throw new IllegalStateException("\u65e0\u6cd5\u4fdd\u5b58\u7528\u6237\u6587\u4ef6: " + target, e);
      }
   }

   private Path path() {
      return Path.of(this.usersFile).toAbsolutePath().normalize();
   }

   private static String encode(byte[] value) {
      return Base64.getEncoder().encodeToString(value);
   }

   private static byte[] decode(String value) {
      try {
         return Base64.getDecoder().decode(value);
      } catch (IllegalArgumentException e) {
         throw new IllegalStateException("\u7528\u6237\u5bc6\u7801\u54c8\u5e0c\u683c\u5f0f\u65e0\u6548", e);
      }
   }

   public enum Role {
      ADMIN,
      VIEWER;
   }

   private record StoredUser(
      String username,
      String passwordSalt,
      String passwordHash,
      int passwordIterations,
      UserAccountService.Role role,
      boolean enabled,
      Instant createdAt,
      Instant updatedAt
   ) {
   }

   public record UserPrincipal(String username, UserAccountService.Role role) {
   }

   public record UserView(String username, UserAccountService.Role role, boolean enabled, Instant createdAt, Instant updatedAt) {
   }
}
