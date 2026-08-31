## 我们要解决什么问题？
# 你是否也遇到过这样的困扰？
# 购买了海康威视、萤石、大华等品牌的摄像头，想将视频流接入自己的业务平台，开发定时抽帧、目标检测、智能告警等功能，却因为缺少开放 API，只能通过原厂手机 App 查看——后续开发无从下手。
# 好不容易找到了萤石云、海康云眸等官方接入方案，却发现：
- 接入成本高，价格令人望而却步；
- 接入后可能影响原有 App 的正常使用；
- 平台限制较多，难以满足个性化业务需求。
# 这正是 mingqian-video-gateway 希望解决的问题。
# 基于自研接入协议，我们提供了一套轻量、灵活、高性价比的视频接入方案：
- 超轻量部署，2 核 2 GB 配置即可运行；
- 单节点支持同时接入 500 路设备；
- 支持 10 路子码流并发处理；
- 完善的进程管理与内存管理机制，保障服务稳定运行；
- 预留 AI 模型权重上传通道，可快速接入目标检测等智能分析能力；
- 不影响原有 App 使用，兼顾现有体验与二次开发需求。
# 让摄像头视频流真正接入自己的平台，让定时抽帧、目标检测和智能分析不再停留在设想中。
## mingqian-video-gateway，欢迎体验！

# Mingqian Video Gateway

超轻量化视频接入网关，提供 GB/T 28181、RTSP/ZLMediaKit、安全 HLS 播放、按需断流、串行抽帧队列和 Open API。

## 核心能力

- GB28181：UDP SIP 注册、心跳、目录、实时点播、BYE 和 PTZ。
- ZLMediaKit：RTSP 代理、RTP Server、HLS 输出、快照和媒体状态检查。
- 安全播放：短期 Playback Token，媒体请求同时校验 API Key/登录会话与播放令牌。
- 按需断流：播放心跳、主动停止、无观看者超时、并发上限和内存压力回收。
- 抽帧队列：有界串行队列、请求限时、间隔控制、短期缓存和直播帧复用统计。
- Open API：API Key 哈希存储、READ/PLAYBACK/CONTROL 最小权限、按 Key 限流、CORS 白名单和 OpenAPI 3.1 规范。（！非常方便各种数据大屏开发！）
- 管理控制台：设备、通道、播放资源、API Key、用户与权限管理。

## 架构

```text
IPC / NVR -- GB28181 SIP + RTP --┐
                                 ├─ Mingqian Video Gateway ─ Open API / Console
RTSP Camera ---------------------┘             │
                                               └─ ZLMediaKit ─ Secure HLS
```

网关负责信令、鉴权、会话和资源生命周期；ZLMediaKit 负责媒体接收、代理与协议转换。FFmpeg 用作 RTSP 兼容拉流和截图后备方案。

## 环境要求

- Java 21
- Maven 3.9+
- ZLMediaKit（GB28181 RTP 实时播放必需）
- FFmpeg（RTSP 后备转码与截图建议安装）

## 本地构建

```bash
mvn clean verify
java -jar target/mingqian-video-gateway-1.0.0.jar
```

首次启动前至少设置以下变量，所有示例值都必须替换：

```bash
export PLATFORM_ADMIN_PASSWORD='replace-with-a-long-random-password'
export PLATFORM_ADMIN_TOKEN='replace-with-a-long-random-admin-token'
export GB_PUBLIC_IP='192.0.2.10'
export GB_DEVICE_PASSWORD='replace-with-a-device-password'
export ZLM_SECRET='replace-with-the-same-zlm-api-secret'
```

访问 `http://127.0.0.1:18080/login.html`。首次启动会在本地 `data/` 创建管理员密码哈希；之后可移除 `PLATFORM_ADMIN_PASSWORD`，不要提交 `data/`。

## Docker Compose

Linux 主机可直接使用包含 ZLMediaKit 的示例：

```bash
cp deploy/.env.example deploy/.env
# 编辑 deploy/.env，替换所有 CHANGE_ME
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build
```

示例使用 host 网络以避免 GB28181 动态 RTP 端口经过容器 NAT。详细说明、Nginx 和 systemd 示例见 [`deploy/README.md`](deploy/README.md)。

## 关键配置

| 变量 | 用途 | 默认值 |
| --- | --- | --- |
| `HTTP_PORT` | 管理与 API 端口 | `18080` |
| `PLATFORM_ADMIN_PASSWORD` | 首次管理员密码，至少 12 位 | 无 |
| `PLATFORM_ADMIN_TOKEN` | 管理接口后备令牌 | 无 |
| `PLATFORM_PUBLIC_URL` | HTTPS 对外根地址 | 自动推断 |
| `GB_PUBLIC_IP` | 摄像机可达的 SIP/RTP 地址 | `127.0.0.1` |
| `GB_PLATFORM_ID` | 20 位国标平台编码 | 示例编码 |
| `GB_PLATFORM_DOMAIN` | 国标域 | 示例域 |
| `GB_DEVICE_PASSWORD` | GB28181 Digest 密码 | 无 |
| `ZLM_HOST` / `ZLM_HTTP_PORT` | ZLMediaKit API 地址 | `127.0.0.1:18081` |
| `ZLM_SECRET` | ZLMediaKit API 密钥 | 无 |
| `FFMPEG_PATH` | FFmpeg 可执行文件 | `ffmpeg` |
| `OPEN_API_ALLOWED_ORIGINS` | 浏览器跨域 Origin 白名单 | 空 |
| `PLAYBACK_MAX_ACTIVE` | 最大活动播放路数 | `6` |
| `PLAYBACK_VIEWER_TIMEOUT` | 无心跳自动回收秒数 | `25` |
| `SNAPSHOT_QUEUE_CAPACITY` | 抽帧等待队列容量 | `12` |

其余参数见 [`application.yml`](src/main/resources/application.yml) 和 [`deploy/.env.example`](deploy/.env.example)。

## Open API

管理员在控制台创建 API Key。完整密钥只显示一次，服务端仅保存 SHA-256 哈希。

```bash
curl -H 'Authorization: Bearer <API_KEY>' \
  http://127.0.0.1:18080/open-api/v1/devices
```

主要端点：

- `GET /open-api/v1/health`
- `GET /open-api/v1/devices`
- `GET /open-api/v1/channels`
- `GET /open-api/v1/devices/{deviceId}/snapshot`
- `POST /open-api/v1/devices/{deviceId}/play`
- `POST .../play/{playbackId}/heartbeat`
- `POST .../play/{playbackId}/stop`
- `POST /open-api/v1/devices/{deviceId}/ptz`
- `GET /open-api/v1/spec`

受保护 HLS 请求必须携带 `Authorization: Bearer <API_KEY>` 和点播响应返回的 `X-Playback-Token`。

## 安全与数据

- 公网部署必须使用 HTTPS 反向代理，并启用 `AUTH_SECURE_COOKIE=true`。
- 不要把摄像机凭据、管理员密码、API Key、ZLM Secret 或 `data/` 提交到 Git。
- 默认不创建演示设备，也不包含任何生产数据。
- 安全问题请按 [`SECURITY.md`](SECURITY.md) 私下报告。

## 许可证

Apache License 2.0，见 [`LICENSE`](LICENSE)。ZLMediaKit、FFmpeg、Spring Boot 和容器基础镜像各自遵循其上游许可证。
