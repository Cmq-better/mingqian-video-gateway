package cn.videohub.api;

import cn.videohub.device.DeviceService;
import cn.videohub.media.SnapshotQueueService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseEntity.BodyBuilder;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/open-api/v1")
public class OpenApiController {
   private final DeviceService devices;
   private final ApiController internal;
   @Value("${video-hub.public-base-url:}")
   private String publicBaseUrl;
   @Value("${video-hub.playback.token-idle-seconds:120}")
   private long playbackTokenIdleSeconds;

   public OpenApiController(DeviceService devices, ApiController internal) {
      this.devices = devices;
      this.internal = internal;
   }

   @GetMapping
   public Object index(HttpServletRequest request) {
      return Map.of(
         "name",
         "Mingqian Video Open API",
         "version",
         "v1",
         "baseUrl",
         this.baseUrl(request) + "/open-api/v1",
         "authentication",
         "Authorization: Bearer <API_KEY>",
         "documentation",
         this.baseUrl(request) + "/developers.html",
         "spec",
         this.baseUrl(request) + "/open-api/v1/spec"
      );
   }

   @GetMapping("/health")
   public Object health(HttpServletRequest request) {
      return Map.of("ok", true, "status", "UP", "time", Instant.now(), "baseUrl", this.baseUrl(request));
   }

   @GetMapping("/dashboard")
   public Object dashboard() {
      return this.devices.dashboard();
   }

   @GetMapping("/devices")
   public Object devices() {
      return this.devices.views();
   }

   @GetMapping("/devices/{deviceId}/channels")
   public Object channels(@PathVariable String deviceId) {
      return this.devices.channels(deviceId);
   }

   @GetMapping("/channels")
   public Object channels(@RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "30") int limit, @RequestParam(defaultValue = "") String q) {
      return this.devices.channelPage(offset, limit, q);
   }

   @GetMapping("/platform")
   public Object platform() {
      return this.internal.platform();
   }

   @GetMapping(value = "/devices/{deviceId}/snapshot", produces = "image/jpeg")
   public Object snapshot(@PathVariable String deviceId, @RequestParam(defaultValue = "") String channelId) {
      return this.internal.snapshot(deviceId, channelId);
   }

   @GetMapping("/snapshots/status")
   public Object snapshotStatus() {
      return this.internal.snapshotStatus();
   }

   @PostMapping("/devices/{deviceId}/ptz")
   public Object ptz(@PathVariable String deviceId, @Valid @RequestBody OpenApiController.PtzRequest request) {
      return this.internal.ptz(deviceId, new ApiController.PtzRequest(request.channelId(), request.action(), request.speed()));
   }

   @PostMapping("/devices/{deviceId}/play")
   public Object play(@PathVariable String deviceId, @Valid @RequestBody OpenApiController.PlayRequest request, HttpServletRequest httpRequest) {
      Object raw = this.internal.play(deviceId, new ApiController.PlayRequest(request.channelId(), request.streamType()), httpRequest);
      if (raw instanceof Map<?, ?> source) {
         LinkedHashMap result = new LinkedHashMap();
         source.forEach((key, value) -> result.put(String.valueOf(key), value));
         String root = this.baseUrl(httpRequest);
         String playbackId = String.valueOf(result.getOrDefault("playbackId", ""));
         String playbackToken = String.valueOf(result.getOrDefault("playbackToken", ""));
         String hls = String.valueOf(result.getOrDefault("hls", ""));
         if (!hls.isBlank()) {
            result.put("hls", absolute(root, hls));
         }

         result.put("mediaHeaders", Map.of("Authorization", "Bearer <SAME_API_KEY>", "X-Playback-Token", playbackToken));
         result.put("heartbeatUrl", root + "/open-api/v1/devices/" + deviceId + "/play/" + playbackId + "/heartbeat");
         result.put("stopUrl", root + "/open-api/v1/devices/" + deviceId + "/play/" + playbackId + "/stop");
         result.put("tokenIdleSeconds", Math.max(30L, this.playbackTokenIdleSeconds));
         return result;
      } else {
         return raw;
      }
   }

   @PostMapping("/devices/{deviceId}/play/{playbackId}/heartbeat")
   public Object heartbeat(@PathVariable String deviceId, @PathVariable String playbackId) {
      return this.internal.playbackHeartbeat(deviceId, playbackId);
   }

   @PostMapping("/devices/{deviceId}/play/{playbackId}/stop")
   public Object stop(@PathVariable String deviceId, @PathVariable String playbackId) {
      return this.internal.stopPlayback(deviceId, playbackId);
   }

   @GetMapping("/spec")
   public Object spec(HttpServletRequest request) {
      return OpenApiSpecFactory.create(this.baseUrl(request));
   }

   private String baseUrl(HttpServletRequest request) {
      if (this.publicBaseUrl != null && !this.publicBaseUrl.isBlank()) {
         return this.publicBaseUrl.replaceAll("/+$", "");
      }

      String scheme = request.getScheme();
      int port = request.getServerPort();
      boolean defaultPort = "http".equalsIgnoreCase(scheme) && port == 80 || "https".equalsIgnoreCase(scheme) && port == 443;
      return scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port) + request.getContextPath();
   }

   private static String absolute(String root, String value) {
      return value.matches("(?i)^https?://.*") ? value : root + (value.startsWith("/") ? value : "/" + value);
   }

   @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
   public ResponseEntity<?> error(RuntimeException e) {
      return ResponseEntity.badRequest().body(Map.of("ok", false, "message", e.getMessage()));
   }

   @ExceptionHandler(SnapshotQueueService.UnavailableException.class)
   public ResponseEntity<?> snapshotUnavailable(SnapshotQueueService.UnavailableException e) {
      return ((BodyBuilder)ResponseEntity.status(503).header("Retry-After", new String[]{"2"})).body(Map.of("ok", false, "message", e.getMessage()));
   }

   @ExceptionHandler(MethodArgumentNotValidException.class)
   public ResponseEntity<?> validation(MethodArgumentNotValidException e) {
      Map<String, String> fields = e.getBindingResult()
         .getFieldErrors()
         .stream()
         .collect(
            Collectors.toMap(
               error -> error.getField(),
               error -> error.getDefaultMessage() == null ? "\u53c2\u6570\u65e0\u6548" : error.getDefaultMessage(),
               (left, right) -> left,
               LinkedHashMap::new
            )
         );
      return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "\u8bf7\u6c42\u53c2\u6570\u6821\u9a8c\u5931\u8d25", "fields", fields));
   }

   public record PlayRequest(
      @Size(max = 128) String channelId,
      @Pattern(regexp = "(?i)MAIN|SUB|THIRD", message = "streamType \u4ec5\u652f\u6301 MAIN\u3001SUB \u6216 THIRD") String streamType
   ) {
   }

   public record PtzRequest(
      @NotBlank @Size(max = 128) String channelId,
      @NotBlank @Pattern(regexp = "(?i)UP|DOWN|LEFT|RIGHT|ZOOM_IN|ZOOM_OUT|STOP", message = "\u4e0d\u652f\u6301\u7684 PTZ \u52a8\u4f5c") String action,
      @Min(0L) @Max(255L) int speed
   ) {
   }
}
