package cn.videohub.api;

import cn.videohub.device.DeviceService;
import cn.videohub.media.FfmpegGateway;
import cn.videohub.media.MediaService;
import cn.videohub.media.PlaybackSessionService;
import cn.videohub.media.PlaybackTokenService;
import cn.videohub.media.SnapshotQueueService;
import cn.videohub.sip.Gb28181SipServer;
import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseEntity.BodyBuilder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ApiController {
   private final DeviceService devices;
   private final Gb28181SipServer sip;
   private final MediaService media;
   private final PlaybackSessionService playbacks;
   private final FfmpegGateway ffmpeg;
   private final PlaybackTokenService playbackTokens;
   private final SnapshotQueueService snapshots;
   @Value("${video-hub.platform-id}")
   private String platformId;
   @Value("${video-hub.sip-port}")
   private int sipPort;
   @Value("${video-hub.public-ip}")
   private String publicIp;
   @Value("${server.port:18080}")
   private int httpPort;
   @Value("${video-hub.public-base-url:}")
   private String configuredPlatformUrl;
   @Value("${video-hub.platform-domain}")
   private String platformDomain;
   @Value("${video-hub.sip-auth-enabled:false}")
   private boolean sipAuthEnabled;
   @Value("${video-hub.auth.password:${video-hub.admin-token:}}")
   private String authPassword;
   @Value("${video-hub.media.secret:}")
   private String mediaSecret;
   @Value("${video-hub.sip-password:}")
   private String sipPassword;

   public ApiController(
      DeviceService devices,
      Gb28181SipServer sip,
      MediaService media,
      PlaybackSessionService playbacks,
      FfmpegGateway ffmpeg,
      PlaybackTokenService playbackTokens,
      SnapshotQueueService snapshots
   ) {
      this.devices = devices;
      this.sip = sip;
      this.media = media;
      this.playbacks = playbacks;
      this.ffmpeg = ffmpeg;
      this.playbackTokens = playbackTokens;
      this.snapshots = snapshots;
   }

   @GetMapping("/dashboard")
   public Object dashboard() {
      return this.devices.dashboard();
   }

   @GetMapping("/devices")
   public Object devices() {
      return this.devices.views();
   }

   @PostMapping("/devices")
   public Object add(@RequestBody DeviceService.DeviceInput input) {
      DeviceService.Device saved = this.devices.upsertManual(input);
      return Map.of("id", saved.id(), "name", saved.name(), "status", saved.status(), "message", "\u8bbe\u5907\u5df2\u4fdd\u5b58");
   }

   @DeleteMapping("/devices/{deviceId}")
   public Object delete(@PathVariable String deviceId) {
      this.playbacks.stopDevice(deviceId, "device-delete");
      this.devices.delete(deviceId);
      return Map.of("ok", true, "message", "\u8bbe\u5907\u5df2\u5220\u9664");
   }

   @GetMapping("/devices/{deviceId}/channels")
   public Object channels(@PathVariable String deviceId) {
      return this.devices.channels(deviceId);
   }

   @GetMapping("/channels")
   public Object channelsPage(
      @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "30") int limit, @RequestParam(defaultValue = "") String q
   ) {
      return this.devices.channelPage(offset, limit, q);
   }

   @PostMapping("/devices/{deviceId}/diagnose")
   public Object diagnose(@PathVariable String deviceId) {
      DeviceService.Device device = this.devices.require(deviceId);
      if ("GB28181".equalsIgnoreCase(device.protocol())) {
         boolean online = this.devices.session(deviceId).isPresent();
         return new FfmpegGateway.ProbeResult(
            online,
            0L,
            online
               ? "GB28181 SIP \u4f1a\u8bdd\u5728\u7ebf\uff0c\u53ef\u6267\u884c\u76ee\u5f55\u67e5\u8be2\u3001\u70b9\u64ad\u548c\u4e91\u53f0\u63a7\u5236"
               : "\u8bbe\u5907\u6ca1\u6709\u5728\u7ebf SIP \u4f1a\u8bdd\uff0c\u8bf7\u68c0\u67e5\u6ce8\u518c\u4e0e\u5fc3\u8df3"
         );
      } else {
         FfmpegGateway.ProbeResult result = this.ffmpeg.probe(device);
         this.devices.markConnection(deviceId, result.online());
         return result;
      }
   }

   @PostMapping("/devices/{deviceId}/catalog")
   public Object catalog(@PathVariable String deviceId) {
      this.sip.sendCatalog(deviceId);
      return Map.of("ok", true, "message", "\u76ee\u5f55\u67e5\u8be2\u5df2\u53d1\u9001\uff0c\u8bf7\u7a0d\u540e\u5237\u65b0\u901a\u9053\u5217\u8868");
   }

   @PostMapping("/devices/{deviceId}/ptz")
   public Object ptz(@PathVariable String deviceId, @RequestBody ApiController.PtzRequest request) {
      this.sip.sendPtz(deviceId, required(request.channelId(), "\u901a\u9053 ID"), required(request.action(), "\u52a8\u4f5c"), request.speed());
      return Map.of("ok", true, "message", "PTZ \u6307\u4ee4\u5df2\u53d1\u9001");
   }

   @PostMapping("/devices/{deviceId}/play")
   public Object play(@PathVariable String deviceId, @RequestBody ApiController.PlayRequest request, HttpServletRequest httpRequest) {
      DeviceService.Device device = this.devices.require(deviceId);
      String channelId = request.channelId() != null && !request.channelId().isBlank() ? request.channelId().trim() : deviceId;
      if (device.streamUrl() != null && !device.streamUrl().isBlank()) {
         if (device.streamUrl().toLowerCase(Locale.ROOT).startsWith("rtsp://")) {
            this.playbacks.assertCapacity();
            LinkedHashMap<String, Object> result = new LinkedHashMap<>(this.media.proxy(device));
            String playbackId = this.playbacks.nextPlaybackId();
            String mode = String.valueOf(result.get("mode"));
            if ("ZLM_PROXY".equals(mode)) {
               this.playbacks.registerProxy(deviceId, channelId, playbackId, String.valueOf(result.get("key")), String.valueOf(result.get("stream")));
            } else if ("FFMPEG_HLS".equals(mode)) {
               this.playbacks.registerFfmpeg(deviceId, channelId, playbackId, String.valueOf(result.get("stream")));
            }

            result.put("playbackId", playbackId);
            PlaybackTokenService.Target target = "ZLM_PROXY".equals(mode)
               ? PlaybackTokenService.Target.zlm("proxy", String.valueOf(result.get("stream")))
               : PlaybackTokenService.Target.ffmpeg(String.valueOf(result.get("stream")));
            result.put("playbackToken", this.playbackTokens.issue(playbackId, httpRequest, target));
            result.put("hls", "/api/playbacks/" + playbackId + "/media/" + ("ZLM_PROXY".equals(mode) ? "hls.m3u8" : "index.m3u8"));
            result.remove("key");
            result.remove("stream");
            result.remove("flv");
            result.remove("fmp4");
            result.remove("webrtc");
            return result;
         } else {
            throw new IllegalStateException(
               "\u5b89\u5168\u64ad\u653e\u6a21\u5f0f\u7981\u6b62\u8fd4\u56de\u8bbe\u5907\u76f4\u8fde\u5730\u5740\uff1b\u8bf7\u5c06\u8be5\u89c6\u9891\u63a5\u5165 RTSP/GB28181 \u4e2d\u8f6c\u6216\u914d\u7f6e\u53d7\u63a7 HTTPS \u7f51\u5173"
            );
         }
      } else {
         channelId = required(channelId, "\u901a\u9053 ID");
         String streamType;

         int streamNumber = switch (streamType = request.streamType() == null ? "SUB" : request.streamType().trim().toUpperCase(Locale.ROOT)) {
            case "MAIN" -> 0;
            case "SUB" -> 1;
            case "THIRD" -> 2;
            default -> throw new IllegalArgumentException("\u7801\u6d41\u7c7b\u578b\u4ec5\u652f\u6301 MAIN\u3001SUB \u6216 THIRD");
         };
         this.playbacks.assertCapacity();
         String ssrc = this.sip.nextSsrc();
         int receivePort = this.media.openRtpServer(ssrc);

         try {
            this.sip.invite(deviceId, channelId, ssrc, receivePort, streamNumber);
            this.playbacks.registerGb(deviceId, channelId, ssrc, receivePort);
         } catch (RuntimeException e) {
            this.media.closeRtpServer(ssrc);
            throw e;
         }

         LinkedHashMap<String, Object> result = new LinkedHashMap<>();
         result.put("mode", "GB28181");
         result.put("ssrc", ssrc);
         result.put("playbackId", ssrc);
         result.put("rtpPort", receivePort);
         result.put("streamType", streamType);
         result.put("playbackToken", this.playbackTokens.issue(ssrc, httpRequest, PlaybackTokenService.Target.zlm("rtp", ssrc)));
         result.put("hls", "/api/playbacks/" + ssrc + "/media/hls.m3u8");
         return result;
      }
   }

   @PostMapping("/devices/{deviceId}/play/{playbackId}/heartbeat")
   public Object playbackHeartbeat(@PathVariable String deviceId, @PathVariable String playbackId) {
      boolean active = this.playbacks.heartbeat(deviceId, playbackId);
      return Map.of(
         "active",
         active,
         "message",
         active ? "\u64ad\u653e\u5fc3\u8df3\u5df2\u66f4\u65b0" : "\u64ad\u653e\u901a\u9053\u4e0d\u5b58\u5728\u6216\u5df2\u56de\u6536"
      );
   }

   @PostMapping("/devices/{deviceId}/play/{playbackId}/stop")
   public Object stopPlayback(@PathVariable String deviceId, @PathVariable String playbackId) {
      this.playbackTokens.revokePlayback(playbackId);
      return this.playbacks.stop(deviceId, playbackId, "viewer-stop");
   }

   @GetMapping(value = "/devices/{deviceId}/snapshot", produces = "image/jpeg")
   public ResponseEntity<byte[]> snapshot(@PathVariable String deviceId, @RequestParam(defaultValue = "") String channelId) {
      SnapshotQueueService.SnapshotResult result = this.snapshots.request(deviceId, channelId);
      return ((BodyBuilder)((BodyBuilder)((BodyBuilder)ResponseEntity.ok()
                  .contentType(MediaType.IMAGE_JPEG)
                  .header("X-Snapshot-Source", new String[]{result.source()}))
               .header("X-Snapshot-Cache", new String[]{result.cached() ? "HIT" : "MISS"}))
            .header("X-Snapshot-Queue-Wait-Ms", new String[]{Long.toString(result.queueWaitMillis())}))
         .body(result.image());
   }

   @GetMapping("/snapshots/status")
   public Object snapshotStatus() {
      return this.snapshots.status();
   }

   @GetMapping("/platform")
   public Object platform() {
      LinkedHashMap<String, Object> result = new LinkedHashMap<>();
      result.put("platformId", this.platformId);
      result.put("sip", "udp://" + this.publicIp + ":" + this.sipPort);
      Object platformUrl = this.configuredPlatformUrl == null ? "" : this.configuredPlatformUrl.trim().replaceAll("/+$", "");
      if (((String)platformUrl).isBlank()) {
         platformUrl = "http://" + this.publicIp + (this.httpPort == 80 ? "" : ":" + this.httpPort);
      }

      result.put("http", platformUrl);
      result.put("name", "Mingqian Video Gateway");
      result.put("media", this.mediaHealth());
      result.put("domain", this.platformDomain);
      result.put("sipAuthEnabled", this.sipAuthEnabled);
      result.put("activePlaybacks", this.playbacks.activeCount());
      result.put("playbackResources", this.playbacks.resourceStatus());
      result.put("snapshotQueue", this.snapshots.status());
      return result;
   }

   @GetMapping("/media/health")
   public Object mediaHealth() {
      return this.media.health();
   }

   @GetMapping("/playback/resources")
   public Object playbackResources() {
      return this.playbacks.resourceStatus();
   }

   @PutMapping("/playback/settings")
   public Object updatePlaybackSettings(@RequestBody PlaybackSessionService.PlaybackSettings settings) {
      return Map.of(
         "ok",
         true,
         "message",
         "\u64ad\u653e\u8d44\u6e90\u53c2\u6570\u5df2\u4fdd\u5b58\u5e76\u7acb\u5373\u751f\u6548",
         "settings",
         this.playbacks.updateSettings(settings),
         "resources",
         this.playbacks.resourceStatus()
      );
   }

   @GetMapping("/media/rtp/{ssrc}")
   public Object rtpStatus(@PathVariable String ssrc, @RequestParam(defaultValue = "0") int port) {
      return this.media.rtpStatus(ssrc, port);
   }

   @GetMapping("/platform/diagnostics")
   public Object diagnostics() {
      List<String> addresses = localIpv4();
      ArrayList<String> warnings = new ArrayList<>();
      if (this.publicIp.startsWith("127.")) {
         warnings.add(
            "GB_PUBLIC_IP \u5f53\u524d\u662f 127.0.0.1\uff0c\u8fdc\u7aef\u8bbe\u5907\u65e0\u6cd5\u6ce8\u518c\uff1b\u8bf7\u6539\u4e3a\u672c\u673a\u5c40\u57df\u7f51IP\u3001VPN\u5730\u5740\u6216\u516c\u7f51IP"
         );
      }

      if (!this.sipAuthEnabled) {
         warnings.add("GB28181 Digest \u9274\u6743\u5f53\u524d\u672a\u542f\u7528\uff1b\u6b63\u5f0f\u90e8\u7f72\u5efa\u8bae\u8bbe\u7f6e GB_AUTH_ENABLED=true");
      }

      if (this.sipAuthEnabled && (this.sipPassword == null || this.sipPassword.length() < 8)) {
         warnings.add(
            "GB28181 \u4ecd\u5728\u4f7f\u7528\u9ed8\u8ba4\u8ba4\u8bc1\u5bc6\u7801\uff1b\u516c\u7f51\u90e8\u7f72\u524d\u5fc5\u987b\u4fee\u6539 GB_DEVICE_PASSWORD"
         );
      }

      if (this.mediaSecret == null || this.mediaSecret.length() < 16) {
         warnings.add(
            "ZLMediaKit \u4ecd\u5728\u4f7f\u7528\u9ed8\u8ba4 API \u5bc6\u94a5\uff1b\u516c\u7f51\u90e8\u7f72\u524d\u5fc5\u987b\u540c\u6b65\u4fee\u6539 ZLM_SECRET \u4e0e ZLMediaKit \u914d\u7f6e"
         );
      }

      if (this.authPassword == null || this.authPassword.length() < 12) {
         warnings.add(
            "\u7ba1\u7406\u5458\u767b\u5f55\u5bc6\u7801\u5c1a\u672a\u5b89\u5168\u914d\u7f6e\uff1b\u8bf7\u8bbe\u7f6e\u81f3\u5c1112\u4f4d\u7684 PLATFORM_ADMIN_PASSWORD"
         );
      }

      Map<String, Object> mediaState;
      if (!"ONLINE".equals((mediaState = this.media.health()).get("status"))) {
         warnings.add("ZLMediaKit \u672a\u5728\u7ebf\uff1aRTSP \u53ef\u4f7f\u7528 FFmpeg\uff0cGB28181 RTP \u5b9e\u65f6\u6d41\u6682\u4e0d\u53ef\u64ad\u653e");
      }

      Map<String, Object> videoStorage = this.devices.storageStatus();
      if (!Boolean.TRUE.equals(videoStorage.get("writable"))) {
         warnings.add(
            "\u89c6\u9891\u8bbe\u5907\u6570\u636e\u76ee\u5f55\u4e0d\u53ef\u5199\uff0c\u91cd\u542f\u540e\u53ef\u80fd\u4e22\u5931\u65b0\u589e\u8bbe\u5907"
         );
      }

      LinkedHashMap<String, Object> result = new LinkedHashMap<>();
      result.put("ready", warnings.isEmpty());
      result.put("localAddresses", addresses);
      result.put("configuredPublicIp", this.publicIp);
      result.put("sipPort", this.sipPort);
      result.put("platformId", this.platformId);
      result.put("domain", this.platformDomain);
      result.put("authEnabled", this.sipAuthEnabled);
      result.put("media", mediaState);
      result.put("warnings", warnings);
      result.put("activePlaybacks", this.playbacks.activeCount());
      result.put("playbackResources", this.playbacks.resourceStatus());
      result.put("storage", Map.of("video", videoStorage));
      return result;
   }

   @GetMapping("/media/hls/{stream}/{filename:.+}")
   public ResponseEntity<Resource> hls(@PathVariable String stream, @PathVariable String filename) {
      Resource resource = this.ffmpeg.resource(stream, filename);
      MediaType type = filename.endsWith(".m3u8") ? MediaType.parseMediaType("application/vnd.apple.mpegurl") : MediaType.parseMediaType("video/mp2t");
      return ((BodyBuilder)ResponseEntity.ok().cacheControl(CacheControl.noStore())).contentType(type).body(resource);
   }

   @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
   public ResponseEntity<?> badRequest(RuntimeException e) {
      return ResponseEntity.badRequest().body(Map.of("ok", false, "message", e.getMessage()));
   }

   @ExceptionHandler(SnapshotQueueService.UnavailableException.class)
   public ResponseEntity<?> snapshotUnavailable(SnapshotQueueService.UnavailableException e) {
      return ((BodyBuilder)ResponseEntity.status(503).header("Retry-After", new String[]{"2"})).body(Map.of("ok", false, "message", e.getMessage()));
   }

   private static String required(String value, String label) {
      if (value != null && !value.isBlank()) {
         return value.trim();
      } else {
         throw new IllegalArgumentException(label + "\u4e0d\u80fd\u4e3a\u7a7a");
      }
   }

   private static List<String> localIpv4() {
      try {
         return Collections.list(NetworkInterface.getNetworkInterfaces())
            .stream()
            .filter(network -> {
               try {
                  return network.isUp() && !network.isLoopback();
               } catch (Exception e) {
                  return false;
               }
            })
            .flatMap(network -> Collections.list(network.getInetAddresses()).stream())
            .filter(address -> address instanceof Inet4Address && !address.isLoopbackAddress())
            .map(address -> address.getHostAddress())
            .distinct()
            .sorted()
            .toList();
      } catch (Exception e) {
         return List.of();
      }
   }

   public record PlayRequest(String channelId, String streamType) {
   }

   public record PtzRequest(String channelId, String action, int speed) {
   }
}
