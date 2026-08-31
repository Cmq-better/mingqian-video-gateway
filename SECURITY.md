# Security Policy

## Supported versions

安全修复优先合入当前默认分支。发布版本中仅最新的 minor 版本接受安全更新。

## Reporting a vulnerability

请优先使用 GitHub 仓库的 **Security → Report a vulnerability** 私密报告入口。若仓库尚未启用 Private Vulnerability Reporting，请联系仓库维护者获取私密联系方式；不要在公开 Issue 中披露可利用细节、密钥、设备地址或生产数据。

报告建议包含：

- 受影响版本或提交；
- 影响范围与攻击前提；
- 最小复现步骤或 PoC；
- 建议修复方式（如有）；
- 是否已经在其他渠道披露。

维护者确认后会协调复现、修复、CVE（如适用）和披露时间。在修复公开前，请给维护者合理处置时间。

## Deployment baseline

- 仅通过 HTTPS 暴露控制台和 Open API；启用 Secure Cookie。
- 为管理员、GB28181 与 ZLMediaKit 分别使用独立随机密钥。
- 限制 SIP、RTP、ZLMediaKit API 和管理端口的网络访问范围。
- 定期轮换 API Key，按调用方授予最小 Scope。
- 保护并备份 `data/`，禁止提交用户、设备和密钥文件。
- 上线前检查 `/api/platform/diagnostics`，并修复全部安全警告。

## Out of scope

上游 ZLMediaKit、FFmpeg、JDK、Spring Boot、浏览器和操作系统漏洞应同时报告给对应上游项目。本项目仍会评估依赖升级或缓解措施。
