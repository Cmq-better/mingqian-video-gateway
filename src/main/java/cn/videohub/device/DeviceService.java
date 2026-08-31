package cn.videohub.device;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class DeviceService {
   private static final Logger log = LoggerFactory.getLogger(DeviceService.class);
   private final Map<String, DeviceService.Device> devices = new ConcurrentHashMap<>();
   private final Map<String, InetSocketAddress> sipSessions = new ConcurrentHashMap<>();
   private final Map<String, List<DeviceService.Channel>> channelCatalog = new ConcurrentHashMap<>();
   private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
   private boolean recoveredFromBackup;
   @Value("${video-hub.data-file:./data/devices.json}")
   private String dataFile = "./data/devices.json";
   @Value("${video-hub.session-timeout-seconds:180}")
   private long sessionTimeoutSeconds = 180L;

   @PostConstruct
   void initialize() {
      this.load();
   }

   public Collection<DeviceService.Device> list() {
      return this.devices.values().stream().sorted(Comparator.comparing(DeviceService.Device::name)).toList();
   }

   public Collection<DeviceService.DeviceView> views() {
      return this.list()
         .stream()
         .map(
            device -> new DeviceService.DeviceView(
               device.id(),
               device.name(),
               device.protocol(),
               device.status(),
               device.manufacturer(),
               device.host(),
               device.port(),
               device.lastSeen(),
               device.channels(),
               device.streamUrl() != null && !device.streamUrl().isBlank(),
               maskedAddress(device.streamUrl())
            )
         )
         .toList();
   }

   public Optional<DeviceService.Device> find(String id) {
      return Optional.ofNullable(this.devices.get(id));
   }

   public DeviceService.Device upsertManual(DeviceService.DeviceInput input) {
      String id = required(input.id(), "\u8bbe\u5907 ID");
      DeviceService.Device old = this.devices.get(id);
      DeviceService.Device device = new DeviceService.Device(
         id,
         value(input.name(), id),
         value(input.protocol(), "HTTPS"),
         old == null ? "OFFLINE" : old.status(),
         value(input.manufacturer(), "Unknown"),
         valueOrNull(input.host()),
         validPort(input.port()),
         old == null ? null : old.lastSeen(),
         input.channels() == null ? (old == null ? 1 : old.channels()) : Math.max(0, input.channels()),
         valueOrNull(input.streamUrl())
      );
      this.devices.put(id, device);

      try {
         this.persist();
         return device;
      } catch (RuntimeException e) {
         if (old == null) {
            this.devices.remove(id, device);
         } else {
            this.devices.put(id, old);
         }

         throw e;
      }
   }

   public DeviceService.Device require(String id) {
      DeviceService.Device device = this.devices.get(id);
      if (device == null) {
         throw new IllegalArgumentException("\u8bbe\u5907\u4e0d\u5b58\u5728: " + id);
      } else {
         return device;
      }
   }

   public void delete(String id) {
      DeviceService.Device removed = this.devices.remove(id);
      if (removed == null) {
         throw new IllegalArgumentException("\u8bbe\u5907\u4e0d\u5b58\u5728: " + id);
      }

      InetSocketAddress session = this.sipSessions.remove(id);
      List<DeviceService.Channel> catalog = this.channelCatalog.remove(id);

      try {
         this.persist();
      } catch (RuntimeException e) {
         this.devices.put(id, removed);
         if (session != null) {
            this.sipSessions.put(id, session);
         }

         if (catalog != null) {
            this.channelCatalog.put(id, catalog);
         }

         throw e;
      }
   }

   public List<DeviceService.Channel> channels(String id) {
      DeviceService.Device device = this.require(id);
      List<DeviceService.Channel> actual = this.channelCatalog.get(id);
      if (actual != null && !actual.isEmpty()) {
         return namedChannels(device, actual);
      }

      int count = Math.max(0, device.channels());
      return IntStream.range(0, count)
         .mapToObj(
            index -> new DeviceService.Channel(
               channelId(device.id(), index + 1), count == 1 ? device.name() : device.name() + " \u00b7 \u7b2c" + (index + 1) + "\u8def", device.status(), null
            )
         )
         .toList();
   }

   public DeviceService.ChannelPage channelPage(int offset, int limit, String query) {
      int safeOffset = Math.max(0, offset);
      int safeLimit = Math.max(1, Math.min(100, limit));
      String keyword = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
      List<DeviceService.ChannelView> all = this.list()
         .stream()
         .flatMap(
            device -> this.channels(device.id())
               .stream()
               .map(
                  channel -> new DeviceService.ChannelView(
                     channel.id(), channel.name(), channel.status(), channel.streamUrl(), device.id(), device.name(), device.protocol()
                  )
               )
         )
         .filter(channel -> keyword.isBlank() || (channel.name() + " " + channel.id() + " " + channel.deviceName()).toLowerCase(Locale.ROOT).contains(keyword))
         .toList();
      List<DeviceService.ChannelView> items = all.stream().skip(safeOffset).limit(safeLimit).toList();
      return new DeviceService.ChannelPage(items, safeOffset, safeLimit, all.size(), safeOffset + items.size() < all.size());
   }

   public DeviceService.Device registerGb(String id, String host, int port, InetSocketAddress source) {
      DeviceService.Device old = this.devices.get(id);
      DeviceService.Device current = new DeviceService.Device(
         id,
         old == null ? "GB\u8bbe\u5907 " + tail(id) : old.name(),
         "GB28181",
         "ONLINE",
         old == null ? "Hikvision/GB" : old.manufacturer(),
         host,
         port,
         Instant.now(),
         old == null ? 0 : old.channels(),
         old == null ? null : old.streamUrl()
      );
      this.devices.put(id, current);
      this.sipSessions.put(id, source);
      this.persist();
      return current;
   }

   public boolean heartbeat(String id, InetSocketAddress source) {
      boolean sessionRestored = source != null && this.sipSessions.put(id, source) == null;
      this.devices
         .computeIfPresent(
            id,
            (key, old) -> new DeviceService.Device(
               old.id(), old.name(), old.protocol(), "ONLINE", old.manufacturer(), old.host(), old.port(), Instant.now(), old.channels(), old.streamUrl()
            )
         );
      this.persist();
      return sessionRestored;
   }

   public void unregisterGb(String id) {
      this.sipSessions.remove(id);
      this.markConnection(id, false);
   }

   public void updateChannels(String id, int count) {
      this.devices
         .computeIfPresent(
            id,
            (key, old) -> new DeviceService.Device(
               old.id(), old.name(), old.protocol(), old.status(), old.manufacturer(), old.host(), old.port(), Instant.now(), count, old.streamUrl()
            )
         );
      this.persist();
   }

   public synchronized void updateChannelCatalog(String id, List<DeviceService.Channel> channels) {
      LinkedHashMap<String, DeviceService.Channel> merged = new LinkedHashMap<>();
      this.channelCatalog
         .getOrDefault(id, List.of())
         .stream()
         .filter(channel -> isVideoChannel(channel.id()))
         .forEach(channel -> merged.put(channel.id(), channel));
      channels.stream().filter(channel -> isVideoChannel(channel.id())).forEach(channel -> merged.put(channel.id(), channel));
      if (!merged.isEmpty()) {
         List<DeviceService.Channel> videoChannels = List.copyOf(merged.values());
         this.channelCatalog.put(id, videoChannels);
         this.updateChannels(id, videoChannels.size());
      }
   }

   public DeviceService.Device markConnection(String id, boolean online) {
      DeviceService.Device updated = this.devices
         .computeIfPresent(
            id,
            (key, old) -> new DeviceService.Device(
               old.id(),
               old.name(),
               old.protocol(),
               online ? "ONLINE" : "OFFLINE",
               old.manufacturer(),
               old.host(),
               old.port(),
               online ? Instant.now() : old.lastSeen(),
               old.channels(),
               old.streamUrl()
            )
         );
      this.persist();
      return updated;
   }

   public void expireSipSessions(Duration timeout) {
      Instant deadline = Instant.now().minus(timeout);
      this.sipSessions.keySet().removeIf(id -> {
         DeviceService.Device device = this.devices.get(id);
         boolean expired = device == null || device.lastSeen() == null || device.lastSeen().isBefore(deadline);
         if (expired && device != null) {
            this.markConnection(id, false);
         }

         return expired;
      });
   }

   @Scheduled(fixedDelay = 30000L)
   void expireInactiveSessions() {
      this.expireSipSessions(Duration.ofSeconds(Math.max(60L, this.sessionTimeoutSeconds)));
   }

   public Optional<InetSocketAddress> session(String id) {
      return Optional.ofNullable(this.sipSessions.get(id));
   }

   public DeviceService.Dashboard dashboard() {
      long online = this.devices.values().stream().filter(d -> "ONLINE".equals(d.status())).count();
      int channels = this.devices.values().stream().mapToInt(DeviceService.Device::channels).sum();
      return new DeviceService.Dashboard(this.devices.size(), online, channels, this.sipSessions.size());
   }

   public Map<String, Object> storageStatus() {
      Path path = Path.of(this.dataFile).toAbsolutePath().normalize();
      Path parent = path.getParent();
      boolean writable = Files.exists(path) ? Files.isWritable(path) : parent != null && Files.isDirectory(parent) && Files.isWritable(parent);
      return Map.of(
         "file",
         path.toString(),
         "exists",
         Files.isRegularFile(path),
         "writable",
         writable,
         "backupExists",
         Files.isRegularFile(path.resolveSibling(path.getFileName() + ".bak"))
      );
   }

   private static String tail(String id) {
      return id.length() <= 6 ? id : id.substring(id.length() - 6);
   }

   private static String value(String value, String fallback) {
      return value != null && !value.isBlank() ? value.trim() : fallback;
   }

   private static String valueOrNull(String value) {
      return value != null && !value.isBlank() ? value.trim() : null;
   }

   private static Integer validPort(Integer port) {
      if (port == null) {
         return null;
      } else if (port >= 1 && port <= 65535) {
         return port;
      } else {
         throw new IllegalArgumentException("\u7aef\u53e3\u5fc5\u987b\u5728 1 \u5230 65535 \u4e4b\u95f4");
      }
   }

   private static String channelId(String deviceId, int index) {
      return deviceId.matches("\\d{20}")
         ? deviceId.substring(0, 12) + deviceId.substring(16) + String.format("%04d", index)
         : deviceId + "-CH" + String.format("%02d", index);
   }

   private static boolean isVideoChannel(String id) {
      if (id != null && id.matches("\\d{20}")) {
         String type = id.substring(10, 13);
         return "131".equals(type) || "132".equals(type);
      } else {
         return false;
      }
   }

   private static List<DeviceService.Channel> namedChannels(DeviceService.Device device, List<DeviceService.Channel> channels) {
      return IntStream.range(0, channels.size())
         .mapToObj(
            index -> {
               DeviceService.Channel channel = channels.get(index);
               String raw = channel.name() == null ? "" : channel.name().trim();
               boolean generic = raw.isBlank()
                  || raw.equals(channel.id())
                  || raw.matches("(?i)^(camera|channel|ipc|d|ch)[-_ ]*\\d+$")
                  || raw.matches("(?i)^\u89c6\u9891\u901a\u9053[-_ ]*\\d+$");
               String localName = generic ? "\u7b2c" + (index + 1) + "\u8def" : raw;
               String name = localName.startsWith(device.name()) ? localName : device.name() + " \u00b7 " + localName;
               return new DeviceService.Channel(channel.id(), name, channel.status(), channel.streamUrl());
            }
         )
         .toList();
   }

   private static String required(String value, String label) {
      if (value != null && !value.isBlank()) {
         return value.trim();
      } else {
         throw new IllegalArgumentException(label + "\u4e0d\u80fd\u4e3a\u7a7a");
      }
   }

   private synchronized void load() {
      Path path = Path.of(this.dataFile).toAbsolutePath().normalize();
      Path backup = path.resolveSibling(path.getFileName() + ".bak");

      try {
         if (!Files.isRegularFile(path)) {
            if (Files.isRegularFile(backup)) {
               this.restoreDevices((List<DeviceService.Device>)this.mapper.readValue(backup.toFile(), new TypeReference<List<DeviceService.Device>>() {}));
               this.recoveredFromBackup = true;
               log.warn("\u8bbe\u5907\u4e3b\u6570\u636e\u6587\u4ef6\u4e0d\u5b58\u5728\uff0c\u5df2\u4ece\u5907\u4efd\u6062\u590d\u8bfb\u53d6\uff1a{}", backup);
            }

            return;
         }

         this.restoreDevices((List<DeviceService.Device>)this.mapper.readValue(path.toFile(), new TypeReference<List<DeviceService.Device>>() {}));
      } catch (Exception var6) {
         Exception primaryError = var6;

         try {
            if (!Files.isRegularFile(backup)) {
               throw primaryError;
            }

            this.restoreDevices((List<DeviceService.Device>)this.mapper.readValue(backup.toFile(), new TypeReference<List<DeviceService.Device>>() {}));
            this.recoveredFromBackup = true;
            log.error(
               "\u8bbe\u5907\u6570\u636e\u6587\u4ef6\u635f\u574f\uff0c\u5df2\u4ece\u5907\u4efd\u6062\u590d\u8bfb\u53d6\uff1a{}\uff1b\u539f\u9519\u8bef\uff1a{}",
               backup,
               primaryError.getMessage()
            );
         } catch (Exception backupError) {
            throw new IllegalStateException(
               "\u8bbe\u5907\u6570\u636e\u8bfb\u53d6\u5931\u8d25\uff0c\u4e3a\u907f\u514d\u8986\u76d6\u539f\u6570\u636e\u5df2\u505c\u6b62\u542f\u52a8\uff1a"
                  + path
                  + "\uff1b"
                  + var6.getMessage(),
               var6
            );
         }
      }
   }

   private void restoreDevices(List<DeviceService.Device> saved) {
      saved.forEach(
         device -> this.devices
            .put(
               device.id(),
               new DeviceService.Device(
                  device.id(),
                  device.name(),
                  device.protocol(),
                  "GB28181".equals(device.protocol()) ? "OFFLINE" : device.status(),
                  device.manufacturer(),
                  device.host(),
                  device.port(),
                  device.lastSeen(),
                  device.channels(),
                  device.streamUrl()
               )
            )
      );
   }

   private synchronized void persist() {
      try {
         Path path = Path.of(this.dataFile).toAbsolutePath().normalize();
         if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
         }

         Path temp = path.resolveSibling(path.getFileName() + ".tmp");
         this.mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), this.list());
         if (!this.recoveredFromBackup && Files.isRegularFile(path)) {
            Files.copy(path, path.resolveSibling(path.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
         }

         try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (Exception ignored) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
         }

         this.recoveredFromBackup = false;
      } catch (Exception e) {
         log.error("\u8bbe\u5907\u6570\u636e\u4fdd\u5b58\u5931\u8d25\uff1a{}", this.dataFile, e);
         throw new IllegalStateException(
            "\u8bbe\u5907\u6570\u636e\u4fdd\u5b58\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5 data \u76ee\u5f55\u6743\u9650\u548c\u78c1\u76d8\u7a7a\u95f4\uff1a"
               + e.getMessage(),
            e
         );
      }
   }

   private static String maskedAddress(String url) {
      return url != null && !url.isBlank() ? url.replaceFirst("(?i)(://)[^/@]+@", "$1***:***@") : null;
   }

   public record Channel(String id, String name, String status, String streamUrl) {
   }

   public record ChannelPage(List<DeviceService.ChannelView> items, int offset, int limit, int total, boolean hasMore) {
   }

   public record ChannelView(String id, String name, String status, String streamUrl, String deviceId, String deviceName, String protocol) {
   }

   public record Dashboard(long devices, long online, int channels, int sipSessions) {
   }

   public record Device(
      String id, String name, String protocol, String status, String manufacturer, String host, Integer port, Instant lastSeen, int channels, String streamUrl
   ) {
   }

   public record DeviceInput(String id, String name, String protocol, String manufacturer, String host, Integer port, String streamUrl, Integer channels) {
   }

   public record DeviceView(
      String id,
      String name,
      String protocol,
      String status,
      String manufacturer,
      String host,
      Integer port,
      Instant lastSeen,
      int channels,
      boolean configured,
      String maskedStreamUrl
   ) {
   }
}
