package cn.videohub.media;

import cn.videohub.device.DeviceService;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.lang.ProcessBuilder.Redirect;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
public class FfmpegGateway {
   private final Map<String, Process> processes = new ConcurrentHashMap<>();
   private final Path outputRoot = Path.of(System.getProperty("java.io.tmpdir"), "mingqian-video-hls").toAbsolutePath().normalize();
   @Value("${video-hub.media.ffmpeg-path:ffmpeg}")
   private String ffmpegPath;

   public boolean available() {
      if (this.ffmpegPath == null || this.ffmpegPath.isBlank()) {
         return false;
      }

      if (!this.ffmpegPath.equalsIgnoreCase("ffmpeg") && !this.ffmpegPath.equalsIgnoreCase("ffmpeg.exe")) {
         return Files.isRegularFile(Path.of(this.ffmpegPath));
      }

      try {
         Process process = new ProcessBuilder(this.ffmpegPath, "-version").redirectErrorStream(true).start();
         return process.waitFor(2L, TimeUnit.SECONDS) && process.exitValue() == 0;
      } catch (Exception ignored) {
         return false;
      }
   }

   public Map<String, Object> start(DeviceService.Device device) {
      if (!this.available()) {
         throw new IllegalStateException("ZLMediaKit \u672a\u542f\u52a8\uff0c\u5e76\u4e14\u672c\u673a\u672a\u627e\u5230 FFmpeg");
      }

      String stream = MediaService.safeStreamId(device.id());

      try {
         Path directory = this.outputRoot.resolve(stream).normalize();
         if (!directory.startsWith(this.outputRoot)) {
            throw new IllegalArgumentException("\u975e\u6cd5\u6d41\u6807\u8bc6");
         }

         Files.createDirectories(directory);
         Process current = this.processes.get(stream);
         Path playlist = directory.resolve("index.m3u8");
         if (current == null || !current.isAlive()) {
            clearMediaFiles(directory);
            Process process = new ProcessBuilder(
                  this.ffmpegPath,
                  "-hide_banner",
                  "-loglevel",
                  "warning",
                  "-rtsp_transport",
                  "tcp",
                  "-i",
                  device.streamUrl(),
                  "-map",
                  "0:v:0",
                  "-map",
                  "0:a?",
                  "-c:v",
                  "libx264",
                  "-preset",
                  "veryfast",
                  "-tune",
                  "zerolatency",
                  "-profile:v",
                  "main",
                  "-pix_fmt",
                  "yuv420p",
                  "-c:a",
                  "aac",
                  "-f",
                  "hls",
                  "-hls_time",
                  "2",
                  "-hls_list_size",
                  "6",
                  "-hls_flags",
                  "delete_segments+append_list+omit_endlist",
                  "-hls_segment_filename",
                  directory.resolve("segment-%06d.ts").toString(),
                  playlist.toString()
               )
               .redirectError(directory.resolve("ffmpeg.log").toFile())
               .start();
            this.processes.put(stream, process);
            current = process;
         }

         long deadline = System.nanoTime() + Duration.ofSeconds(10L).toNanos();

         while (System.nanoTime() < deadline && !Files.isRegularFile(playlist) && current.isAlive()) {
            Thread.sleep(200L);
         }

         if (!Files.isRegularFile(playlist)) {
            current.destroyForcibly();
            this.processes.remove(stream, current);
            throw new IllegalStateException(
               "\u6444\u50cf\u673a RTSP \u62c9\u6d41\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u5730\u5740\u3001\u8d26\u53f7\u5bc6\u7801\u548c\u7f51\u7edc\u8fde\u63a5"
            );
         } else {
            return Map.of(
               "mode",
               "FFMPEG_HLS",
               "stream",
               stream,
               "hls",
               "/api/media/hls/" + stream + "/index.m3u8",
               "message",
               "\u5df2\u4f7f\u7528\u672c\u673a FFmpeg \u81ea\u52a8\u8f6c\u6362 RTSP"
            );
         }
      } catch (IllegalStateException e) {
         throw e;
      } catch (Exception e) {
         throw new IllegalStateException("\u542f\u52a8 FFmpeg \u89c6\u9891\u4e2d\u8f6c\u5931\u8d25: " + e.getMessage(), e);
      }
   }

   public boolean stop(String stream) {
      if (stream != null && stream.matches("[A-Za-z0-9_-]+")) {
         Process process = this.processes.remove(stream);
         if (process != null && process.isAlive()) {
            process.destroy();

            try {
               if (!process.waitFor(2L, TimeUnit.SECONDS)) {
                  process.destroyForcibly();
               }
            } catch (InterruptedException e) {
               Thread.currentThread().interrupt();
               process.destroyForcibly();
            }
         }

         try {
            Path directory = this.outputRoot.resolve(stream).normalize();
            if (directory.startsWith(this.outputRoot) && Files.isDirectory(directory)) {
               clearMediaFiles(directory);
            }
         } catch (Exception var4) {
         }

         return process != null;
      } else {
         return false;
      }
   }

