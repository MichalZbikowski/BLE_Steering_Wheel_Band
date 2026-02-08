/*
 * Ultra Low Power 5-Button Handler with BLE for ESP32-S3 (Xiao Seed)
 * Power optimizations:
 * - Automatic CPU frequency scaling (40-80MHz)
 * - Light sleep between BLE connection intervals
 * - Long connection interval (1000ms)
 * - Reduced TX power
 * - Estimated battery life: 2-3 months on 3000mAh
 */

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <esp_pm.h>
#include <esp_wifi.h>
#include <driver/rtc_io.h>
#include <esp_sleep.h>

// Button pins
// #define BTN_1_GPIO 3
// #define BTN_2_GPIO 6
// #define BTN_3_GPIO 7
// #define BTN_4_GPIO 8
// #define BTN_5_GPIO 9

#define BTN_1_GPIO 4
#define BTN_2_GPIO 5
// Timing constants
#define LONG_PRESS_DURATION_MS 1000
#define MAX_WAIT_TIME_MS 5000
#define DEBOUNCE_MS 100  // Increased debounce

// BLE UUIDs
#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"

// Power settings
#define BLE_CONNECTION_INTERVAL_MS 1000  // 1 second interval = stable connection
#define CPU_MAX_FREQ_MHZ 80              // Maximum CPU frequency
#define CPU_MIN_FREQ_MHZ 40              // Minimum CPU frequency when idle

// Action types
enum ActionType {
  ACTION_SHORT_PRESS,
  ACTION_LONG_PRESS
};

// Button configuration
//const uint8_t BUTTON_PINS[] = {BTN_1_GPIO, BTN_2_GPIO, BTN_3_GPIO, BTN_4_GPIO, BTN_5_GPIO};
const uint8_t BUTTON_PINS[] = {BTN_1_GPIO, BTN_2_GPIO};

const uint8_t NUM_BUTTONS = 2;

// BLE objects
BLEServer* pServer = NULL;
BLECharacteristic* pCharacteristic = NULL;
bool deviceConnected = false;
bool oldDeviceConnected = false;

// Server callbacks
class MyServerCallbacks: public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) {
    deviceConnected = true;
    Serial.println("=== Device connected ===");
  };

  void onDisconnect(BLEServer* pServer) {
    deviceConnected = false;
    Serial.println("=== Device disconnected ===");
  }
};

void handleButtonPress(uint8_t buttonIdx);
void sendAction(uint8_t buttonNum, ActionType actionType);
void initBLE();
void configurePowerManagement();
void printPowerStats();

void setup() {
  Serial.begin(115200);
  delay(1000);

  Serial.println("\n=================================");
  Serial.println("Ultra Low Power Button Handler");
  Serial.println("ESP32-S3 + BLE + Deep Sleep");
  Serial.println("=================================\n");

  // Disable WiFi to save power (we only use BLE)
  esp_wifi_stop();
  esp_wifi_deinit();
  Serial.println("✓ WiFi disabled");

  // Configure button pins with internal pull-up
  for (uint8_t i = 0; i < NUM_BUTTONS; i++) {
    pinMode(BUTTON_PINS[i], INPUT_PULLUP);
    rtc_gpio_pullup_en((gpio_num_t)BUTTON_PINS[i]);  // Ensure pull-up during deep sleep
  }
  Serial.println("✓ Buttons configured");

  // Check wakeup cause
  esp_sleep_wakeup_cause_t wakeup_reason = esp_sleep_get_wakeup_cause();

  if (wakeup_reason == ESP_SLEEP_WAKEUP_EXT1) {
    // Woken by button press - stay awake for 5 seconds to allow connection and button presses
    Serial.println("✓ Woken by button press - staying awake for 5 seconds");

    // Initialize BLE quickly
    initBLE();

    // Stay awake for 5 seconds to allow app connection and button presses
    unsigned long wakeStart = millis();
    while (millis() - wakeStart < 5000) {
      // Poll buttons during wake period
      for (uint8_t i = 0; i < NUM_BUTTONS; i++) {
        if (digitalRead(BUTTON_PINS[i]) == LOW) {
          handleButtonPress(i);
          delay(100); // Avoid multiple triggers
          break;
        }
      }
      delay(50); // Small delay between polls
    }

    Serial.println("Wake period ended");
  } else {
    // Normal startup
    Serial.println("✓ Normal startup");

    // Configure automatic power management
    configurePowerManagement();

    // Initialize BLE with power optimization
    initBLE();

    Serial.println("\n=================================");
    Serial.println("Ready! Press any button...");
    Serial.println("Power optimization: ACTIVE (Deep Sleep)");
    Serial.println("=================================\n");

    printPowerStats();
  }

  // Set up ext1 wakeup for all buttons (low level trigger)
  uint64_t ext1_wakeup_mask = 0;
  for (uint8_t i = 0; i < NUM_BUTTONS; i++) {
    ext1_wakeup_mask |= (1ULL << BUTTON_PINS[i]);
  }
  esp_sleep_enable_ext1_wakeup(ext1_wakeup_mask, ESP_EXT1_WAKEUP_ANY_LOW);
  Serial.println("✓ Deep sleep wakeup configured");

  Serial.println("Entering deep sleep...");
  delay(100); // Allow serial to flush
  esp_deep_sleep_start();
}

void loop() {
  // Device enters deep sleep in setup(), so loop() is never reached
  // This function is required by Arduino framework but unused
}

