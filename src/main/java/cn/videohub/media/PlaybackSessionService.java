package cn.videohub.media;

import cn.videohub.sip.Gb28181SipServer;
import com.sun.management.OperatingSystemMXBean;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class PlaybackSessionService {
   private static final Logger log = LoggerFactory.getLogger(PlaybackSessionService.class);
   private final Map<String, PlaybackSessionService.PlaybackSession> sessions = new ConcurrentHashMap<>();
   private final Gb28181SipServer sip;
   private final MediaService media;
   private final FfmpegGateway ffmpeg;
   @Value("${video-hub.playback.viewer-timeout-seconds:25}")
   private volatile long viewerTimeoutSeconds = 25L;
   @Value("${video-hub.playback.startup-timeout-seconds:35}")
   private volatile long startupTimeoutSeconds = 35L;
   @Value("${video-hub.playback.max-active:6}")
   private volatile int maxActive = 6;
   @Value("${video-hub.playback.memory-pressure-enabled:true}")
   private volatile boolean memoryPressureEnabled = true;
   @Value("${video-hub.playback.heap-high-watermark-percent:82}")
   private volatile int heapHighWatermarkPercent = 82;
   @Value("${video-hub.playback.system-memory-high-watermark-percent:88}")
   private volatile int systemMemoryHighWatermarkPercent = 88;
   @Value("${video-hub.playback.pressure-evictions-per-sweep:1}")
   private volatile int pressureEvictionsPerSweep = 1;
   @Value("${video-hub.playback.pressure-eviction-cooldown-seconds:5}")
   private volatile long pressureEvictionCooldownSeconds = 5L;
   @Value("${video-hub.playback.settings-file:./data/playback-settings.properties}")
   private String settingsFile = "./data/playback-settings.properties";
   private final Object admissionLock = new Object();
   private DoubleSupplier heapUsageRatio = PlaybackSessionService::currentHeapUsageRatio;
   private DoubleSupplier systemMemoryUsageRatio = PlaybackSessionService::currentSystemMemoryUsageRatio;
   private volatile Instant lastPressureEviction = Instant.EPOCH;

   public PlaybackSessionService(Gb28181SipServer sip, MediaService media, FfmpegGateway ffmpeg) {
      this.sip = sip;
      this.media = media;
      this.ffmpeg = ffmpeg;
   }

   @PostConstruct
   void loadPersistedSettings() {
      Path path = this.settingsPath();
      if (Files.isReadable(path)) {
         Properties values = new Properties();

         try (InputStream input = Files.newInputStream(path)) {
            values.load(input);
            this.applySettings(
               new PlaybackSessionService.PlaybackSettings(
                  intProperty(values, "maxActive", this.maxActive),
                  intProperty(values, "heapHighWatermarkPercent", this.heapHighWatermarkPercent),
                  intProperty(values, "systemMemoryHighWatermarkPercent", this.systemMemoryHighWatermarkPercent),
                  intProperty(values, "pressureEvictionsPerSweep", this.pressureEvictionsPerSweep),
                  longProperty(values, "pressureEvictionCooldownSeconds", this.pressureEvictionCooldownSeconds),
                  longProperty(values, "viewerTimeoutSeconds", this.viewerTimeoutSeconds),
                  longProperty(values, "startupTimeoutSeconds", this.startupTimeoutSeconds),
                  Boolean.parseBoolean(values.getProperty("memoryPressureEnabled", Boolean.toString(this.memoryPressureEnabled)))
               ),
               false
            );
            log.info("Playback resource settings loaded from {}", path);
         } catch (Exception e) {
            log.error("Playback resource settings are invalid: {}", path, e);
         }
      }
   }

   public void assertCapacity() {
      synchronized (this.admissionLock) {
         boolean atCapacity = this.sessions.size() >= Math.max(1, this.maxActive);
         boolean memoryPressure = this.isMemoryPressure();
         if ((atCapacity || memoryPressure) && !this.evictOldest(atCapacity ? "capacity-lru" : "memory-pressure-admission")) {
            throw new IllegalStateException(
               "\u670d\u52a1\u5668\u8d44\u6e90\u63a5\u8fd1\u4e0a\u9650\uff0c\u6682\u65f6\u65e0\u6cd5\u5efa\u7acb\u65b0\u7684\u64ad\u653e\u901a\u9053"
            );
         }

         if (this.sessions.size() >= Math.max(1, this.maxActive)) {
            throw new IllegalStateException(
               "\u6d3b\u52a8\u64ad\u653e\u901a\u9053\u5df2\u8fbe\u5230\u4e0a\u9650\uff0c\u6682\u65f6\u65e0\u6cd5\u5efa\u7acb\u65b0\u7684\u64ad\u653e\u901a\u9053"
            );
         }
      }
   }

   public String nextPlaybackId() {
      return UUID.randomUUID().toString().replace("-", "");
   }

   public void registerGb(String deviceId, String channelId, String ssrc, int rtpPort) {
      this.sessions.put(ssrc, new PlaybackSessionService.PlaybackSession(deviceId, channelId, ssrc, PlaybackSessionService.Kind.GB28181, ssrc, rtpPort));
   }

   public void register(String deviceId, String channelId, String ssrc, int rtpPort) {
      this.registerGb(deviceId, channelId, ssrc, rtpPort);
   }

   public void registerProxy(String deviceId, String channelId, String playbackId, String proxyKey) {
      this.registerProxy(deviceId, channelId, playbackId, proxyKey, "");
   }

   public void registerProxy(String deviceId, String channelId, String playbackId, String proxyKey, String stream) {
      this.sessions
         .put(
            playbackId, new PlaybackSessionService.PlaybackSession(deviceId, channelId, playbackId, PlaybackSessionService.Kind.ZLM_PROXY, proxyKey, 0, stream)
         );
   }

   public void registerFfmpeg(String deviceId, String channelId, String playbackId, String stream) {
      this.sessions
         .put(
            playbackId, new PlaybackSessionService.PlaybackSession(deviceId, channelId, playbackId, PlaybackSessionService.Kind.FFMPEG_HLS, stream, 0, stream)
         );
   }

   public PlaybackSessionService.ReusableStream findReusableStream(String deviceId, String channelId) {
      return this.sessions
         .values()
         .stream()
         .filter(session -> session.deviceId.equals(deviceId))
         .filter(session -> channelId == null || channelId.isBlank() || session.channelId.equals(channelId))
         .filter(session -> session.kind != PlaybackSessionService.Kind.ZLM_PROXY || !session.snapshotStream.isBlank())
         .max(Comparator.comparing(session -> session.lastViewerHeartbeat))
         .map(
            session -> new PlaybackSessionService.ReusableStream(
               session.kind == PlaybackSessionService.Kind.GB28181 ? "ZLM" : (session.kind == PlaybackSessionService.Kind.ZLM_PROXY ? "ZLM" : "FFMPEG_HLS"),
               session.kind == PlaybackSessionService.Kind.GB28181 ? "rtp" : (session.kind == PlaybackSessionService.Kind.ZLM_PROXY ? "proxy" : ""),
               session.snapshotStream,
               session.playbackId,
               session.channelId
            )
         )
         .orElse(null);
   }

   public boolean canStartBackgroundStream() {
      synchronized (this.admissionLock) {
         return this.sessions.size() < Math.max(1, this.maxActive) && !this.isMemoryPressure();
      }
   }

   public boolean heartbeat(String deviceId, String playbackId) {
      PlaybackSessionService.PlaybackSession session = this.sessions.get(playbackId);
      if (session != null && session.deviceId.equals(deviceId)) {
         session.lastViewerHeartbeat = Instant.now();
         return true;
      } else {
         return false;
      }
   }

   public PlaybackSessionService.StopResult stop(String deviceId, String playbackId, String reason) {
      PlaybackSessionService.PlaybackSession session = this.sessions.get(playbackId);
      if (session == null || !session.deviceId.equals(deviceId)) {
         return new PlaybackSessionService.StopResult(false, false, false, "\u64ad\u653e\u901a\u9053\u5df2\u5173\u95ed");
      }

      if (!this.sessions.remove(playbackId, session)) {
         return new PlaybackSessionService.StopResult(false, false, false, "\u64ad\u653e\u901a\u9053\u5df2\u5173\u95ed");
      }

      boolean signalStopped = false;
      boolean mediaClosed = false;
      if (session.kind == PlaybackSessionService.Kind.GB28181) {
         signalStopped = this.sip.stopStream(session.resourceId);
         mediaClosed = this.media.closeRtpServer(session.resourceId);
      } else if (this.lastUserOf(session.kind, session.resourceId)) {
         mediaClosed = session.kind == PlaybackSessionService.Kind.ZLM_PROXY
            ? this.media.closeStreamProxy(session.resourceId)
            : this.ffmpeg.stop(session.resourceId);
      }

      log.info(
         "Playback stopped device={} channel={} id={} kind={} reason={} signal={} mediaClosed={}",
         new Object[]{deviceId, session.channelId, playbackId, session.kind, reason, signalStopped, mediaClosed}
      );
      return new PlaybackSessionService.StopResult(true, signalStopped, mediaClosed, "\u64ad\u653e\u901a\u9053\u5df2\u5173\u95ed");
   }

   public int stopDevice(String deviceId, String reason) {
      List<PlaybackSessionService.PlaybackSession> matches = this.sessions.values().stream().filter(session -> session.deviceId.equals(deviceId)).toList();
      matches.forEach(session -> this.stop(deviceId, session.playbackId, reason));
      return matches.size();
   }

   private boolean lastUserOf(PlaybackSessionService.Kind kind, String resourceId) {
      return this.sessions.values().stream().noneMatch(session -> session.kind == kind && session.resourceId.equals(resourceId));
   }

   @Scheduled(fixedDelay = 5000L)
   public void reapAbandonedSessions() {
      Instant deadline = Instant.now().minusSeconds(Math.max(15L, this.viewerTimeoutSeconds));
      this.sessions
         .values()
         .stream()
         .filter(session -> session.lastViewerHeartbeat.isBefore(deadline))
         .toList()
         .forEach(session -> this.stop(session.deviceId, session.playbackId, "viewer-timeout"));
      Instant startupDeadline = Instant.now().minusSeconds(Math.max(15L, this.startupTimeoutSeconds));
      this.sessions
         .values()
         .stream()
         .filter(session -> session.kind == PlaybackSessionService.Kind.GB28181 && session.createdAt.isBefore(startupDeadline))
         .filter(session -> "WAITING_RTP".equals(this.media.rtpStatus(session.resourceId, session.rtpPort).get("state")))
         .toList()
         .forEach(session -> this.stop(session.deviceId, session.playbackId, "startup-timeout"));
      this.reapForMemoryPressure();
   }

   private void reapForMemoryPressure() {
      if (this.isMemoryPressure() && !this.sessions.isEmpty()) {
         Instant now = Instant.now();
         if (!this.lastPressureEviction.plusSeconds(Math.max(1L, this.pressureEvictionCooldownSeconds)).isAfter(now)) {
            int limit = Math.max(1, Math.min(4, this.pressureEvictionsPerSweep));
            int evicted = 0;
            synchronized (this.admissionLock) {
               while (evicted < limit && this.isMemoryPressure() && this.evictOldest("memory-pressure")) {
                  evicted++;
               }

               if (evicted > 0) {
                  this.lastPressureEviction = now;
               }
            }

            if (evicted > 0) {
               log.warn(
                  "Memory pressure eviction completed evicted={} active={} heapUsagePercent={} heapThresholdPercent={} systemMemoryUsagePercent={} systemThresholdPercent={}",
                  new Object[]{
                     evicted,
                     this.sessions.size(),
                     this.heapUsagePercent(),
                     this.normalizedHeapThreshold(),
                     this.systemMemoryUsagePercent(),
                     this.normalizedSystemMemoryThreshold()
                  }
               );
            }
         }
      }
   }

   private boolean evictOldest(String reason) {
      PlaybackSessionService.PlaybackSession oldest = this.sessions
         .values()
         .stream()
         .min(
            Comparator.<PlaybackSessionService.PlaybackSession, Instant>comparing(session -> session.lastViewerHeartbeat)
               .thenComparing(session -> session.createdAt)
         )
         .orElse(null);
      return oldest == null ? false : this.stop(oldest.deviceId, oldest.playbackId, reason).found();
   }

   private boolean isMemoryPressure() {
      return this.memoryPressureEnabled
         && (this.heapUsagePercent() >= this.normalizedHeapThreshold() || this.systemMemoryUsagePercent() >= this.normalizedSystemMemoryThreshold());
   }

   private int heapUsagePercent() {
      return (int)Math.round(Math.max(0.0, Math.min(1.0, this.heapUsageRatio.getAsDouble())) * 100.0);
   }

   private int normalizedHeapThreshold() {
      return Math.max(60, Math.min(95, this.heapHighWatermarkPercent));
   }

   private int systemMemoryUsagePercent() {
      return (int)Math.round(Math.max(0.0, Math.min(1.0, this.systemMemoryUsageRatio.getAsDouble())) * 100.0);
   }

   private int normalizedSystemMemoryThreshold() {
      return Math.max(70, Math.min(97, this.systemMemoryHighWatermarkPercent));
   }

   private static double currentHeapUsageRatio() {
      Runtime runtime = Runtime.getRuntime();
      long max = runtime.maxMemory();
      long used = runtime.totalMemory() - runtime.freeMemory();
      return max <= 0L ? 0.0 : (double)used / max;
   }

   private static double currentSystemMemoryUsageRatio() {
      Path memInfo = Path.of("/proc/meminfo");
      if (Files.isReadable(memInfo)) {
         try {
            long totalKb = 0L;
            long availableKb = 0L;

            for (String line : Files.readAllLines(memInfo)) {
               if (line.startsWith("MemTotal:")) {
                  totalKb = parseMemInfoKb(line);
               } else if (line.startsWith("MemAvailable:")) {
                  availableKb = parseMemInfoKb(line);
               }
            }

            if (totalKb > 0L && availableKb > 0L) {
               return 1.0 - (double)availableKb / totalKb;
            }
         } catch (Exception e) {
            log.debug("Unable to read /proc/meminfo", e);
         }
      }

      if (ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os) {
         long total = os.getTotalMemorySize();
         long free = os.getFreeMemorySize();
         return total <= 0L ? 0.0 : 1.0 - (double)free / total;
      } else {
         return 0.0;
      }
   }

   private static long parseMemInfoKb(String line) {
      String digits = line.replaceAll("[^0-9]", "");
      return digits.isEmpty() ? 0L : Long.parseLong(digits);
   }

   public int activeCount() {
      return this.sessions.size();
   }

   public boolean isActive(String playbackId) {
      return this.sessions.containsKey(playbackId);
   }

   public Map<String, Object> resourceStatus() {
      int active = this.sessions.size();
      int maximum = Math.max(1, this.maxActive);
      int heap = this.heapUsagePercent();
      int system = this.systemMemoryUsagePercent();
      boolean pressure = this.isMemoryPressure();
      LinkedHashMap<String, Object> status = new LinkedHashMap<>();
      status.put("active", active);
      status.put("maxActive", maximum);
      status.put("availableSlots", pressure ? 0 : Math.max(0, maximum - active));
      status.put("capacityUsagePercent", Math.min(100, (int)Math.round(active * 100.0 / maximum)));
      status.put("heapUsagePercent", heap);
      status.put("heapHighWatermarkPercent", this.normalizedHeapThreshold());
      status.put("systemMemoryUsagePercent", system);
      status.put("systemMemoryHighWatermarkPercent", this.normalizedSystemMemoryThreshold());
      status.put("memoryPressure", pressure);
      status.put(
         "risk",
         pressure
            ? "CRITICAL"
            : (active < maximum && heap < this.normalizedHeapThreshold() - 10 && system < this.normalizedSystemMemoryThreshold() - 10 ? "NORMAL" : "WARNING")
      );
      status.put("evictionPolicy", "LEAST_RECENTLY_ACTIVE");
      status.put("settings", this.settings());
      return status;
   }

   public PlaybackSessionService.PlaybackSettings settings() {
      return new PlaybackSessionService.PlaybackSettings(
         this.maxActive,
         this.normalizedHeapThreshold(),
         this.normalizedSystemMemoryThreshold(),
         Math.max(1, Math.min(4, this.pressureEvictionsPerSweep)),
         Math.max(1L, this.pressureEvictionCooldownSeconds),
         Math.max(15L, this.viewerTimeoutSeconds),
         Math.max(15L, this.startupTimeoutSeconds),
         this.memoryPressureEnabled
      );
   }

   public synchronized PlaybackSessionService.PlaybackSettings updateSettings(PlaybackSessionService.PlaybackSettings settings) {
      this.applySettings(settings, true);

      while (this.sessions.size() > this.maxActive && this.evictOldest("settings-capacity-reduced")) {
      }

      return this.settings();
   }

   private void applySettings(PlaybackSessionService.PlaybackSettings settings, boolean persist) {
      if (settings == null || settings.maxActive() < 1 || settings.maxActive() > 64) {
         throw new IllegalArgumentException("\u6700\u5927\u64ad\u653e\u8def\u6570\u5fc5\u987b\u5728 1 \u5230 64 \u4e4b\u95f4");
      }

      if (settings.heapHighWatermarkPercent() < 60 || settings.heapHighWatermarkPercent() > 95) {
         throw new IllegalArgumentException("JVM \u5185\u5b58\u6c34\u4f4d\u5fc5\u987b\u5728 60% \u5230 95% \u4e4b\u95f4");
      }

      if (settings.systemMemoryHighWatermarkPercent() < 70 || settings.systemMemoryHighWatermarkPercent() > 97) {
         throw new IllegalArgumentException("\u6574\u673a\u5185\u5b58\u6c34\u4f4d\u5fc5\u987b\u5728 70% \u5230 97% \u4e4b\u95f4");
      }

      if (settings.pressureEvictionsPerSweep() < 1 || settings.pressureEvictionsPerSweep() > 4) {
         throw new IllegalArgumentException("\u5355\u8f6e\u91ca\u653e\u8def\u6570\u5fc5\u987b\u5728 1 \u5230 4 \u4e4b\u95f4");
      }

      if (settings.pressureEvictionCooldownSeconds() < 1L || settings.pressureEvictionCooldownSeconds() > 60L) {
         throw new IllegalArgumentException("\u91ca\u653e\u51b7\u5374\u65f6\u95f4\u5fc5\u987b\u5728 1 \u5230 60 \u79d2\u4e4b\u95f4");
      }

      if (settings.viewerTimeoutSeconds() < 15L || settings.viewerTimeoutSeconds() > 300L) {
         throw new IllegalArgumentException("\u65e0\u5fc3\u8df3\u8d85\u65f6\u5fc5\u987b\u5728 15 \u5230 300 \u79d2\u4e4b\u95f4");
      }

      if (settings.startupTimeoutSeconds() >= 15L && settings.startupTimeoutSeconds() <= 300L) {
         this.maxActive = settings.maxActive();
         this.heapHighWatermarkPercent = settings.heapHighWatermarkPercent();
         this.systemMemoryHighWatermarkPercent = settings.systemMemoryHighWatermarkPercent();
         this.pressureEvictionsPerSweep = settings.pressureEvictionsPerSweep();
         this.pressureEvictionCooldownSeconds = settings.pressureEvictionCooldownSeconds();
         this.viewerTimeoutSeconds = settings.viewerTimeoutSeconds();
         this.startupTimeoutSeconds = settings.startupTimeoutSeconds();
         this.memoryPressureEnabled = settings.memoryPressureEnabled();
         if (persist) {
            this.persistSettings();
         }
      } else {
         throw new IllegalArgumentException("\u542f\u52a8\u8d85\u65f6\u5fc5\u987b\u5728 15 \u5230 300 \u79d2\u4e4b\u95f4");
      }
   }

   private void persistSettings() {
      Path target = this.settingsPath();
      Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
      Properties values = new Properties();
      PlaybackSessionService.PlaybackSettings settings = this.settings();
      values.setProperty("maxActive", Integer.toString(settings.maxActive()));
      values.setProperty("heapHighWatermarkPercent", Integer.toString(settings.heapHighWatermarkPercent()));
      values.setProperty("systemMemoryHighWatermarkPercent", Integer.toString(settings.systemMemoryHighWatermarkPercent()));
      values.setProperty("pressureEvictionsPerSweep", Integer.toString(settings.pressureEvictionsPerSweep()));
      values.setProperty("pressureEvictionCooldownSeconds", Long.toString(settings.pressureEvictionCooldownSeconds()));
      values.setProperty("viewerTimeoutSeconds", Long.toString(settings.viewerTimeoutSeconds()));
      values.setProperty("startupTimeoutSeconds", Long.toString(settings.startupTimeoutSeconds()));
      values.setProperty("memoryPressureEnabled", Boolean.toString(settings.memoryPressureEnabled()));

      try {
         if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
         }

         try (OutputStream output = Files.newOutputStream(temporary)) {
            values.store(output, "Mingqian video playback resource settings");
         }

         try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
         } catch (Exception ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
         }
      } catch (Exception e) {
         throw new IllegalStateException("\u64ad\u653e\u8d44\u6e90\u53c2\u6570\u4fdd\u5b58\u5931\u8d25", e);
      }
   }

   private Path settingsPath() {
      return Path.of(this.settingsFile).toAbsolutePath().normalize();
   }

   private static int intProperty(Properties values, String key, int fallback) {
      String value = values.getProperty(key);
      return value == null ? fallback : Integer.parseInt(value);
   }

   private static long longProperty(Properties values, String key, long fallback) {
      String value = values.getProperty(key);
      return value == null ? fallback : Long.parseLong(value);
   }

   private enum Kind {
      GB28181,
      ZLM_PROXY,
      FFMPEG_HLS;
   }

   private static final class PlaybackSession {
      private final String deviceId;
      private final String channelId;
      private final String playbackId;
      private final PlaybackSessionService.Kind kind;
      private final String resourceId;
      private final int rtpPort;
      private final Instant createdAt = Instant.now();
      private volatile Instant lastViewerHeartbeat = Instant.now();
      private final String snapshotStream;

      private PlaybackSession(String deviceId, String channelId, String playbackId, PlaybackSessionService.Kind kind, String resourceId, int rtpPort) {
         this(deviceId, channelId, playbackId, kind, resourceId, rtpPort, resourceId);
      }

      private PlaybackSession(
         String deviceId, String channelId, String playbackId, PlaybackSessionService.Kind kind, String resourceId, int rtpPort, String snapshotStream
      ) {
         this.deviceId = deviceId;
         this.channelId = channelId;
         this.playbackId = playbackId;
         this.kind = kind;
         this.resourceId = resourceId;
         this.rtpPort = rtpPort;
         this.snapshotStream = snapshotStream == null ? "" : snapshotStream;
      }
   }

   public record PlaybackSettings(
      int maxActive,
      int heapHighWatermarkPercent,
      int systemMemoryHighWatermarkPercent,
      int pressureEvictionsPerSweep,
      long pressureEvictionCooldownSeconds,
      long viewerTimeoutSeconds,
      long startupTimeoutSeconds,
      boolean memoryPressureEnabled
   ) {
   }

   public record ReusableStream(String kind, String app, String stream, String playbackId, String channelId) {
   }

   public record StopResult(boolean found, boolean signalStopped, boolean mediaClosed, String message) {
      public boolean byeSent() {
         return this.signalStopped;
      }

      public boolean rtpClosed() {
         return this.mediaClosed;
      }
   }
}
