package cn.videohub.api;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AuthSessionService {
   public static final String AUTH_ATTRIBUTE = "mingqian.auth.session";
   public static final String COOKIE_NAME = "MQSESSION";
   private static final SecureRandom RANDOM = new SecureRandom();
   private final Map<String, AuthSessionService.Session> sessions = new ConcurrentHashMap<>();
   private final Map<String, AuthSessionService.LoginGuard> guards = new ConcurrentHashMap<>();
   private final UserAccountService users;
   @Value("${video-hub.auth.idle-minutes:30}")
   private long idleMinutes;
   @Value("${video-hub.auth.absolute-hours:8}")
   private long absoluteHours;
   @Value("${video-hub.auth.max-failures:5}")
   private int maxFailures;
   @Value("${video-hub.auth.lock-minutes:15}")
   private long lockMinutes;
   @Value("${video-hub.auth.secure-cookie:false}")
   private boolean forceSecureCookie;
   @Value("${video-hub.auth.max-sessions-per-user:5}")
   private int maxSessionsPerUser;
   @Value("${video-hub.auth.max-total-sessions:1000}")
   private int maxTotalSessions;

   public AuthSessionService(UserAccountService users) {
      this.users = users;
   }

   public AuthSessionService.LoginResult login(String username, String password, String remoteAddress, HttpServletResponse response, boolean requestSecure) {
      String remote = remoteAddress == null ? "unknown" : remoteAddress;
      String normalizedUsername = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
      if (this.guards.size() > 10000) {
         this.guards.clear();
      }

      remote = remote + "|" + sha256(normalizedUsername);
      AuthSessionService.LoginGuard guard;
      UserAccountService.UserPrincipal principal;
      synchronized (guard = this.guards.computeIfAbsent(remote, ignored -> new AuthSessionService.LoginGuard())) {
         if (guard.lockedUntil != null && guard.lockedUntil.isAfter(Instant.now())) {
            throw new IllegalStateException("\u767b\u5f55\u5931\u8d25\u6b21\u6570\u8fc7\u591a\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5");
         }

         principal = this.users.authenticate(normalizedUsername, password).orElse(null);
         if (principal == null) {
            guard.failures++;
            if (guard.failures >= Math.max(3, this.maxFailures)) {
               guard.lockedUntil = Instant.now().plus(Duration.ofMinutes(Math.max(1L, this.lockMinutes)));
               guard.failures = 0;
            }

            throw new IllegalArgumentException("\u7528\u6237\u540d\u6216\u5bc6\u7801\u9519\u8bef");
         }

         this.guards.remove(remote);
      }

      this.limitSessions(principal.username());
      String rawToken = randomToken(32);
      String key = sha256(rawToken);
      String csrf = randomToken(24);
      Instant now = Instant.now();
      Instant expiresAt = now.plus(Duration.ofHours(Math.max(1L, this.absoluteHours)));
      this.sessions.put(key, new AuthSessionService.Session(key, principal.username(), principal.role(), csrf, now, expiresAt));
      this.setCookie(response, rawToken, requestSecure, Math.toIntExact(Math.min(2147483647L, Duration.between(now, expiresAt).toSeconds())));
      return new AuthSessionService.LoginResult(principal.username(), principal.role(), csrf, expiresAt);
   }

   public AuthSessionService.Session authenticate(HttpServletRequest request) {
      String raw = cookie(request, "MQSESSION");
      if (raw != null && !raw.isBlank()) {
         AuthSessionService.Session session = this.sessions.get(sha256(raw));
         if (session == null) {
            return null;
         } else {
            Instant now = Instant.now();
            if (!session.expiresAt.isBefore(now)
               && !session.lastSeen.plus(Duration.ofMinutes(Math.max(1L, this.idleMinutes))).isBefore(now)
               && this.users.isActive(session.username, session.role)) {
               session.lastSeen = now;
               return session;
            } else {
               this.sessions.remove(session.key, session);
               return null;
            }
         }
      } else {
         return null;
      }
   }

   public void logout(HttpServletRequest request, HttpServletResponse response) {
      String raw = cookie(request, "MQSESSION");
      if (raw != null) {
         this.sessions.remove(sha256(raw));
      }

      this.setCookie(response, "", request.isSecure(), 0);
   }

   public boolean csrfValid(AuthSessionService.Session session, String supplied) {
      return session != null && constantEquals(session.csrf, supplied);
   }

   public String principal(HttpServletRequest request) {
      Object auth = request.getAttribute("mingqian.auth.session");
      String object;
      if (auth instanceof AuthSessionService.Session session) {
         object = "session:" + session.key;
      } else {
         object = String.valueOf(auth);
      }

      return object;
   }

   public void revokeUser(String username) {
      if (username != null) {
         this.sessions.values().removeIf(session -> session.username.equalsIgnoreCase(username));
      }
   }

   public static AuthSessionService.Session sessionFrom(HttpServletRequest request) {
      return request.getAttribute("mingqian.auth.session") instanceof AuthSessionService.Session session ? session : null;
   }

   private void limitSessions(String username) {
      int perUser = Math.max(1, this.maxSessionsPerUser);
      this.sessions
         .values()
         .stream()
         .filter(session -> session.username.equalsIgnoreCase(username))
         .sorted(Comparator.comparing(AuthSessionService.Session::createdAt))
         .limit(Math.max(0L, this.sessions.values().stream().filter(session -> session.username.equalsIgnoreCase(username)).count() - perUser + 1L))
         .toList()
         .forEach(session -> this.sessions.remove(session.key, session));
      int total = Math.max(10, this.maxTotalSessions);
      this.sessions
         .values()
         .stream()
         .sorted(Comparator.comparing(AuthSessionService.Session::createdAt))
         .limit(Math.max(0L, this.sessions.size() - total + 1L))
         .toList()
         .forEach(session -> this.sessions.remove(session.key, session));
   }

   @Scheduled(fixedDelay = 60000L)
   void reap() {
      Instant now = Instant.now();
      this.sessions
         .values()
         .removeIf(session -> session.expiresAt.isBefore(now) || session.lastSeen.plus(Duration.ofMinutes(Math.max(1L, this.idleMinutes))).isBefore(now));
      this.guards.values().removeIf(guard -> guard.lockedUntil != null && guard.lockedUntil.isBefore(now));
   }

   private void setCookie(HttpServletResponse response, String value, boolean requestSecure, int maxAge) {
      String secure = !this.forceSecureCookie && !requestSecure ? "" : "; Secure";
      response.addHeader("Set-Cookie", "MQSESSION=" + value + "; Path=/; Max-Age=" + maxAge + "; HttpOnly; SameSite=Strict; Priority=High" + secure);
   }

   private static String cookie(HttpServletRequest request, String name) {
      if (request.getCookies() == null) {
         return null;
      }

      for (Cookie cookie : request.getCookies()) {
         if (name.equals(cookie.getName())) {
            return cookie.getValue();
         }
      }

      return null;
   }

   public static boolean constantEquals(String expected, String actual) {
      return expected != null && actual != null
         ? MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))
         : false;
   }

   public static String sha256(String value) {
      try {
         return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
      } catch (Exception e) {
         throw new IllegalStateException(e);
      }
   }

   private static String randomToken(int bytes) {
      byte[] value = new byte[bytes];
      RANDOM.nextBytes(value);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
   }

   private static final class LoginGuard {
      private int failures;
      private Instant lockedUntil;
   }

   public record LoginResult(String username, UserAccountService.Role role, String csrfToken, Instant expiresAt) {
   }

   public static final class Session {
      private final String key;
      private final String username;
      private final UserAccountService.Role role;
      private final String csrf;
      private final Instant createdAt;
      private final Instant expiresAt;
      private volatile Instant lastSeen;

      private Session(String key, String username, UserAccountService.Role role, String csrf, Instant createdAt, Instant expiresAt) {
         this.key = key;
         this.username = username;
         this.role = role;
         this.csrf = csrf;
         this.createdAt = createdAt;
         this.expiresAt = expiresAt;
         this.lastSeen = createdAt;
      }

      public String username() {
         return this.username;
      }

      public String csrf() {
         return this.csrf;
      }

      public UserAccountService.Role role() {
         return this.role;
      }

      public Instant createdAt() {
         return this.createdAt;
      }

      public Instant expiresAt() {
         return this.expiresAt;
      }
   }
}
