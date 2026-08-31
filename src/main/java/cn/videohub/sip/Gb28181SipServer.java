package cn.videohub.sip;

import cn.videohub.device.DeviceService;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class Gb28181SipServer implements SmartLifecycle {
   private static final Logger log = LoggerFactory.getLogger(Gb28181SipServer.class);
   private static final Pattern SIP_ID = Pattern.compile("sip:(\\d{20})@", 2);
   private static final Pattern FROM_ID = Pattern.compile("(?im)^From\\s*:.*?sip:(\\d{20})@");
   private static final Pattern XML_ID = Pattern.compile("<DeviceID>([^<]+)</DeviceID>", 2);
   private static final Pattern DEVICE_ITEM = Pattern.compile("<Item>", 2);
   private static final Pattern ITEM_BLOCK = Pattern.compile("<Item>(.*?)</Item>", 34);
   private static final Pattern XML_NAME = Pattern.compile("<Name>([^<]*)</Name>", 2);
   private static final Pattern XML_STATUS = Pattern.compile("<Status>([^<]*)</Status>", 2);
   private final DeviceService devices;
   private final AtomicInteger sequence = new AtomicInteger(1);
   private final SecureRandom random = new SecureRandom();
   private final Map<String, String> nonces = new ConcurrentHashMap<>();
   private final Map<String, Gb28181SipServer.InviteDialog> dialogsByCallId = new ConcurrentHashMap<>();
   private final Map<String, Gb28181SipServer.InviteDialog> dialogsBySsrc = new ConcurrentHashMap<>();
   private volatile boolean running;
   private DatagramSocket socket;
   @Value("${video-hub.sip-port:5060}")
   private int port;
   @Value("${video-hub.platform-id}")
   private String platformId;
   @Value("${video-hub.platform-domain}")
   private String domain;
   @Value("${video-hub.public-ip:127.0.0.1}")
   private String publicIp;
   @Value("${video-hub.media.rtp-port:10000}")
   private int rtpPort;
   @Value("${video-hub.sip-auth-enabled:false}")
   private boolean authEnabled;
   @Value("${video-hub.sip-password:}")
   private String sipPassword;

   public Gb28181SipServer(DeviceService devices) {
      this.devices = devices;
   }

   public void start() {
      try {
         this.socket = new DatagramSocket(this.port);
         this.running = true;
         Thread.ofVirtual().name("gb28181-sip").start(this::receiveLoop);
         log.info("GB28181 SIP UDP listening on 0.0.0.0:{} as {}", this.port, this.platformId);
      } catch (Exception e) {
         throw new IllegalStateException("\u65e0\u6cd5\u76d1\u542c GB28181 SIP UDP \u7aef\u53e3 " + this.port, e);
      }
   }

   private void receiveLoop() {
      byte[] buffer = new byte[65535];

      while (this.running) {
         try {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            this.socket.receive(packet);
            byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
            String message = decodeSip(data);
            this.handle(message, new InetSocketAddress(packet.getAddress(), packet.getPort()));
         } catch (Exception e) {
            if (this.running) {
               log.warn("SIP packet handling failed: {}", e.getMessage());
            }
         }
      }
   }

   private void handle(String message, InetSocketAddress source) throws Exception {
      String first = message.lines().findFirst().orElse("");
      if (first.startsWith("SIP/2.0")) {
         this.handleResponse(message, source);
      } else {
         String deviceId = first.startsWith("REGISTER") ? extract(FROM_ID, message) : extract(XML_ID, message);
         if (deviceId == null) {
            deviceId = extract(SIP_ID, message);
         }

         if (deviceId != null) {
            if (first.startsWith("REGISTER")) {
               if (this.authEnabled) {
                  if (this.nonces.size() > 4096) {
                     this.nonces.clear();
                  }

                  String nonce;
                  if (!digestValid(message, this.sipPassword, this.domain, nonce = this.nonces.computeIfAbsent(deviceId, ignored -> this.randomHex(16)))) {
                     this.send(this.buildUnauthorized(message, nonce), source);
                     return;
                  }

                  this.nonces.remove(deviceId);
               }

               this.send(this.buildOk(message), source);
               if ("0".equals(header(message, "Expires"))) {
                  this.devices.unregisterGb(deviceId);
                  return;
               }

               this.devices.registerGb(deviceId, source.getAddress().getHostAddress(), source.getPort(), source);
               log.info("GB28181 device registered: {} from {}", deviceId, source);
               String registeredId = deviceId;
               Thread.ofVirtual().start(() -> {
                  try {
                     Thread.sleep(250L);
                     this.sendCatalog(registeredId);
                  } catch (Exception e) {
                     log.debug("Catalog query failed: {}", e.getMessage());
                  }
               });
            } else if (first.startsWith("MESSAGE")) {
               this.send(this.buildOk(message), source);
               boolean restored = this.devices.heartbeat(deviceId, source);
               if (restored && message.toLowerCase(Locale.ROOT).contains("<cmdtype>keepalive</cmdtype>")) {
                  String restoredId = deviceId;
                  Thread.ofVirtual().start(() -> {
                     try {
                        Thread.sleep(250L);
                        this.sendCatalog(restoredId);
                     } catch (Exception e) {
                        log.debug("Catalog refresh after heartbeat failed: {}", e.getMessage());
                     }
                  });
               }

               if (message.toLowerCase(Locale.ROOT).contains("<cmdtype>catalog</cmdtype>")) {
                  List<DeviceService.Channel> channels = parseCatalog(message);
                  if (channels.isEmpty()) {
                     this.devices.updateChannels(deviceId, count(DEVICE_ITEM, message));
                  } else {
                     this.devices.updateChannelCatalog(deviceId, channels);
                  }
               }
            } else {
               this.send(this.buildOk(message), source);
            }
         }
      }
   }

   private void handleResponse(String message, InetSocketAddress source) throws Exception {
      String cseq = header(message, "CSeq");
      if (message.startsWith("SIP/2.0 200") && cseq != null) {
         String callId = header(message, "Call-ID");
         if (cseq.toUpperCase(Locale.ROOT).endsWith("BYE")) {
            Gb28181SipServer.InviteDialog ended = this.dialogsByCallId.get(callId);
            if (ended != null) {
               this.cleanupDialog(ended);
            }
         } else if (cseq.toUpperCase(Locale.ROOT).endsWith("INVITE")) {
            String number = cseq.split("\\s+")[0];
            String from = header(message, "From");
            String to = header(message, "To");
            String contact = header(message, "Contact");
            String requestUri = contact == null
               ? "sip:" + source.getAddress().getHostAddress() + ":" + source.getPort()
               : contact.replaceAll(".*<([^>]+)>.*", "$1");
            String ack = "ACK "
               + requestUri
               + " SIP/2.0\r\nVia: SIP/2.0/UDP "
               + this.publicIp
               + ":"
               + this.port
               + ";branch=z9hG4bK"
               + this.randomHex(8)
               + "\r\nFrom: "
               + from
               + "\r\nTo: "
               + to
               + "\r\nCall-ID: "
               + callId
               + "\r\nCSeq: "
               + number
               + " ACK\r\nMax-Forwards: 70\r\nContent-Length: 0\r\n\r\n";
            this.send(ack, source);
            Gb28181SipServer.InviteDialog dialog = this.dialogsByCallId.get(callId);
            if (dialog != null) {
               dialog.remoteTo = to;
               dialog.remoteUri = requestUri;
               dialog.target = source;
               dialog.confirmed = true;
               if (dialog.stopping && !dialog.terminating) {
                  this.sendBye(dialog);
               }
            }

            log.info("GB28181 INVITE accepted callId={} from={}", callId, source);
         }
      }
   }

   public void sendCatalog(String deviceId) {
      InetSocketAddress target = this.devices
         .session(deviceId)
         .orElseThrow(() -> new IllegalStateException("\u8bbe\u5907\u6ca1\u6709\u5728\u7ebf SIP \u4f1a\u8bdd"));
      String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n<Query>\r\n<CmdType>Catalog</CmdType>\r\n<SN>"
         + this.sequence.incrementAndGet()
         + "</SN>\r\n<DeviceID>"
         + deviceId
         + "</DeviceID>\r\n</Query>";
      this.sendRequest("MESSAGE", deviceId, target, "Application/MANSCDP+xml", body);
   }

   public void sendPtz(String deviceId, String channelId, String action, int speed) {
      InetSocketAddress target = this.devices
         .session(deviceId)
         .orElseThrow(() -> new IllegalStateException("\u8bbe\u5907\u6ca1\u6709\u5728\u7ebf SIP \u4f1a\u8bdd"));
      String ptz = ptzCommand(action, speed);
      String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n<Control>\r\n<CmdType>DeviceControl</CmdType>\r\n<SN>"
         + this.sequence.incrementAndGet()
         + "</SN>\r\n<DeviceID>"
         + channelId
         + "</DeviceID>\r\n<PTZCmd>"
         + ptz
         + "</PTZCmd>\r\n</Control>";
      this.sendRequest("MESSAGE", deviceId, target, "Application/MANSCDP+xml", body);
   }

   public String invite(String deviceId, String channelId) {
      String ssrc = this.nextSsrc();
      this.invite(deviceId, channelId, ssrc, this.rtpPort, 0);
      return ssrc;
   }

   public void invite(String deviceId, String channelId, String ssrc, int receivePort) {
      this.invite(deviceId, channelId, ssrc, receivePort, 0);
   }

   public void invite(String deviceId, String channelId, String ssrc, int receivePort, int streamNumber) {
      InetSocketAddress target = this.devices
         .session(deviceId)
         .orElseThrow(() -> new IllegalStateException("\u8bbe\u5907\u6ca1\u6709\u5728\u7ebf SIP \u4f1a\u8bdd"));
      int profile = Math.max(0, Math.min(2, streamNumber));
      String body = "v=0\r\no="
         + channelId
         + " 0 0 IN IP4 "
         + this.publicIp
         + "\r\ns=Play\r\ni=Stream:"
         + profile
         + "\r\nu="
         + channelId
         + ":0\r\nc=IN IP4 "
         + this.publicIp
         + "\r\nt=0 0\r\nm=video "
         + receivePort
         + " RTP/AVP 96 98 97\r\na=recvonly\r\na=rtpmap:96 PS/90000\r\na=streamnumber:"
         + profile
         + "\r\ny="
         + ssrc
         + "\r\n";
      log.info(
         "GB28181 INVITE device={} channel={} ssrc={} rtpPort={} stream={} target={}", new Object[]{deviceId, channelId, ssrc, receivePort, profile, target}
      );

      try {
         String branch = "z9hG4bK" + Long.toHexString(this.random.nextLong());
         String callId = Long.toHexString(this.random.nextLong()) + "@" + this.publicIp;
         int cseq = this.sequence.incrementAndGet();
         String from = "<sip:" + this.platformId + "@" + this.domain + ">;tag=" + Integer.toHexString(this.random.nextInt());
         String to = "<sip:" + channelId + "@" + this.domain + ">";
         String requestUri = "sip:" + channelId + "@" + target.getAddress().getHostAddress() + ":" + target.getPort();
         byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
         String message = "INVITE "
            + requestUri
            + " SIP/2.0\r\nVia: SIP/2.0/UDP "
            + this.publicIp
            + ":"
            + this.port
            + ";branch="
            + branch
            + ";rport\r\nFrom: "
            + from
            + "\r\nTo: "
            + to
            + "\r\nContact: <sip:"
            + this.platformId
            + "@"
            + this.publicIp
            + ":"
            + this.port
            + ">\r\nCall-ID: "
            + callId
            + "\r\nCSeq: "
            + cseq
            + " INVITE\r\nMax-Forwards: 70\r\nSubject: "
            + channelId
            + ":"
            + ssrc
            + ","
            + this.platformId
            + ":0\r\nContent-Type: application/sdp\r\nContent-Length: "
            + bodyBytes.length
            + "\r\n\r\n"
            + body;
         Gb28181SipServer.InviteDialog dialog = new Gb28181SipServer.InviteDialog(deviceId, channelId, ssrc, callId, branch, cseq, from, to, requestUri, target);
         this.dialogsByCallId.put(callId, dialog);
         this.dialogsBySsrc.put(ssrc, dialog);
         this.send(message, target);
         this.scheduleInviteRetries(dialog, message);
      } catch (Exception e) {
         Gb28181SipServer.InviteDialog failed = this.dialogsBySsrc.remove(ssrc);
         if (failed != null) {
            this.dialogsByCallId.remove(failed.callId, failed);
         }

         throw new IllegalStateException("\u53d1\u9001 SIP \u70b9\u64ad\u6307\u4ee4\u5931\u8d25: " + e.getMessage(), e);
      }
   }

   public boolean stopStream(String ssrc) {
      Gb28181SipServer.InviteDialog dialog = this.dialogsBySsrc.get(ssrc);
      if (dialog == null) {
         return false;
      }

      dialog.stopping = true;

      try {
         if (dialog.confirmed) {
            this.sendBye(dialog);
         } else {
            this.sendCancel(dialog);
            Thread.ofVirtual().start(() -> {
               try {
                  Thread.sleep(30000L);
               } catch (InterruptedException ignored) {
                  Thread.currentThread().interrupt();
               }

               if (!dialog.confirmed) {
                  this.cleanupDialog(dialog);
               }
            });
         }

         return true;
      } catch (Exception e) {
         this.cleanupDialog(dialog);
         log.warn("GB28181 stop failed ssrc={}: {}", ssrc, e.getMessage());
         return false;
      }
   }

   private void sendCancel(Gb28181SipServer.InviteDialog dialog) throws Exception {
      String cancel = "CANCEL "
         + dialog.remoteUri
         + " SIP/2.0\r\nVia: SIP/2.0/UDP "
         + this.publicIp
         + ":"
         + this.port
         + ";branch="
         + dialog.branch
         + ";rport\r\nFrom: "
         + dialog.localFrom
         + "\r\nTo: "
         + dialog.remoteTo
         + "\r\nCall-ID: "
         + dialog.callId
         + "\r\nCSeq: "
         + dialog.inviteCseq
         + " CANCEL\r\nMax-Forwards: 70\r\nContent-Length: 0\r\n\r\n";
      this.send(cancel, dialog.target);
      log.info("GB28181 CANCEL sent device={} channel={} ssrc={}", new Object[]{dialog.deviceId, dialog.channelId, dialog.ssrc});
   }

   private void sendBye(Gb28181SipServer.InviteDialog dialog) throws Exception {
      synchronized (dialog) {
         if (dialog.terminating) {
            return;
         }

         dialog.terminating = true;
      }

      String bye = "BYE "
         + dialog.remoteUri
         + " SIP/2.0\r\nVia: SIP/2.0/UDP "
         + this.publicIp
         + ":"
         + this.port
         + ";branch=z9hG4bK"
         + this.randomHex(8)
         + ";rport\r\nFrom: "
         + dialog.localFrom
         + "\r\nTo: "
         + dialog.remoteTo
         + "\r\nCall-ID: "
         + dialog.callId
         + "\r\nCSeq: "
         + (dialog.inviteCseq + 1)
         + " BYE\r\nContact: <sip:"
         + this.platformId
         + "@"
         + this.publicIp
         + ":"
         + this.port
         + ">\r\nMax-Forwards: 70\r\nContent-Length: 0\r\n\r\n";
      this.send(bye, dialog.target);
      log.info("GB28181 BYE sent device={} channel={} ssrc={}", new Object[]{dialog.deviceId, dialog.channelId, dialog.ssrc});
      Thread.ofVirtual().start(() -> {
         for (int delay : new int[]{500, 1000, 2000}) {
            try {
               Thread.sleep(delay);
            } catch (InterruptedException e) {
               Thread.currentThread().interrupt();
               break;
            }

            if (this.dialogsByCallId.get(dialog.callId) != dialog) {
               return;
            }

            try {
               this.send(bye, dialog.target);
            } catch (Exception e) {
               log.debug("GB28181 BYE retry failed ssrc={}: {}", dialog.ssrc, e.getMessage());
            }
         }

         this.cleanupDialog(dialog);
      });
   }

   private void scheduleInviteRetries(Gb28181SipServer.InviteDialog dialog, String message) {
      Thread.ofVirtual().start(() -> {
         for (int delay : new int[]{500, 1000, 2000}) {
            try {
               Thread.sleep(delay);
            } catch (InterruptedException e) {
               Thread.currentThread().interrupt();
               return;
            }

            if (dialog.confirmed || dialog.stopping || this.dialogsByCallId.get(dialog.callId) != dialog) {
               return;
            }

            try {
               this.send(message, dialog.target);
            } catch (Exception e) {
               log.debug("GB28181 INVITE retry failed ssrc={}: {}", dialog.ssrc, e.getMessage());
            }
         }
      });
   }

   private void cleanupDialog(Gb28181SipServer.InviteDialog dialog) {
      this.dialogsBySsrc.remove(dialog.ssrc, dialog);
      this.dialogsByCallId.remove(dialog.callId, dialog);
   }

   public String nextSsrc() {
      String candidate;
      while (this.dialogsBySsrc.containsKey(candidate = String.format("0%09d", this.random.nextInt(1000000000)))) {
      }

      return candidate;
   }

   private void sendRequest(String method, String deviceId, InetSocketAddress target, String contentType, String body) {
      this.sendRequest(method, deviceId, target, contentType, body, "");
   }

   private void sendRequest(String method, String deviceId, InetSocketAddress target, String contentType, String body, String extraHeaders) {
      try {
         String branch = "z9hG4bK" + Long.toHexString(this.random.nextLong());
         String callId = Long.toHexString(this.random.nextLong()) + "@" + this.publicIp;
         byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
         String message = method
            + " sip:"
            + deviceId
            + "@"
            + target.getAddress().getHostAddress()
            + ":"
            + target.getPort()
            + " SIP/2.0\r\nVia: SIP/2.0/UDP "
            + this.publicIp
            + ":"
            + this.port
            + ";branch="
            + branch
            + ";rport\r\nFrom: <sip:"
            + this.platformId
            + "@"
            + this.domain
            + ">;tag="
            + Integer.toHexString(this.random.nextInt())
            + "\r\nTo: <sip:"
            + deviceId
            + "@"
            + this.domain
            + ">\r\nContact: <sip:"
            + this.platformId
            + "@"
            + this.publicIp
            + ":"
            + this.port
            + ">\r\nCall-ID: "
            + callId
            + "\r\nCSeq: "
            + this.sequence.incrementAndGet()
            + " "
            + method
            + "\r\nMax-Forwards: 70\r\n"
            + extraHeaders
            + "Content-Type: "
            + contentType
            + "\r\nContent-Length: "
            + bodyBytes.length
            + "\r\n\r\n"
            + body;
         this.send(message, target);
      } catch (Exception e) {
         throw new IllegalStateException("\u53d1\u9001 SIP \u6307\u4ee4\u5931\u8d25: " + e.getMessage(), e);
      }
   }

   private String buildOk(String request) {
      StringBuilder response = new StringBuilder("SIP/2.0 200 OK\r\n");

      for (String line : request.split("\\r?\\n")) {
         String lower = line.toLowerCase(Locale.ROOT);
         if (lower.startsWith("via:") || lower.startsWith("from:") || lower.startsWith("to:") || lower.startsWith("call-id:") || lower.startsWith("cseq:")) {
            response.append(line).append("\r\n");
         }
      }

      return response.append("Content-Length: 0\r\n\r\n").toString();
   }

   private String buildUnauthorized(String request, String nonce) {
      StringBuilder response = new StringBuilder("SIP/2.0 401 Unauthorized\r\n");
      copyTransactionHeaders(request, response);
      return response.append("WWW-Authenticate: Digest realm=\"")
         .append(this.domain)
         .append("\", nonce=\"")
         .append(nonce)
         .append("\", algorithm=MD5, qop=\"auth\"\r\nContent-Length: 0\r\n\r\n")
         .toString();
   }

   private static void copyTransactionHeaders(String request, StringBuilder response) {
      for (String line : request.split("\\r?\\n")) {
         String lower = line.toLowerCase(Locale.ROOT);
         if (lower.startsWith("via:") || lower.startsWith("from:") || lower.startsWith("to:") || lower.startsWith("call-id:") || lower.startsWith("cseq:")) {
            response.append(line).append("\r\n");
         }
      }
   }

   static boolean digestValid(String request, String password, String realm, String expectedNonce) {
      String authorization = header(request, "Authorization");
      if (authorization != null && authorization.regionMatches(true, 0, "Digest ", 0, 7)) {
         HashMap<String, String> values = new HashMap<>();
         Matcher matcher = Pattern.compile("(\\w+)=\"?([^,\"]+)\"?").matcher(authorization.substring(7));

         while (matcher.find()) {
            values.put(matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2).trim());
         }

         String username = values.get("username");
         String nonce = values.get("nonce");
         String uri = values.get("uri");
         String response = values.get("response");
         if (username != null && uri != null && response != null && expectedNonce.equals(nonce)) {
            String ha1 = md5(username + ":" + realm + ":" + password);
            String ha2 = md5("REGISTER:" + uri);
            String qop = values.get("qop");
            if (qop == null || values.get("nc") != null && values.get("cnonce") != null) {
               String expected = qop == null
                  ? md5(ha1 + ":" + nonce + ":" + ha2)
                  : md5(ha1 + ":" + nonce + ":" + values.get("nc") + ":" + values.get("cnonce") + ":" + qop + ":" + ha2);
               return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), response.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
            } else {
               return false;
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private static List<DeviceService.Channel> parseCatalog(String message) {
      ArrayList<DeviceService.Channel> result = new ArrayList<>();
      Matcher blocks = ITEM_BLOCK.matcher(message);

      while (blocks.find()) {
         String item = blocks.group(1);
         String id = extract(XML_ID, item);
         if (id != null) {
            String name = extract(XML_NAME, item);
            String status = extract(XML_STATUS, item);
            result.add(
               new DeviceService.Channel(
                  id,
                  name != null && !name.isBlank() ? name : "\u89c6\u9891\u901a\u9053 " + id.substring(Math.max(0, id.length() - 4)),
                  "ON".equalsIgnoreCase(status) ? "ONLINE" : "OFFLINE",
                  null
               )
            );
         }
      }

      return result;
   }

   private static String decodeSip(byte[] data) {
      String header = new String(data, StandardCharsets.ISO_8859_1);
      String lower = header.toLowerCase(Locale.ROOT);

      try {
         if (!lower.contains("encoding=\"gb2312\"")
            && !lower.contains("encoding='gb2312'")
            && !lower.contains("encoding=\"gbk\"")
            && !lower.contains("encoding='gbk'")) {
            CharsetDecoder decoder = StandardCharsets.UTF_8
               .newDecoder()
               .onMalformedInput(CodingErrorAction.REPORT)
               .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(data)).toString();
         } else {
            return new String(data, Charset.forName("GB18030"));
         }
      } catch (Exception ignored) {
         return new String(data, Charset.forName("GB18030"));
      }
   }

   private static String header(String message, String name) {
      Matcher matcher = Pattern.compile("(?im)^" + Pattern.quote(name) + "\\s*:\\s*(.+)$").matcher(message);
      return matcher.find() ? matcher.group(1).trim() : null;
   }

   private static String md5(String text) {
      try {
         return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));
      } catch (Exception e) {
         throw new IllegalStateException(e);
      }
   }

   private String randomHex(int bytes) {
      byte[] value = new byte[bytes];
      this.random.nextBytes(value);
      return HexFormat.of().formatHex(value);
   }

   private void send(String text, InetSocketAddress target) throws Exception {
      byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      this.socket.send(new DatagramPacket(bytes, bytes.length, target));
   }

   static String ptzCommand(String action, int requestedSpeed) {
      int speed = Math.max(0, Math.min(255, requestedSpeed));

      int command = switch (action.toUpperCase(Locale.ROOT)) {
         case "RIGHT" -> 1;
         case "LEFT" -> 2;
         case "DOWN" -> 4;
         case "UP" -> 8;
         case "ZOOM_IN" -> 16;
         case "ZOOM_OUT" -> 32;
         case "STOP" -> 0;
         default -> throw new IllegalArgumentException("\u4e0d\u652f\u6301\u7684 PTZ \u52a8\u4f5c: " + action);
      };
      int horizontal = (command & 3) != 0 ? speed : 0;
      int vertical = (command & 12) != 0 ? speed : 0;
      int zoom = (command & 48) != 0 ? Math.min(240, speed / 16 << 4) : 0;
      byte[] bytes = new byte[]{-91, 15, 1, (byte)command, (byte)horizontal, (byte)vertical, (byte)zoom, 0};
      int sum = 0;

      for (int i = 0; i < 7; i++) {
         sum += bytes[i] & 255;
      }

      bytes[7] = (byte)(sum & 0xFF);
      return HexFormat.of().withUpperCase().formatHex(bytes);
   }

   private static String extract(Pattern pattern, String input) {
      Matcher m = pattern.matcher(input);
      return m.find() ? m.group(1).trim() : null;
   }

   private static int count(Pattern pattern, String input) {
      int n = 0;
      Matcher m = pattern.matcher(input);

      while (m.find()) {
         n++;
      }

      return n;
   }

   public void stop() {
      this.dialogsBySsrc.keySet().forEach(this::stopStream);
      this.running = false;
      if (this.socket != null) {
         this.socket.close();
      }
   }

   public boolean isRunning() {
      return this.running;
   }

   public int getPhase() {
      return Integer.MAX_VALUE;
   }

   private static final class InviteDialog {
      private final String deviceId;
      private final String channelId;
      private final String ssrc;
      private final String callId;
      private final String branch;
      private final int inviteCseq;
      private final String localFrom;
      private volatile String remoteTo;
      private volatile String remoteUri;
      private volatile InetSocketAddress target;
      private volatile boolean confirmed;
      private volatile boolean stopping;
      private volatile boolean terminating;

      private InviteDialog(
         String deviceId,
         String channelId,
         String ssrc,
         String callId,
         String branch,
         int inviteCseq,
         String localFrom,
         String remoteTo,
         String remoteUri,
         InetSocketAddress target
      ) {
         this.deviceId = deviceId;
         this.channelId = channelId;
         this.ssrc = ssrc;
         this.callId = callId;
         this.branch = branch;
         this.inviteCseq = inviteCseq;
         this.localFrom = localFrom;
         this.remoteTo = remoteTo;
         this.remoteUri = remoteUri;
         this.target = target;
      }
   }
}
