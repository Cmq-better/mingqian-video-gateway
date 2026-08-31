package cn.videohub.media;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseEntity.BodyBuilder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/playbacks")
public class SecureMediaController {
   private final PlaybackTokenService tokens;
   private final PlaybackSessionService playbacks;
   private final MediaService media;
   private final FfmpegGateway ffmpeg;

   public SecureMediaController(PlaybackTokenService tokens, PlaybackSessionService playbacks, MediaService media, FfmpegGateway ffmpeg) {
      this.tokens = tokens;
      this.playbacks = playbacks;
      this.media = media;
      this.ffmpeg = ffmpeg;
   }

   @GetMapping("/{playbackId}/media/{*filename}")
   public ResponseEntity<?> media(
      @PathVariable String playbackId,
      @PathVariable String filename,
      @RequestHeader(value = "X-Playback-Token", required = false) String token,
      HttpServletRequest request
   ) {
      if (!this.playbacks.isActive(playbackId)) {
         throw new SecurityException("\u64ad\u653e\u901a\u9053\u5df2\u5173\u95ed");
      }

      String safeFilename = MediaService.normalizeMediaPath(filename);
      PlaybackTokenService.Target target = this.tokens.validate(playbackId, token, request);
      MediaType type = contentType(safeFilename);
      if (target.type() == PlaybackTokenService.Type.FFMPEG) {
         Resource resource = this.ffmpeg.resource(target.stream(), safeFilename);
         return ((BodyBuilder)ResponseEntity.ok().cacheControl(CacheControl.noStore())).contentType(type).body(resource);
      }

      MediaService.MediaPayload payload = this.media.fetchMedia(target.app(), target.stream(), safeFilename);
      byte[] body = payload.body();
      if (safeFilename.endsWith(".m3u8")) {
         String manifest = new String(body, StandardCharsets.UTF_8);
         if (manifest.lines().map(String::trim).anyMatch(line -> line.matches("(?i)^https?://.*"))) {
            throw new SecurityException("\u5a92\u4f53\u6e05\u5355\u5305\u542b\u672a\u53d7\u4fdd\u62a4\u7684\u5916\u90e8\u5730\u5740");
         }

         body = manifest.getBytes(StandardCharsets.UTF_8);
      }

      return ((BodyBuilder)ResponseEntity.ok().cacheControl(CacheControl.noStore())).contentType(type).body(body);
   }

   @ExceptionHandler(SecurityException.class)
   public ResponseEntity<?> unauthorized(SecurityException e) {
      return ResponseEntity.status(401).body(Map.of("ok", false, "message", e.getMessage()));
   }

   private static MediaType contentType(String filename) {
      if (filename.endsWith(".m3u8")) {
         return MediaType.parseMediaType("application/vnd.apple.mpegurl");
      } else if (filename.endsWith(".ts")) {
         return MediaType.parseMediaType("video/mp2t");
      } else {
         return !filename.endsWith(".m4s") && !filename.endsWith(".mp4") ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType("video/mp4");
      }
   }
}
