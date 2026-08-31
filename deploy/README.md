# Deployment examples

## Docker Compose（Linux）

示例同时启动网关与 ZLMediaKit，并使用 host 网络保留 GB28181 动态 RTP 端口语义。

```bash
cp deploy/.env.example deploy/.env
chmod 600 deploy/.env
# 编辑并替换全部 CHANGE_ME
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build
curl http://127.0.0.1:18080/actuator/health
```

`zlmediakit/zlmediakit:master` 是 [ZLMediaKit 官方文档](https://github.com/ZLMediaKit/ZLMediaKit/blob/master/README.md)提供的持续集成镜像；正式环境建议在验证后锁定镜像 digest，以获得可重复部署。

## systemd（已有 ZLMediaKit）

```bash
sudo useradd --system --home /var/lib/mingqian-video-gateway --shell /usr/sbin/nologin video-gateway
sudo install -d -o video-gateway -g video-gateway /opt/mingqian-video-gateway /var/lib/mingqian-video-gateway
sudo install -d -m 0750 /etc/mingqian-video-gateway
sudo install -m 0644 target/mingqian-video-gateway-1.0.0.jar /opt/mingqian-video-gateway/
sudo install -m 0644 deploy/mingqian-video-gateway.service /etc/systemd/system/
sudoedit /etc/mingqian-video-gateway/gateway.env
sudo systemctl daemon-reload
sudo systemctl enable --now mingqian-video-gateway
```

`gateway.env` 至少设置：`PLATFORM_ADMIN_PASSWORD`（仅首次启动）、`PLATFORM_ADMIN_TOKEN`、`GB_PUBLIC_IP`、`GB_DEVICE_PASSWORD`、`ZLM_HOST`、`ZLM_HTTP_PORT`、`ZLM_SECRET`，并把四个数据文件路径指向 `/var/lib/mingqian-video-gateway/`。

## HTTPS

复制 `deploy/nginx.conf` 后替换域名和证书路径，同时设置：

```text
PLATFORM_PUBLIC_URL=https://video.example.com
AUTH_SECURE_COOKIE=true
```

防火墙只应向可信网络开放 UDP 5060 和 ZLMediaKit 所需 RTP 端口；ZLMediaKit HTTP API 端口不应直接暴露公网。

## Upgrade and backup

升级前备份数据目录和环境文件。容器部署的数据位于 `gateway-data` 卷；systemd 示例位于 `/var/lib/mingqian-video-gateway`。JAR 或镜像升级不应覆盖数据目录。
