# Relay（Pi 中继 / OBD）

车机与家庭网络之间的转发层：nc 隧道、文件投递、OBD/GPS 代理。

| 文件 | 作用 |
| --- | --- |
| `relay.py` / `pi_tunnel.py` | TCP 隧道，车机 ←→ Pi ←→ 内网 |
| `pi_client.py` / `op_client.py` | 带 token 的客户端 |
| `hud-obd-direct/` | 直连方案（无 relay） |

## 运行

```bash
pip install -r requirements.txt
./run.sh
nc -zv $PI_HOST 7801     # 期望 succeeded
```

## 坑

- 隧道要 Pi 侧常驻 `nc -l`，断线需重连。
- 大文件走 base64 分块 + md5 校验，否则中文/二进制会坏。