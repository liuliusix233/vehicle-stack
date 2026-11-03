// ============================================================
// DJI Power 303 (dy303 / Cadillac 1000mini) BLE 探针/蜜罐
// 板子: ESP32 DevKit (CP210x 串口, COM6 on <USER> 主力机)
//
// 双模式 (编译时切换):
//   MODE_PROBE    : Central 直连真机 98:88:E0:34:A9:6A (免配对),
//                   读出 C300/C301/C307/C400/C401 + 订阅 C305 遥测
//   MODE_HONEYPOT : Peripheral 伪装真机 (MAC 98:88:E0:34:A9:6A +
//                   名字 POWER1000MINI-1009NX + A002/A003 GATT),
//                   捕获 DJI Home 写入 C304 的 14 字节帧 → 串口 hex
//
// 依赖: arduino-esp32 2.0.17 (esp32:esp32@2.0.17), 默认 BLEDevice(bluedroid)
// 串口: 115200
// ============================================================

#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>
#include <BLEScan.h>
#include <BLE2902.h>
#include <esp_bt.h>
#include <esp_bt_device.h>

// ---------------- 编译开关 ----------------
// 默认探针模式; 用 -DMODE_PROBE=0 编译蜜罐模式
#ifndef MODE_PROBE
#define MODE_PROBE 1        // 1 = 探针(Central), 0 = 蜜罐(Peripheral)
#endif
// -----------------------------------------

// 真机常量
static const char* REAL_MAC = "98:88:e0:34:a9:6a";
static const char* DEV_NAME = "POWER1000MINI-1009NX";
static const uint8_t MAC_SPOOF[6] = {0x98, 0x88, 0xE0, 0x34, 0xA9, 0x6A};

// GATT UUID (16bit → 标准 128bit 展开)
static BLEUUID SVC_A002((uint16_t)0xA002);
static BLEUUID SVC_A003((uint16_t)0xA003);
static BLEUUID CH_C300((uint16_t)0xC300);
static BLEUUID CH_C301((uint16_t)0xC301);
static BLEUUID CH_C302((uint16_t)0xC302);
static BLEUUID CH_C303((uint16_t)0xC303);
static BLEUUID CH_C304((uint16_t)0xC304);
static BLEUUID CH_C305((uint16_t)0xC305);
static BLEUUID CH_C306((uint16_t)0xC306);
static BLEUUID CH_C307((uint16_t)0xC307);
static BLEUUID CH_C400((uint16_t)0xC400);
static BLEUUID CH_C401((uint16_t)0xC401);

// C301 512B 静态校准数据 (真机捕获后由 c301_data.h 提供)
#include "c301_data.h"

// ---------------- 工具: hex dump ----------------
static void hexDump(const char* tag, const uint8_t* p, size_t n) {
  Serial.printf("[%s] %u bytes:\n", tag, (unsigned)n);
  for (size_t i = 0; i < n; i++) {
    if (i % 16 == 0) {
      if (i) Serial.println();
      Serial.printf("%04X: ", (unsigned)i);
    }
    Serial.printf("%02X ", p[i]);
  }
  Serial.println();
}

static void hexDumpStr(const char* tag, const std::string& s) {
  hexDump(tag, (const uint8_t*)s.data(), s.size());
}

// ============================================================
// MODE 1: PROBE — Central 读真机
// ============================================================
#if MODE_PROBE

class ScanCB : public BLEAdvertisedDeviceCallbacks {
  bool* found_;
public:
  ScanCB(bool* f) : found_(f) {}
  void onResult(BLEAdvertisedDevice d) override {
    String addr = d.getAddress().toString().c_str();
    Serial.printf("[SCAN] %s | RSSI=%d | %s\n",
                  addr.c_str(), d.getRSSI(),
                  d.haveName() ? d.getName().c_str() : "(no name)");
    if (addr.equalsIgnoreCase(REAL_MAC)) {
      *found_ = true;
      Serial.printf("[SCAN] >>> TARGET FOUND! RSSI=%d\n", d.getRSSI());
    }
  }
};

bool scanOnce() {
  Serial.println("[SCAN] scanning 20s...");
  bool found = false;
  BLEScan* scan = BLEDevice::getScan();
  scan->setAdvertisedDeviceCallbacks(new ScanCB(&found), false);
  scan->setActiveScan(true);
  scan->setInterval(100);
  scan->setWindow(99);
  scan->start(20, false);
  Serial.println("[SCAN] done");
  return found;
}