   public FfmpegGateway.ProbeResult probe(DeviceService.Device device) {
      long started = System.nanoTime();
      int port = device.port() == null ? ("RTSP".equalsIgnoreCase(device.protocol()) ? 554 : 443) : device.port();

      try (Socket socket = new Socket()) {
         socket.connect(new InetSocketAddress(device.host(), port), 2500);
      } catch (Exception e) {
         return new FfmpegGateway.ProbeResult(
            false,
            elapsed(started),
            "\u65e0\u6cd5\u8fde\u63a5 " + device.host() + ":" + port + "\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u3001\u7aef\u53e3\u6216VPN"
         );
      }

      if (!"RTSP".equalsIgnoreCase(device.protocol())) {
         return new FfmpegGateway.ProbeResult(true, elapsed(started), "\u8bbe\u5907\u7f51\u7edc\u7aef\u53e3\u8fde\u63a5\u6b63\u5e38");
      }

      if (device.streamUrl() == null || device.streamUrl().isBlank()) {
         return new FfmpegGateway.ProbeResult(false, elapsed(started), "\u672a\u914d\u7f6e RTSP \u89c6\u9891\u5730\u5740");
      }

      if (!this.available()) {
         return new FfmpegGateway.ProbeResult(
            false,
            elapsed(started),
            "\u8bbe\u5907\u7aef\u53e3\u53ef\u8fde\u63a5\uff0c\u4f46\u672c\u673a\u672a\u627e\u5230 FFmpeg\uff0c\u65e0\u6cd5\u9a8c\u8bc1\u89c6\u9891\u6d41"
         );
      }

      try {
         Path log = Files.createTempFile("mingqian-probe-", ".log");
         Process process = new ProcessBuilder(
               this.ffmpegPath,
               "-hide_banner",
               "-loglevel",
               "error",
               "-rtsp_transport",
               "tcp",
               "-i",
               device.streamUrl(),
               "-map",
               "0:v:0",
               "-t",
               "1",
               "-f",
               "null",
               "-"
            )
            .redirectOutput(Redirect.DISCARD)
            .redirectError(log.toFile())
            .start();
         boolean finished = process.waitFor(12L, TimeUnit.SECONDS);
         if (!finished) {
            process.destroyForcibly();
         }

         String detail = Files.readString(log, StandardCharsets.UTF_8).trim();
         Files.deleteIfExists(log);
         return finished && process.exitValue() == 0
            ? new FfmpegGateway.ProbeResult(true, elapsed(started), "RTSP \u767b\u5f55\u548c\u89c6\u9891\u6d41\u8bfb\u53d6\u6b63\u5e38")
            : new FfmpegGateway.ProbeResult(false, elapsed(started), explainProbeFailure(detail));
      } catch (Exception e) {
         return new FfmpegGateway.ProbeResult(false, elapsed(started), "RTSP \u68c0\u6d4b\u5931\u8d25: " + e.getMessage());
      }
   }

   public Resource resource(String stream, String filename) {
      if (stream.matches("[A-Za-z0-9_-]+") && filename.matches("[A-Za-z0-9_.-]+")) {
         Path file = this.outputRoot.resolve(stream).resolve(filename).normalize();
         if (file.startsWith(this.outputRoot) && Files.isRegularFile(file)) {
            return new FileSystemResource(file);
         } else {
            throw new IllegalArgumentException("\u5a92\u4f53\u6587\u4ef6\u4e0d\u5b58\u5728");
         }
      } else {
         throw new IllegalArgumentException("\u975e\u6cd5\u5a92\u4f53\u6587\u4ef6\u8def\u5f84");
      }
   }

   public byte[] snapshot(DeviceService.Device device) {
      if (!this.available()) {
         throw new IllegalStateException("\u672c\u673a\u672a\u627e\u5230 FFmpeg");
      }

      try {
         Path log = Files.createTempFile("mingqian-snapshot-", ".log");
         Process process = new ProcessBuilder(
               this.ffmpegPath,
               "-hide_banner",
               "-loglevel",
               "warning",
               "-rtsp_transport",
               "tcp",
               "-i",
               device.streamUrl(),
               "-frames:v",
               "1",
               "-f",
               "image2pipe",
               "-vcodec",
               "mjpeg",
               "pipe:1"
            )
            .redirectError(log.toFile())
            .start();
         ByteArrayOutputStream image = new ByteArrayOutputStream();
         Thread reader = Thread.ofVirtual().start(() -> {
            try {
               process.getInputStream().transferTo(image);
            } catch (Exception var3x) {
            }
         });
         if (!process.waitFor(12L, TimeUnit.SECONDS)) {
            process.destroyForcibly();
         }

         reader.join(1000L);
         Files.deleteIfExists(log);
         if (image.size() < 100) {
            throw new IllegalStateException("\u6444\u50cf\u673a\u672a\u8fd4\u56de\u53ef\u622a\u56fe\u753b\u9762");
         } else {
            return image.toByteArray();
         }
      } catch (IllegalStateException e) {
         throw e;
      } catch (Exception e) {
         throw new IllegalStateException("FFmpeg \u62bd\u5e27\u5931\u8d25: " + e.getMessage(), e);
      }
   }

