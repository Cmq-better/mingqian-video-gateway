package cn.videohub.media;

import cn.videohub.device.DeviceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MediaService {
   private final ObjectMapper mapper;
   private final FfmpegGateway ffmpeg;
   private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2L)).build();
   @Value("${video-hub.media.host:127.0.0.1}")
   private String host;
   @Value("${video-hub.media.http-port:18081}")
   private int port;
   @Value("${video-hub.media.rtsp-port:554}")
   private int rtspPort;
   @Value("${video-hub.media.secret:}")
   private String secret;
   @Value("${video-hub.media.public-base-url:}")
   private String configuredPublicBaseUrl;

   public MediaService(ObjectMapper mapper, FfmpegGateway ffmpeg) {
      this.mapper = mapper;
      this.ffmpeg = ffmpeg;
   }

   public Map<String, Object> health() {
      try {
         JsonNode response = this.getJson("/index/api/getServerConfig", Map.of("secret", this.secret));
         boolean online = response.path("code").asInt(-1) == 0;
         return Map.of(
            "status",
            online ? "ONLINE" : "OFFLINE",
            "endpoint",
            this.endpoint(),
            "message",
            online ? "\u5a92\u4f53\u8282\u70b9\u8fd0\u884c\u6b63\u5e38" : response.path("msg").asText("\u5a92\u4f53\u8282\u70b9\u8fd4\u56de\u5f02\u5e38")
         );
      } catch (Exception e) {
         return this.ffmpeg.available()
            ? Map.of(
               "status",
               "FALLBACK",
               "endpoint",
               "\u672c\u673a FFmpeg",
               "message",
               "ZLMediaKit \u672a\u8fde\u63a5\uff0cRTSP \u5c06\u4f7f\u7528 FFmpeg \u517c\u5bb9\u4e2d\u8f6c"
            )
            : Map.of("status", "OFFLINE", "endpoint", this.endpoint(), "message", friendly(e));
      }
   }

   public Map<String, Object> proxy(DeviceService.Device device) {
      String source = device.streamUrl();
      if (source != null && !source.isBlank()) {
         if (!source.toLowerCase(Locale.ROOT).startsWith("rtsp://")) {
            throw new IllegalArgumentException("\u53ea\u6709 RTSP \u5730\u5740\u9700\u8981\u521b\u5efa\u5a92\u4f53\u4ee3\u7406");
         }

         String stream = safeStreamId(device.id()) + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
         LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
         parameters.put("secret", this.secret);
         parameters.put("vhost", "__defaultVhost__");
         parameters.put("app", "proxy");
         parameters.put("stream", stream);
         parameters.put("url", source);
         parameters.put("retry_count", "3");
         parameters.put("rtp_type", "0");
         parameters.put("enable_hls", "1");
         parameters.put("enable_fmp4", "1");
         parameters.put("enable_rtsp", "1");
         parameters.put("enable_rtmp", "1");

         try {
            JsonNode response = this.getJson("/index/api/addStreamProxy", parameters);
            if (response.path("code").asInt(-1) != 0) {
               throw new IllegalStateException("\u5a92\u4f53\u8282\u70b9\u62c9\u6d41\u5931\u8d25: " + response.path("msg").asText("\u672a\u77e5\u9519\u8bef"));
            }

            String base = this.publicBase();
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("mode", "ZLM_PROXY");
            result.put("stream", stream);
            result.put("key", response.path("data").path("key").asText());
            result.put("hls", base + "/proxy/" + stream + "/hls.m3u8");
            result.put("flv", base + "/proxy/" + stream + ".live.flv");
            result.put("fmp4", base + "/proxy/" + stream + ".live.mp4");
            result.put("webrtc", base + "/index/api/webrtc?app=proxy&stream=" + stream + "&type=play");
            result.put("message", "RTSP \u5a92\u4f53\u4ee3\u7406\u5df2\u5efa\u7acb");
            return result;
         } catch (IllegalStateException e) {
            return this.ffmpeg.start(device);
         } catch (Exception e) {
            return this.ffmpeg.start(device);
         }
      } else {
         throw new IllegalArgumentException("\u8bbe\u5907\u6ca1\u6709\u914d\u7f6e RTSP \u5730\u5740");
      }
   }

   public byte[] snapshot(DeviceService.Device device) {
      String source = device.streamUrl();
      if (source != null && !source.isBlank()) {
         try {
            HttpRequest request = HttpRequest.newBuilder(
                  this.buildUri("/index/api/getSnap", Map.of("secret", this.secret, "url", source, "timeout_sec", "10", "expire_sec", "1"))
               )
               .timeout(Duration.ofSeconds(12L))
               .GET()
               .build();
            HttpResponse<byte[]> response = this.client.send(request, BodyHandlers.ofByteArray());
            if (response.statusCode() == 200 && response.body().length >= 100) {
               return response.body();
            } else {
               throw new IllegalStateException("\u5a92\u4f53\u8282\u70b9\u622a\u56fe\u5931\u8d25");
            }
         } catch (IllegalStateException e) {
            return this.ffmpeg.snapshot(device);
         } catch (Exception e) {
            return this.ffmpeg.snapshot(device);
         }
      } else {
         throw new IllegalArgumentException("\u8bbe\u5907\u6ca1\u6709\u914d\u7f6e\u89c6\u9891\u5730\u5740");
      }
   }

   public byte[] snapshotStream(String app, String stream) {
      if (app != null && app.matches("[A-Za-z0-9_-]{1,64}") && stream != null && stream.matches("[A-Za-z0-9_-]{1,128}")) {
         String source = "rtsp://" + this.host + (this.rtspPort == 554 ? "" : ":" + this.rtspPort) + "/" + app + "/" + stream;

         try {
            HttpRequest request = HttpRequest.newBuilder(
                  this.buildUri("/index/api/getSnap", Map.of("secret", this.secret, "url", source, "timeout_sec", "8", "expire_sec", "1"))
               )
               .timeout(Duration.ofSeconds(10L))
               .GET()
               .build();
            HttpResponse<byte[]> response = this.client.send(request, BodyHandlers.ofByteArray());
            if (response.statusCode() == 200 && response.body().length >= 100) {
               return response.body();
            } else {
               throw new IllegalStateException("\u5a92\u4f53\u8282\u70b9\u76f4\u64ad\u6d41\u62bd\u5e27\u5931\u8d25");
            }
         } catch (RuntimeException e) {
            throw e;
         } catch (Exception e) {
            throw new IllegalStateException("\u5a92\u4f53\u8282\u70b9\u76f4\u64ad\u6d41\u62bd\u5e27\u5931\u8d25: " + friendly(e), e);
         }
      } else {
         throw new IllegalArgumentException("\u975e\u6cd5\u5a92\u4f53\u6d41\u6807\u8bc6");
      }
   }

   public int openRtpServer(String streamId) {
      try {
         JsonNode response = this.getJson("/index/api/openRtpServer", Map.of("secret", this.secret, "port", "0", "tcp_mode", "0", "stream_id", streamId));
         if (response.path("code").asInt(-1) != 0) {
            throw new IllegalStateException(response.path("msg").asText("\u6253\u5f00 RTP \u7aef\u53e3\u5931\u8d25"));
         } else {
            int openedPort = response.path("port").asInt(response.path("data").path("port").asInt(0));
            if (openedPort < 1) {
               throw new IllegalStateException("\u5a92\u4f53\u8282\u70b9\u6ca1\u6709\u8fd4\u56de\u6709\u6548 RTP \u7aef\u53e3");
            } else {
               return openedPort;
            }
         }
      } catch (Exception e) {
         throw new IllegalStateException(
            "GB28181 \u70b9\u64ad\u9700\u8981\u5728\u7ebf\u7684 ZLMediaKit RTP \u670d\u52a1\uff1b\u5f53\u524d FFmpeg \u517c\u5bb9\u6a21\u5f0f\u53ea\u652f\u6301 RTSP \u5b9e\u7269\u63a5\u5165",
            e
         );
      }
   }

   public boolean closeRtpServer(String streamId) {
      try {
         JsonNode response = this.getJson("/index/api/closeRtpServer", Map.of("secret", this.secret, "stream_id", streamId));
         boolean closed = response.path("code").asInt(-1) == 0;
         if (!closed) {
            LoggerFactory.getLogger(MediaService.class).warn("ZLMediaKit RTP close rejected stream={}: {}", streamId, response.path("msg").asText("unknown"));
         }

         return closed;
      } catch (Exception e) {
         LoggerFactory.getLogger(MediaService.class).warn("ZLMediaKit RTP close failed stream={}: {}", streamId, friendly(e));
         return false;
      }
   }

   public boolean closeStreamProxy(String key) {
      if (key != null && !key.isBlank()) {
         try {
            JsonNode response = this.getJson("/index/api/delStreamProxy", Map.of("secret", this.secret, "key", key));
            return response.path("code").asInt(-1) == 0;
         } catch (Exception e) {
            LoggerFactory.getLogger(MediaService.class).warn("ZLMediaKit proxy close failed key={}: {}", key, friendly(e));
            return false;
         }
      } else {
         return false;
      }
   }

   public MediaService.MediaPayload fetchMedia(String app, String stream, String filename) {
      if (app.matches("[A-Za-z0-9_-]{1,64}") && stream.matches("[A-Za-z0-9_-]{1,128}")) {
         String safeFilename = normalizeMediaPath(filename);

         try {
            URI uri = URI.create(this.internalBase() + "/" + app + "/" + stream + "/" + safeFilename);
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8L)).GET().build();
            HttpResponse<byte[]> response = this.client.send(request, BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
               throw new IllegalStateException("\u5a92\u4f53\u8282\u70b9 HTTP " + response.statusCode());
            } else {
               return new MediaService.MediaPayload(response.body(), response.headers().firstValue("Content-Type").orElse("application/octet-stream"));
            }
         } catch (RuntimeException e) {
            throw e;
         } catch (Exception e) {
            throw new IllegalStateException("\u8bfb\u53d6\u5a92\u4f53\u5206\u7247\u5931\u8d25: " + friendly(e), e);
         }
      } else {
         throw new IllegalArgumentException("\u975e\u6cd5\u5a92\u4f53\u8d44\u6e90\u8def\u5f84");
      }
   }

   public Map<String, Object> rtpStatus(String streamId, int expectedPort) {
      LinkedHashMap<String, Object> result = new LinkedHashMap<>();
      result.put("ssrc", streamId);
      result.put("expectedPort", expectedPort);

      try {
         JsonNode rtp = this.getJson("/index/api/getRtpInfo", Map.of("secret", this.secret, "stream_id", streamId));
         JsonNode list = this.getJson("/index/api/getMediaList", Map.of("secret", this.secret, "vhost", "__defaultVhost__", "app", "rtp", "stream", streamId));
         boolean receiving = rtp.path("exist").asBoolean(false);
         JsonNode media = list.path("data");
         boolean registered = media.isArray() && !media.isEmpty();
         result.put("receivingRtp", receiving);
         result.put("mediaRegistered", registered);
         result.put("peerIp", rtp.path("peer_ip").asText(""));
         result.put("localPort", rtp.path("local_port").asInt(expectedPort));
         ArrayList<Map<String, Object>> tracks = new ArrayList<>();
         JsonNode trackNodes;
         if (registered && (trackNodes = media.get(0).path("tracks")).isArray()) {
            for (JsonNode track : trackNodes) {
               LinkedHashMap<String, Object> item = new LinkedHashMap<>();
               item.put("codec", codecName(track.path("codec_id").asInt(-1)));
               item.put("type", track.path("codec_type").asInt(-1) == 0 ? "VIDEO" : "AUDIO");
               item.put("ready", track.path("ready").asBoolean(false));
               tracks.add(item);
            }
         }

         result.put("tracks", tracks);
         result.put("state", registered ? "MEDIA_READY" : (receiving ? "RTP_RECEIVED" : "WAITING_RTP"));
         result.put(
            "message",
            registered
               ? "\u5a92\u4f53\u6d41\u5df2\u6ce8\u518c\uff0c\u53ef\u751f\u6210 HLS"
               : (
                  receiving
                     ? "\u5df2\u6536\u5230 RTP\uff0c\u6b63\u5728\u89e3\u6790 PS \u5a92\u4f53"
                     : "\u5c1a\u672a\u6536\u5230\u6444\u50cf\u673a RTP \u6570\u636e"
               )
         );
         return result;
      } catch (Exception e) {
         result.put("state", "CHECK_FAILED");
         result.put("message", friendly(e));
         return result;
      }
   }

   private JsonNode getJson(String path, Map<String, String> parameters) throws Exception {
      HttpRequest request = HttpRequest.newBuilder(this.buildUri(path, parameters)).timeout(Duration.ofSeconds(5L)).GET().build();
      HttpResponse<String> response = this.client.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
         throw new IllegalStateException("\u5a92\u4f53\u8282\u70b9 HTTP " + response.statusCode());
      } else {
         return this.mapper.readTree(response.body());
      }
   }

   private URI buildUri(String path, Map<String, String> parameters) {
      String query = parameters.entrySet()
         .stream()
         .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
         .reduce((left, right) -> left + "&" + right)
         .orElse("");
      return URI.create(this.internalBase() + path + "?" + query);
   }

   public String publicBase() {
      String configured = this.configuredPublicBaseUrl == null ? "" : this.configuredPublicBaseUrl.trim();
      return !configured.isBlank() ? configured.replaceAll("/+$", "") : this.internalBase();
   }

   private String internalBase() {
      return "http://" + this.host + (this.port == 80 ? "" : ":" + this.port);
   }

   private String endpoint() {
      return this.host + ":" + this.port;
   }

   private static String encode(String value) {
      return URLEncoder.encode(value, StandardCharsets.UTF_8);
   }

   static String safeStreamId(String id) {
      return id.replaceAll("[^A-Za-z0-9_-]", "_");
   }

   static String normalizeMediaPath(String path) {
      if (path == null) {
         throw new IllegalArgumentException("\u975e\u6cd5\u5a92\u4f53\u8d44\u6e90\u8def\u5f84");
      }

      String normalized = path.replace('\\', '/').replaceFirst("^/+", "");
      if (!normalized.isBlank() && normalized.length() <= 480) {
         String[] segments;
         for (String string : segments = normalized.split("/", -1)) {
            if (string.isBlank() || string.equals(".") || string.equals("..") || !string.matches("[A-Za-z0-9._-]{1,160}")) {
               throw new IllegalArgumentException("\u975e\u6cd5\u5a92\u4f53\u8d44\u6e90\u8def\u5f84");
            }
         }

         return String.join("/", segments);
      } else {
         throw new IllegalArgumentException("\u975e\u6cd5\u5a92\u4f53\u8d44\u6e90\u8def\u5f84");
      }
   }

   private static String friendly(Exception e) {
      String message = e.getMessage();
      return message != null && !message.isBlank() ? message : "\u65e0\u6cd5\u8fde\u63a5\u5a92\u4f53\u8282\u70b9";
   }

   private static String codecName(int id) {
      return switch (id) {
         case 0 -> "H264";
         case 1 -> "H265";
         case 2 -> "AAC";
         case 3 -> "G711A";
         case 4 -> "G711U";
         case 5 -> "OPUS";
         default -> "UNKNOWN(" + id + ")";
         case 7 -> "VP8";
         case 8 -> "VP9";
         case 9 -> "AV1";
         case 10 -> "JPEG";
      };
   }

   public record MediaPayload(byte[] body, String contentType) {
   }
}
