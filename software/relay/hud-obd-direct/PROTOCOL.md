# HUD 车萝卜 C2-Elite OBD 私有协议（逆向完成 2026-08-16 22:50）

## 硬件链路
- HUD 内置 OBD 硬件直连: `/dev/ttyMT0`（MTK UART, 权限 777, 普通 app 可读写）
- 读取方: aicore (com.ileja.aicore) → com.cpsdna.obdports.ports.SerialService
- 串口配置: `/dev/ttyMT0` @ 波特率 **19200**（dina 路径, SerialPrefence.setSerial 0x4b00）
  - 另一路径 miyuan (libt6_serial.so) 波特率常量 0x113f ≈ 115200，未确认主用哪条
- 写周期: Timer.schedule(2000ms 延迟, 1000ms 周期) → 每 1 秒发一帧

## 帧格式（bos2Obd 封装 + escapeObd 转义）
```
原始帧:
  [cmd 2B][len 2B][count 2B][0x0000 2B][data N B][checksum 1B]
  - cmd: 0x8004 = OBD 数据包
  - len: data 长度 (int-to-short)
  - count: 自增序号, 0→0x7fff 循环
  - checksum: 所有字节 XOR

转义后线格式:
  0x7E [body] 0x7E
  body 中: 0x7E → 0x7D 0x01
           0x7D → 0x7D 0x02
```

## 验证
aicore 日志实际帧: `7E 8004 0006 0001 00000001 0100 03 0080 7E`
- 8004 = cmd
- 0006 = len(6)
- 0001 = count
- 0000 = reserved
- 00000101000300 = data (6B)
- 80 = checksum (xor of 80 04 00 06 00 01 00 00 00 01 01 00 03 00)

## 发送命令（ByteOutPrams 静态方法）
- queryOBDID() → 查询 OBD ID
- queryOBDMile() → 查询里程
- queryOBDVin() → 查询 VIN
- bos2Obd(0x8004, data) → OBD 数据包
- clearMileAndOil() → 清里程/油耗
- setCarDisplacement(float) → 设置排量

## 接收解析（SerialService）
- getData(byte[], int) → 读字节
- parseByteValue(int, int, byte[]) → 解析数值
- getStates(byte) → 状态
- OBDManager.sendDataPackage() → 发送

## 请求-响应模式（2026-08-17 确认）
- OBD 是**请求-响应**模式：必须主动发请求帧，硬件才回数据
- aicore SerialService: Timer.schedule(2s 延迟, 1s 周期) → 每 1 秒发一帧请求
- 真实请求帧（aicore 日志）：`7E 8004 0006 0001 0000 000101000300 80 7E`
  - 其中 count 字段(0001)每帧递增, checksum 随 count 变化
- 验证脚本: `obd_query.sh`（发请求+读响应, 车启动时运行）

## 下一步
1. 车启动时运行 obd_query.sh 验证请求-响应抓帧
2. 解析响应帧数据（PanelBoardInfo 字段: speed/rpm/coolant/fuel/voltage/odo）
3. 实现独立解析器（绕过 aicore）
4. 输出 AIOBD## 兼容格式给 HudService