   public byte[] snapshotRunning(String stream) {
      if (stream != null && stream.matches("[A-Za-z0-9_-]+")) {
         Process relay = this.processes.get(stream);
         Path playlist = this.outputRoot.resolve(stream).resolve("index.m3u8").normalize();
         if (relay != null && relay.isAlive() && playlist.startsWith(this.outputRoot) && Files.isRegularFile(playlist)) {
            return this.snapshotInput(playlist.toString(), false);
         } else {
            throw new IllegalStateException("\u6b63\u5728\u76f4\u64ad\u7684 FFmpeg \u6d41\u4e0d\u53ef\u7528");
         }
      } else {
         throw new IllegalArgumentException("\u975e\u6cd5\u5a92\u4f53\u6d41\u6807\u8bc6");
      }
   }

   private byte[] snapshotInput(String source, boolean rtsp) {
      if (!this.available()) {
         throw new IllegalStateException("\u672c\u673a\u672a\u627e\u5230 FFmpeg");
      }

      try {
         Path log = Files.createTempFile("mingqian-snapshot-live-", ".log");
         ArrayList<String> command = new ArrayList<>(List.of(this.ffmpegPath, "-hide_banner", "-loglevel", "warning"));
         if (rtsp) {
            command.addAll(List.of("-rtsp_transport", "tcp"));
         }

         command.addAll(List.of("-i", source, "-frames:v", "1", "-f", "image2pipe", "-vcodec", "mjpeg", "pipe:1"));
         Process process = new ProcessBuilder(command).redirectError(log.toFile()).start();
         ByteArrayOutputStream image = new ByteArrayOutputStream();
         Thread reader = Thread.ofVirtual().start(() -> {
            try {
               process.getInputStream().transferTo(image);
            } catch (Exception var3x) {
            }
         });
         if (!process.waitFor(10L, TimeUnit.SECONDS)) {
            process.destroyForcibly();
         }

         reader.join(1000L);
         Files.deleteIfExists(log);
         if (image.size() < 100) {
            throw new IllegalStateException("\u76f4\u64ad\u6d41\u672a\u8fd4\u56de\u53ef\u622a\u56fe\u753b\u9762");
         } else {
            return image.toByteArray();
         }
      } catch (IllegalStateException e) {
         throw e;
      } catch (Exception e) {
         throw new IllegalStateException("\u76f4\u64ad\u6d41\u62bd\u5e27\u5931\u8d25: " + e.getMessage(), e);
      }
   }

   private static void clearMediaFiles(Path directory) throws Exception {
      try (Stream<Path> files = Files.list(directory)) {
         files.filter(path -> path.getFileName().toString().endsWith(".ts") || path.getFileName().toString().endsWith(".m3u8")).forEach(path -> {
            try {
               Files.deleteIfExists(path);
            } catch (Exception var2) {
            }
         });
      }
   }

   private static long elapsed(long started) {
      return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
   }

   private static String explainProbeFailure(String detail) {
      String lower = detail.toLowerCase();
      if (lower.contains("401") || lower.contains("unauthorized")) {
         return "RTSP \u8d26\u53f7\u6216\u5bc6\u7801\u9519\u8bef";
      } else if (lower.contains("404") || lower.contains("not found")) {
         return "RTSP \u901a\u9053\u8def\u5f84\u4e0d\u5b58\u5728\uff0c\u8bf7\u68c0\u67e5 Channels/101 \u6216 102";
      } else if (lower.contains("timed out") || lower.contains("timeout")) {
         return "RTSP \u8fde\u63a5\u8d85\u65f6\uff0c\u8bf7\u68c0\u67e5\u8de8\u7f51\u8fde\u63a5\u548c\u9632\u706b\u5899";
      } else if (lower.contains("refused")) {
         return "\u6444\u50cf\u673a\u62d2\u7edd\u8fde\u63a5\uff0c\u8bf7\u786e\u8ba4\u5df2\u542f\u7528 RTSP \u670d\u52a1";
      } else {
         return detail.isBlank()
            ? "\u672a\u8bfb\u53d6\u5230\u6709\u6548\u89c6\u9891\u6d41"
            : "RTSP \u6d41\u9a8c\u8bc1\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u7f16\u7801\u3001\u8d26\u53f7\u548c\u901a\u9053\u8def\u5f84";
      }
   }

   @PreDestroy
   void stop() {
      this.processes.values().forEach(process -> {
         if (process.isAlive()) {
            process.destroy();
         }
      });
      this.processes.clear();
   }

   public record ProbeResult(boolean online, long latencyMs, String message) {
   }
}