void probeRead(BLEClient* client) {
  BLERemoteService* s = client->getService(SVC_A002);
  if (!s) { Serial.println("!! A002 service not found"); return; }

  auto rd = [&](BLEUUID u, const char* nm) {
    BLERemoteCharacteristic* c = s->getCharacteristic(u);
    if (!c) { Serial.printf("[%s] missing\n", nm); return; }
    std::string v = c->readValue();
    hexDumpStr(nm, v);
  };
  rd(CH_C300, "C300 READ");
  rd(CH_C301, "C301 READ");
  rd(CH_C307, "C307 READ");

  // C305 遥测订阅 (需真机 C304 心跳已建立; 这里只挂订阅试 15s)
  BLERemoteCharacteristic* c305 = s->getCharacteristic(CH_C305);
  if (c305 && c305->canNotify()) {
    c305->registerForNotify([&](BLERemoteCharacteristic* c, uint8_t* data, size_t len, bool isNotify) {
      hexDump("C305 NOTIFY", data, len);
    });
    Serial.println("[C305] subscribed, listening 15s...");
    for (int i = 0; i < 15; i++) { delay(1000); if (!client->isConnected()) break; }
  } else {
    Serial.println("[C305] not found or no NOTIFY");
  }

  // A003 服务
  BLERemoteService* s3 = client->getService(SVC_A003);
  if (s3) {
    auto rd3 = [&](BLEUUID u, const char* nm) {
      BLERemoteCharacteristic* c = s3->getCharacteristic(u);
      if (!c) { Serial.printf("[%s] missing\n", nm); return; }
      hexDumpStr(nm, c->readValue());
    };
    rd3(CH_C400, "C400 READ");
    rd3(CH_C401, "C401 READ");
  } else {
    Serial.println("!! A003 service not found");
  }
}

void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.printf("\n=== DJI PROBE boot | chip=%s | psram=%d ===\n",
                ESP.getChipModel(), ESP.getPsramSize());
  BLEDevice::init("DJI-Probe");
  delay(500);
  bool found = scanOnce();
  if (!found) {
    Serial.println("!! TARGET NOT IN RANGE (unit off or too far), retry in 10s");
    delay(10000);
    ESP.restart();
    return;
  }
  BLEClient* client = BLEDevice::createClient();
  client->setMTU(517);

  Serial.printf("connecting %s ...\n", REAL_MAC);
  if (!client->connect(BLEAddress(REAL_MAC))) {
    Serial.println("!! connect FAILED");
    Serial.println("sleep 60s then restart");
    delay(60000);
    ESP.restart();
    return;
  }
  Serial.printf("connected, MTU=%u\n", client->getMTU());
  probeRead(client);
  client->disconnect();
  Serial.println("done, going to sleep 30s");
  delay(30000);
  ESP.restart();
}

void loop() {}

// ============================================================
// MODE 2: HONEYPOT — Peripheral 伪装
// ============================================================
#else

class ServerCB : public BLEServerCallbacks {
  void onConnect(BLEServer* s) override {
    Serial.printf("[SRV] client connected, conn=%u\n", s->getConnId());
  }
  void onDisconnect(BLEServer* s) override {
    Serial.printf("[SRV] client disconnected\n");
    s->startAdvertising();  // 重新广播
  }
};

class CharCB : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    std::string v = c->getValue();
    hexDumpStr("C304 WRITE <<<", v);   // ★ 14 字节帧捕获点
  }
  void onRead(BLECharacteristic* c) override {
    Serial.printf("[C%02X] READ (len=%u)\n",
                  (c->getUUID().getNative()->uuid.uuid16 & 0xFF),
                  (unsigned)c->getValue().length());
  }
};

