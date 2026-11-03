# Security Policy

## 不要提交凭证

本仓库**不含任何真实凭证**。所有 IP、SN、token、域名均已替换为 `<PLACEHOLDER>`。

如果你要提交改动：

1. `cp .env.example .env` 并只在 `.env` 里填真实值（已被 `.gitignore` 忽略）。
2. 提交前跑：`python3 tools/sanitize.py --root . --out /tmp/scan.json`
   期望输出 `HIGH: 0`（非 0 时，`--fail-on HIGH` 会让命令以 2 退出，可直接接到 pre-commit）。
3. 不要提交 `.apk`、`.keystore`、日志、抓包。

## 提交前自检

```bash
python3 tools/sanitize.py --root . --out /tmp/scan.json --fail-on HIGH
```

HIGH 非 0 时命令以 2 退出，可直接接 pre-commit / CI。规则见 `tools/sanitize.py` 顶部。


## 网络边界

本栈**假设运行在 Tailscale tailnet 内**：车机、Pi、手机同一 tailnet，不开放公网端口。

- ShellBridge(7801) 无应用层鉴权，靠 tailnet 隔离。
- 若必须跨网访问，请自建反向代理并加 token，不要把 7801 直接映射出去。

## 报告漏洞

请开 issue 并标注 `security`，不要公开粘贴可用的 token 或完整内网拓扑。