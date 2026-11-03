# 蜜罐固件烧录说明（esptool 直烧法）

归档内 4 个 bin（来自 arduino-cli esp32:esp32@2.0.17 编译，MODE_PROBE=0 蜜罐模式）：

| 文件 | Flash 偏移 |
|---|---|
| dji_esp32_honeypot.ino.bootloader.bin | 0x1000 |
| dji_esp32_honeypot.ino.partitions.bin | 0x8000 |
| boot_app0.bin | 0xe000 |
| dji_esp32_honeypot.ino.bin | 0x10000 |

```bash
# 主力机 (<USER>@<LAN_IP>, COM6)
python -m esptool --port COM6 write_flash \
  0x1000 dji_esp32_honeypot.ino.bootloader.bin \
  0x8000 dji_esp32_honeypot.ino.partitions.bin \
  0xe000 boot_app0.bin \
  0x10000 dji_esp32_honeypot.ino.bin
```

> ⚠️ 烧前先释放 COM6（schtasks /end esp_cap + 杀 powershell）；端口掉线先
> `pnputil /restart-device "USB\VID_10C4&PID_EA60\0001"`
