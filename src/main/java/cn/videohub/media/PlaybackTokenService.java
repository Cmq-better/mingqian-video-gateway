package cn.videohub.media;

import cn.videohub.api.AuthSessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class PlaybackTokenService {
   private static final SecureRandom RANDOM = new SecureRandom();
   private final Map<String, PlaybackTokenService.Grant> grants = new ConcurrentHashMap<>();
   private final AuthSessionService auth;
   @Value("${video-hub.playback.token-idle-seconds:120}")
   private long idleSeconds;

   public PlaybackTokenService(AuthSessionService auth) {
      this.auth = auth;
   }

   public String issue(String playbackId, HttpServletRequest request, PlaybackTokenService.Target target) {
      String raw = token();
      String hash = AuthSessionService.sha256(raw);
      this.grants.put(hash, new PlaybackTokenService.Grant(playbackId, this.auth.principal(request), target, Instant.now()));
      return raw;
   }

   public PlaybackTokenService.Target validate(String playbackId, String rawToken, HttpServletRequest request) {
      if (rawToken != null && !rawToken.isBlank()) {
         PlaybackTokenService.Grant grant = this.grants.get(AuthSessionService.sha256(rawToken));
         Instant now = Instant.now();
         if (grant != null
            && grant.playbackId.equals(playbackId)
            && AuthSessionService.constantEquals(grant.principal, this.auth.principal(request))
            && !grant.lastUsed.plus(Duration.ofSeconds(Math.max(30L, this.idleSeconds))).isBefore(now)) {
            grant.lastUsed = now;
            return grant.target;
         }

         if (grant != null) {
            this.grants.remove(AuthSessionService.sha256(rawToken), grant);
         }

         throw new SecurityException("\u76f4\u64ad\u64ad\u653e\u4ee4\u724c\u65e0\u6548\u6216\u5df2\u8fc7\u671f");
      } else {
         throw new SecurityException("\u7f3a\u5c11\u76f4\u64ad\u64ad\u653e\u4ee4\u724c");
      }
   }

   public void revokePlayback(String playbackId) {
      this.grants.values().removeIf(grant -> grant.playbackId.equals(playbackId));
   }

   @Scheduled(fixedDelay = 60000L)
   void reap() {
      Instant deadline = Instant.now().minusSeconds(Math.max(30L, this.idleSeconds));
      this.grants.values().removeIf(grant -> grant.lastUsed.isBefore(deadline));
   }

   private static String token() {
      byte[] bytes = new byte[32];
      RANDOM.nextBytes(bytes);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
   }

   private static final class Grant {
      private final String playbackId;
      private final String principal;
      private final PlaybackTokenService.Target target;
      private volatile Instant lastUsed;

      private Grant(String playbackId, String principal, PlaybackTokenService.Target target, Instant lastUsed) {
         this.playbackId = playbackId;
         this.principal = principal;
         this.target = target;
         this.lastUsed = lastUsed;
      }
   }

   public record Target(PlaybackTokenService.Type type, String app, String stream) {
      public static PlaybackTokenService.Target zlm(String app, String stream) {
         return new PlaybackTokenService.Target(PlaybackTokenService.Type.ZLM, app, stream);
      }

      public static PlaybackTokenService.Target ffmpeg(String stream) {
         return new PlaybackTokenService.Target(PlaybackTokenService.Type.FFMPEG, "", stream);
      }
   }

   public enum Type {
      ZLM,
      FFMPEG;
   }
}