void setup() {
  Serial.begin(115200);
  delay(500);

  // ★ MAC 伪装: 必须在 BLE init 之前设置 base MAC
  esp_base_mac_addr_set((uint8_t*)MAC_SPOOF);
  Serial.printf("base MAC set to %02X:%02X:%02X:%02X:%02X:%02X\n",
                MAC_SPOOF[0], MAC_SPOOF[1], MAC_SPOOF[2],
                MAC_SPOOF[3], MAC_SPOOF[4], MAC_SPOOF[5]);

  BLEDevice::init(DEV_NAME);
  esp_bt_dev_set_device_name(DEV_NAME);   // GAP 层名字一致
  Serial.printf("BLE init, addr=%s\n", BLEDevice::getAddress().toString().c_str());

  BLEServer* server = BLEDevice::createServer();
  server->setCallbacks(new ServerCB());
  CharCB* ccb = new CharCB();

  // ---- A002 服务 ----
  BLEService* svc = server->createService(SVC_A002);
  uint8_t v30 = 0x30;

  BLEService* svc3 = server->createService(SVC_A003);

  // C300 READ = 0x30
  BLECharacteristic* c300 = svc->createCharacteristic(CH_C300, BLECharacteristic::PROPERTY_READ);
  c300->setValue(&v30, 1); c300->setCallbacks(ccb);
  c300->addDescriptor(new BLEDescriptor(BLEUUID((uint16_t)0x2901)));

  // C301 READ = 512B 校准数据
  BLECharacteristic* c301 = svc->createCharacteristic(CH_C301, BLECharacteristic::PROPERTY_READ);
  c301->setValue((uint8_t*)C301_DATA, C301_LEN); c301->setCallbacks(ccb);
  c301->addDescriptor(new BLEDescriptor(BLEUUID((uint16_t)0x2901)));

  // C302 WRITE (DJI Home 未用)
  BLECharacteristic* c302 = svc->createCharacteristic(CH_C302, BLECharacteristic::PROPERTY_WRITE);
  c302->setCallbacks(ccb);
  c302->addDescriptor(new BLEDescriptor(BLEUUID((uint16_t)0x2901)));

  // C303 WRITE NO RESPONSE
  BLECharacteristic* c303 = svc->createCharacteristic(CH_C303, BLECharacteristic::PROPERTY_WRITE_NR);
  c303->setCallbacks(ccb);
  c303->addDescriptor(new BLEDescriptor(BLEUUID((uint16_t)0x2901)));

  // C304 WRITE + WRITE_NO_RESPONSE ★ 控制/心跳通道
  BLECharacteristic* c304 = svc->createCharacteristic(
      CH_C304, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  c304->setCallbacks(ccb);
  c304->addDescriptor(new BLEDescriptor(BLEUUID((uint16_t)0x2901)));

  // C305 NOTIFY 遥测
  BLECharacteristic* c305 = svc->createCharacteristic(
      CH_C305, BLECharacteristic::PROPERTY_NOTIFY);
  c305->addDescriptor(new BLE2902());
  c305->setCallbacks(ccb);

  // C306 INDICATE (nRF 配过 NOTIFY+2902; 给双属性稳妥)
  BLECharacteristic* c306 = svc->createCharacteristic(
      CH_C306, BLECharacteristic::PROPERTY_NOTIFY | BLECharacteristic::PROPERTY_INDICATE);
  c306->addDescriptor(new BLE2902());
  c306->setCallbacks(ccb);

  // C307 READ (真机读不出, 给 0x30)
  BLECharacteristic* c307 = svc->createCharacteristic(CH_C307, BLECharacteristic::PROPERTY_READ);
  c307->setValue(&v30, 1); c307->setCallbacks(ccb);

  svc->start();

  // ---- A003 服务 ----
  BLECharacteristic* c400 = svc3->createCharacteristic(CH_C400, BLECharacteristic::PROPERTY_READ);
  c400->setValue(&v30, 1); c400->setCallbacks(ccb);
  BLECharacteristic* c401 = svc3->createCharacteristic(CH_C401, BLECharacteristic::PROPERTY_READ);
  c401->setValue(&v30, 1); c401->setCallbacks(ccb);
  svc3->start();

  // ---- 广播: Flags + Service UUID + 名字(主包&扫描响应双保险) ----
  BLEAdvertising* adv = BLEDevice::getAdvertising();
  BLEAdvertisementData advData;
  advData.setFlags(0x06);
  advData.setPartialServices(SVC_A002);   // 0x03 类型, 与原广播一致
  advData.setName(DEV_NAME);
  adv->setAdvertisementData(advData);
  BLEAdvertisementData scanResp;
  scanResp.setName(DEV_NAME);
  adv->setScanResponseData(scanResp);
  adv->setMinInterval(100);   // 100ms 快速广播, 保证被扫到
  adv->setMaxInterval(200);
  adv->start();

  Serial.println("[SRV] A002+A003 up, advertising POWER1000MINI-1009NX");
}

void loop() {
  delay(5000);
  Serial.printf("[SRV] heap=%u free\n", ESP.getFreeHeap());
}

#endif