void handleButtonPress(uint8_t buttonIdx) {
  // Debounce
  delay(DEBOUNCE_MS);
  if (digitalRead(BUTTON_PINS[buttonIdx]) == HIGH) {
    return;
  }

  unsigned long pressStart = millis();
  ActionType actionType = ACTION_SHORT_PRESS;

  Serial.print("Button ");
  Serial.print(buttonIdx + 1);
  Serial.println(" pressed");

  // Wait for release or long press
  while (digitalRead(BUTTON_PINS[buttonIdx]) == LOW) {
    if (millis() - pressStart > MAX_WAIT_TIME_MS) {
      Serial.println("Long press timeout!");
      break;
    }
    if (millis() - pressStart >= LONG_PRESS_DURATION_MS) {
      actionType = ACTION_LONG_PRESS;
      Serial.println("Long press detected!");
      break;
    }
    delay(10);
  }

  // Send action via BLE
  sendAction(buttonIdx + 1, actionType);

  // Wait for button release to prevent multiple triggers
  while (digitalRead(BUTTON_PINS[buttonIdx]) == LOW) {
    delay(10);
  }
}

void sendAction(uint8_t buttonNum, ActionType actionType) {
  String action = (actionType == ACTION_SHORT_PRESS ? "short" : "long") + String(buttonNum);
  
  Serial.print("Action: ");
  Serial.print(action);
  Serial.print(" | BLE: ");
  Serial.println(deviceConnected ? "connected" : "disconnected");

  if (deviceConnected && pCharacteristic != NULL) {
    pCharacteristic->setValue(action.c_str());
    pCharacteristic->notify();
    Serial.println(">>> Sent via BLE");
  } else {
    Serial.println(">>> Not sent - no connection");
  }
}

void initBLE() {
  Serial.println("Initializing BLE...");
  
  // Create BLE Device
  BLEDevice::init("ESP32_Buttons");
  esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_DEFAULT, ESP_PWR_LVL_P9);  // Upewnij się, że moc jest max

  // Set TX power to low for power saving (with external antenna for range)
  // Options: ESP_PWR_LVL_N12, N9, N6, N3, N0, P3, P6, P9
  BLEDevice::setPower(ESP_PWR_LVL_N0, ESP_BLE_PWR_TYPE_ADV);
  BLEDevice::setPower(ESP_PWR_LVL_N0, ESP_BLE_PWR_TYPE_SCAN);
  BLEDevice::setPower(ESP_PWR_LVL_N0, ESP_BLE_PWR_TYPE_DEFAULT);
  Serial.println("✓ BLE TX power: LOW (0dBm)");

  // Create BLE Server
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  // Create BLE Service
  BLEService *pService = pServer->createService(SERVICE_UUID);

  // Create BLE Characteristic
  pCharacteristic = pService->createCharacteristic(
                      CHARACTERISTIC_UUID,
                      BLECharacteristic::PROPERTY_READ |
                      BLECharacteristic::PROPERTY_NOTIFY
                    );

  // Add descriptor for notifications
  pCharacteristic->addDescriptor(new BLE2902());
  pCharacteristic->setValue("ready");

  // Start service
  pService->start();
  Serial.println("✓ BLE service started");

  // Configure advertising with long intervals for power saving
  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  
  // Advertising intervals (units of 0.625ms)
  // Shorter intervals for faster reconnection
  pAdvertising->setMinInterval(160);   // 100ms
  pAdvertising->setMaxInterval(320);   // 200ms
  
  BLEDevice::startAdvertising();
  Serial.println("✓ BLE advertising started");
  
  Serial.print("Device name: ESP32_Buttons | Service: ");
  Serial.println(SERVICE_UUID);
}

void configurePowerManagement() {
  Serial.println("Configuring power management...");
  
  // Set CPU frequency for basic operation
  setCpuFrequencyMhz(CPU_MAX_FREQ_MHZ);
  Serial.print("✓ CPU frequency: ");
  Serial.print(getCpuFrequencyMhz());
  Serial.println(" MHz");
  
  // Configure automatic power management
  esp_pm_config_esp32s3_t pm_config = {
    .max_freq_mhz = CPU_MAX_FREQ_MHZ,   // Max frequency when active
    .min_freq_mhz = CPU_MIN_FREQ_MHZ,   // Min frequency when idle
    .light_sleep_enable = true           // Enable automatic light sleep
  };
  
  esp_err_t err = esp_pm_configure(&pm_config);
  if (err == ESP_OK) {
    Serial.println("✓ Automatic power management: ENABLED");
    Serial.print("  - Max freq: ");
    Serial.print(CPU_MAX_FREQ_MHZ);
    Serial.println(" MHz");
    Serial.print("  - Min freq: ");
    Serial.print(CPU_MIN_FREQ_MHZ);
    Serial.println(" MHz");
    Serial.println("  - Light sleep: AUTO");
  } else {
    Serial.print("✗ Power management error: ");
    Serial.println(err);
  }
}

void printPowerStats() {
  Serial.println("\n--- Power Optimization Summary ---");
  Serial.println("CPU: 40-80MHz automatic scaling (when active)");
  Serial.println("BLE: 0dBm TX power (low)");
  Serial.println("Sleep: Deep sleep with ext1 wakeup");
  Serial.println("Connection interval: 1000ms");
  Serial.println("Advertising: 500-1000ms intervals");
  Serial.println("WiFi: Disabled");
  Serial.println("\nEstimated consumption:");
  Serial.println("  Active (button press + BLE): ~30-50mA for ~1-2s");
  Serial.println("  Deep sleep: ~0.01-0.05mA");
  Serial.println("  Battery life (3000mAh): 6-12 months");
  Serial.println("-----------------------------------\n");
}
