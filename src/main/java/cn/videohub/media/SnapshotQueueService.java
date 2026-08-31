package cn.videohub.media;

import cn.videohub.device.DeviceService;
import cn.videohub.sip.Gb28181SipServer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SnapshotQueueService {
   private static final Logger log = LoggerFactory.getLogger(SnapshotQueueService.class);
   private final DeviceService devices;
   private final PlaybackSessionService playbacks;
   private final MediaService media;
   private final FfmpegGateway ffmpeg;
   private final Gb28181SipServer sip;
   private final Map<String, SnapshotQueueService.CachedFrame> cache = new ConcurrentHashMap<>();
   private final Map<String, CompletableFuture<SnapshotQueueService.SnapshotResult>> pendingByChannel = new ConcurrentHashMap<>();
   private final AtomicLong accepted = new AtomicLong();
   private final AtomicLong completed = new AtomicLong();
   private final AtomicLong rejected = new AtomicLong();
   private final AtomicLong cacheHits = new AtomicLong();
   private final AtomicLong reusedLive = new AtomicLong();
   @Value("${video-hub.snapshot.queue-capacity:12}")
   private int queueCapacity = 12;
   @Value("${video-hub.snapshot.interval-millis:800}")
   private long intervalMillis = 800L;
   @Value("${video-hub.snapshot.cache-seconds:8}")
   private long cacheSeconds = 8L;
   @Value("${video-hub.snapshot.cache-entries:8}")
   private int cacheEntries = 8;
   @Value("${video-hub.snapshot.media-ready-timeout-seconds:10}")
   private long mediaReadyTimeoutSeconds = 10L;
   @Value("${video-hub.snapshot.request-timeout-seconds:45}")
   private long requestTimeoutSeconds = 45L;
   private volatile ArrayBlockingQueue<SnapshotQueueService.Job> queue;
   private volatile Thread worker;
   private volatile boolean running;
   private volatile Instant lastCompletedAt;

   public SnapshotQueueService(DeviceService devices, PlaybackSessionService playbacks, MediaService media, FfmpegGateway ffmpeg, Gb28181SipServer sip) {
      this.devices = devices;
      this.playbacks = playbacks;
      this.media = media;
      this.ffmpeg = ffmpeg;
      this.sip = sip;
   }

   @PostConstruct
   void start() {
      this.queue = new ArrayBlockingQueue<>(Math.max(1, Math.min(100, this.queueCapacity)));
      this.running = true;
      this.worker = Thread.ofVirtual().name("snapshot-queue-worker").start(this::workLoop);
   }

   public SnapshotQueueService.SnapshotResult request(String deviceId, String requestedChannelId) {
      DeviceService.Device device = this.devices.require(deviceId);
      String channelId = requestedChannelId != null && !requestedChannelId.isBlank() ? requestedChannelId.trim() : this.defaultChannel(device);
      this.validateChannel(deviceId, channelId);
      String key = deviceId + "/" + channelId;
      SnapshotQueueService.CachedFrame cached = this.freshCache(key);
      if (cached != null) {
         this.cacheHits.incrementAndGet();
         return new SnapshotQueueService.SnapshotResult(cached.image(), "CACHE", true, 0L, cached.createdAt());
      }

      CompletableFuture<SnapshotQueueService.SnapshotResult> created = new CompletableFuture<>();
      CompletableFuture<SnapshotQueueService.SnapshotResult> future = this.pendingByChannel.putIfAbsent(key, created);
      if (future == null) {
         future = created;
         SnapshotQueueService.Job job = new SnapshotQueueService.Job(key, device, channelId, Instant.now(), created);
         if (!this.running || !this.queue.offer(job)) {
            this.pendingByChannel.remove(key, created);
            this.rejected.incrementAndGet();
            throw new SnapshotQueueService.UnavailableException("\u62bd\u5e27\u961f\u5217\u5df2\u6ee1\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5");
         }

         this.accepted.incrementAndGet();
      }

      try {
         return future.get(Math.max(10L, this.requestTimeoutSeconds), TimeUnit.SECONDS);
      } catch (TimeoutException e) {
         throw new SnapshotQueueService.UnavailableException("\u62bd\u5e27\u4efb\u52a1\u4ecd\u5728\u6392\u961f\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5");
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw new SnapshotQueueService.UnavailableException("\u62bd\u5e27\u8bf7\u6c42\u5df2\u4e2d\u65ad");
      } catch (ExecutionException e) {
         Throwable cause = e.getCause();
         if (cause instanceof RuntimeException runtime) {
            throw runtime;
         } else {
            throw new IllegalStateException("\u62bd\u5e27\u5931\u8d25", cause);
         }
      }
   }

   private void workLoop() {
      while (this.running || !this.queue.isEmpty()) {
         SnapshotQueueService.Job job;
         try {
            job = this.queue.poll(1L, TimeUnit.SECONDS);
            if (job == null) {
               continue;
            }
         } catch (InterruptedException e) {
            if (this.running) {
               continue;
            }
            break;
         }

         long waitMillis = Duration.between(job.queuedAt(), Instant.now()).toMillis();

         try {
            SnapshotQueueService.SnapshotResult result = this.execute(job, Math.max(0L, waitMillis));
            this.cache.put(job.key(), new SnapshotQueueService.CachedFrame(result.image(), Instant.now()));
            this.pruneCache();
            this.completed.incrementAndGet();
            this.lastCompletedAt = Instant.now();
            job.future().complete(result);
         } catch (Throwable e) {
            job.future().completeExceptionally(e);
            log.warn("Snapshot failed device={} channel={}: {}", new Object[]{job.device().id(), job.channelId(), e.getMessage()});
         } finally {
            this.pendingByChannel.remove(job.key(), job.future());
            this.coolDown();
         }
      }
   }

   private SnapshotQueueService.SnapshotResult execute(SnapshotQueueService.Job job, long waitMillis) {
      PlaybackSessionService.ReusableStream live = this.playbacks.findReusableStream(job.device().id(), job.channelId());
      if (live != null) {
         byte[] image = "FFMPEG_HLS".equals(live.kind()) ? this.ffmpeg.snapshotRunning(live.stream()) : this.media.snapshotStream(live.app(), live.stream());
         this.reusedLive.incrementAndGet();
         return new SnapshotQueueService.SnapshotResult(image, "LIVE_REUSE", false, waitMillis, Instant.now());
      } else if (job.device().streamUrl() != null && !job.device().streamUrl().isBlank()) {
         return new SnapshotQueueService.SnapshotResult(this.media.snapshot(job.device()), "DEVICE_STREAM", false, waitMillis, Instant.now());
      } else if (!"GB28181".equalsIgnoreCase(job.device().protocol())) {
         throw new IllegalArgumentException("\u8bbe\u5907\u6ca1\u6709\u53ef\u62bd\u5e27\u7684\u89c6\u9891\u5730\u5740");
      } else if (!this.playbacks.canStartBackgroundStream()) {
         this.rejected.incrementAndGet();
         throw new SnapshotQueueService.UnavailableException(
            "\u76f4\u64ad\u8d44\u6e90\u6216\u5185\u5b58\u63a5\u8fd1\u4e0a\u9650\uff0c\u62bd\u5e27\u5df2\u5ef6\u540e\uff1b\u4e0d\u4f1a\u91ca\u653e\u6b63\u5728\u76f4\u64ad\u7684\u901a\u9053"
         );
      } else {
         return this.temporaryGbSnapshot(job, waitMillis);
      }
   }

   private SnapshotQueueService.SnapshotResult temporaryGbSnapshot(SnapshotQueueService.Job job, long waitMillis) {
      String ssrc = this.sip.nextSsrc();
      int rtpPort = this.media.openRtpServer(ssrc);
      boolean invited = false;

      try {
         this.sip.invite(job.device().id(), job.channelId(), ssrc, rtpPort, 1);
         invited = true;
         Instant deadline = Instant.now().plusSeconds(Math.max(3L, this.mediaReadyTimeoutSeconds));

         while (Instant.now().isBefore(deadline)) {
            if ("MEDIA_READY".equals(this.media.rtpStatus(ssrc, rtpPort).get("state"))) {
               byte[] image = this.media.snapshotStream("rtp", ssrc);
               return new SnapshotQueueService.SnapshotResult(image, "TEMPORARY_GB28181_SUBSTREAM", false, waitMillis, Instant.now());
            }

            try {
               Thread.sleep(250L);
            } catch (InterruptedException e) {
               Thread.currentThread().interrupt();
               throw new SnapshotQueueService.UnavailableException("\u62bd\u5e27\u4efb\u52a1\u5df2\u4e2d\u65ad");
            }
         }

         throw new IllegalStateException("GB28181 \u5b50\u7801\u6d41\u542f\u52a8\u8d85\u65f6\uff0c\u672a\u53d6\u5f97\u53ef\u62bd\u5e27\u753b\u9762");
      } finally {
         if (invited) {
            this.sip.stopStream(ssrc);
         }

         this.media.closeRtpServer(ssrc);
      }
   }

   public Map<String, Object> status() {
      LinkedHashMap<String, Object> result = new LinkedHashMap<>();
      result.put("running", this.running);
      result.put("queued", this.queue == null ? 0 : this.queue.size());
      result.put("capacity", this.queue == null ? Math.max(1, this.queueCapacity) : this.queue.size() + this.queue.remainingCapacity());
      result.put("processing", this.pendingByChannel.size() - (this.queue == null ? 0 : this.queue.size()));
      result.put("accepted", this.accepted.get());
      result.put("completed", this.completed.get());
      result.put("rejected", this.rejected.get());
      result.put("cacheHits", this.cacheHits.get());
      result.put("liveReuse", this.reusedLive.get());
      result.put("intervalMillis", Math.max(0L, this.intervalMillis));
      result.put("cacheSeconds", Math.max(0L, this.cacheSeconds));
      result.put("lastCompletedAt", this.lastCompletedAt);
      result.put("policy", "SERIAL_LIVE_REUSE_FIRST");
      return result;
   }

   private String defaultChannel(DeviceService.Device device) {
      return this.devices.channels(device.id()).stream().findFirst().map(DeviceService.Channel::id).orElse(device.id());
   }

   private void validateChannel(String deviceId, String channelId) {
      boolean known = this.devices.channels(deviceId).stream().anyMatch(channel -> Objects.equals(channel.id(), channelId));
      if (!known && !Objects.equals(deviceId, channelId)) {
         throw new IllegalArgumentException("\u901a\u9053\u4e0d\u5c5e\u4e8e\u8be5\u8bbe\u5907: " + channelId);
      }
   }

   private SnapshotQueueService.CachedFrame freshCache(String key) {
      SnapshotQueueService.CachedFrame value = this.cache.get(key);
      if (value == null) {
         return null;
      }

      if (value.createdAt().plusSeconds(Math.max(0L, this.cacheSeconds)).isAfter(Instant.now())) {
         return value;
      }

      this.cache.remove(key, value);
      return null;
   }

   private void pruneCache() {
      Instant deadline = Instant.now().minusSeconds(Math.max(0L, this.cacheSeconds));
      this.cache.entrySet().removeIf(entry -> entry.getValue().createdAt().isBefore(deadline));

      while (this.cache.size() > Math.max(1, this.cacheEntries)) {
         String oldest = this.cache
            .entrySet()
            .stream()
            .min(Entry.comparingByValue((a, b) -> a.createdAt().compareTo(b.createdAt())))
            .map(Entry::getKey)
            .orElse(null);
         if (oldest == null) {
            break;
         }

         this.cache.remove(oldest);
      }
   }

   private void coolDown() {
      long delay = Math.max(0L, Math.min(10000L, this.intervalMillis));
      if (delay != 0L) {
         try {
            Thread.sleep(delay);
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         }
      }
   }

   @PreDestroy
   void stop() {
      this.running = false;
      if (this.worker != null) {
         this.worker.interrupt();
      }

      SnapshotQueueService.Job job;
      if (this.queue != null) {
         while ((job = this.queue.poll()) != null) {
            job.future().completeExceptionally(new SnapshotQueueService.UnavailableException("\u670d\u52a1\u6b63\u5728\u505c\u6b62"));
         }
      }

      this.cache.clear();
   }

   private record CachedFrame(byte[] image, Instant createdAt) {
   }

   private record Job(
      String key, DeviceService.Device device, String channelId, Instant queuedAt, CompletableFuture<SnapshotQueueService.SnapshotResult> future
   ) {
   }

   public record SnapshotResult(byte[] image, String source, boolean cached, long queueWaitMillis, Instant capturedAt) {
   }

   public static class UnavailableException extends RuntimeException {
      public UnavailableException(String message) {
         super(message);
      }
   }
}
