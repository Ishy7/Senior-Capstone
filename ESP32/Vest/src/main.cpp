#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

#define VEST_SERVICE_UUID            "12345678-1234-5678-1234-56789abcdef0"
#define COMMAND_CHARACTERISTIC_UUID  "12345678-1234-5678-1234-56789abcdef1"
#define RESPONSE_CHARACTERISTIC_UUID "12345678-1234-5678-1234-56789abcdef2"

// Command IDs matching BleProtocol.kt
static const uint8_t CMD_BATTERY_LIFE           = 0x01;
static const uint8_t CMD_SET_VIBRATION_STRENGTH = 0x02;
static const uint8_t CMD_VIBRATE                = 0x03;
static const uint8_t CMD_HEARTBEAT              = 0x04;

// Response status codes matching BleProtocol.kt
static const uint8_t STATUS_SUCCESS             = 0x00;
static const uint8_t STATUS_UNKNOWN_COMMAND     = 0x01;
static const uint8_t STATUS_INVALID_PARAMETERS  = 0x02;
static const uint8_t STATUS_HARDWARE_ERROR      = 0x03;
static const uint8_t STATUS_EXECUTION_FAILED    = 0x04;

// Hardware configuration
static const int MOTOR_PIN = D5;         // Pin D5 (HIGH turns motor on)
static uint8_t currentStrength = 5;      // 0..10
static bool deviceConnected = false;

BLECharacteristic *pCommandCharacteristic  = nullptr;
BLECharacteristic *pResponseCharacteristic = nullptr;

void setMotorState(uint8_t strength, bool active) {
    if (!active || strength == 0) {
        analogWrite(MOTOR_PIN, 0);
        digitalWrite(MOTOR_PIN, LOW);
        return;
    }
    uint8_t duty = static_cast<uint8_t>((strength * 255) / 10);
    if (duty == 0) {
        duty = 1;
    }
    analogWrite(MOTOR_PIN, duty);
}

void sendResponse(
    uint8_t command,
    uint8_t transactionId,
    uint8_t status,
    const uint8_t *payload = nullptr,
    size_t payloadLen = 0
) {
    if (!deviceConnected || pResponseCharacteristic == nullptr) {
        return;
    }

    uint8_t packet[16];
    packet[0] = command;
    packet[1] = transactionId;
    packet[2] = status;

    size_t copyLen = (payloadLen > 13) ? 13 : payloadLen;
    for (size_t i = 0; i < copyLen; i++) {
        packet[3 + i] = payload[i];
    }

    pResponseCharacteristic->setValue(packet, 3 + copyLen);
    pResponseCharacteristic->notify();
}

class VestServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer *pServer) override {
        deviceConnected = true;
        Serial.println("Watch connected to ESP32 vest.");
    }

    void onDisconnect(BLEServer *pServer) override {
        deviceConnected = false;
        setMotorState(0, false);
        Serial.println("Watch disconnected. Restarting advertising...");
        BLEDevice::startAdvertising();
    }
};

class CommandCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) override {
        String rx = pCharacteristic->getValue();
        size_t len = rx.length();

        if (len < 2) {
            sendResponse(0x00, 0x00, STATUS_INVALID_PARAMETERS);
            return;
        }

        uint8_t command = static_cast<uint8_t>(rx[0]);
        uint8_t transactionId = static_cast<uint8_t>(rx[1]);

        switch (command) {
            case CMD_BATTERY_LIFE: {
                if (len != 2) {
                    sendResponse(command, transactionId, STATUS_INVALID_PARAMETERS);
                    return;
                }
                // TODO: Replace with real battery percentage reading when wired
                uint8_t batteryPercent = 100;
                if (batteryPercent > 100) {
                    sendResponse(command, transactionId, STATUS_HARDWARE_ERROR);
                    return;
                }
                sendResponse(command, transactionId, STATUS_SUCCESS, &batteryPercent, 1);
                break;
            }

            case CMD_SET_VIBRATION_STRENGTH: {
                if (len != 3) {
                    sendResponse(command, transactionId, STATUS_INVALID_PARAMETERS);
                    return;
                }
                uint8_t requestedStrength = static_cast<uint8_t>(rx[2]);
                if (requestedStrength > 10) {
                    sendResponse(command, transactionId, STATUS_INVALID_PARAMETERS);
                    return;
                }
                // Strength 0 retrieves current strength without changing it
                if (requestedStrength > 0) {
                    currentStrength = requestedStrength;
                }
                sendResponse(command, transactionId, STATUS_SUCCESS, &currentStrength, 1);
                break;
            }

            case CMD_VIBRATE: {
                if (len != 4) {
                    sendResponse(command, transactionId, STATUS_INVALID_PARAMETERS);
                    return;
                }
                // Little-endian order matching BleProtocol.kt: [cmd, txId, lowByte, highByte]
                uint16_t durationMs = static_cast<uint8_t>(rx[2]) |
                                      (static_cast<uint16_t>(static_cast<uint8_t>(rx[3])) << 8);

                if (durationMs == 0) {
                    sendResponse(command, transactionId, STATUS_INVALID_PARAMETERS);
                    return;
                }

                setMotorState(currentStrength, true);
                delay(durationMs);
                setMotorState(0, false);

                sendResponse(command, transactionId, STATUS_SUCCESS);
                break;
            }

            case CMD_HEARTBEAT: {
                if (len != 2) {
                    sendResponse(command, transactionId, STATUS_INVALID_PARAMETERS);
                    return;
                }
                sendResponse(command, transactionId, STATUS_SUCCESS);
                break;
            }

            default: {
                sendResponse(command, transactionId, STATUS_UNKNOWN_COMMAND);
                break;
            }
        }
    }
};

void setup() {
    Serial.begin(115200);
    pinMode(MOTOR_PIN, OUTPUT);
    setMotorState(0, false);

    BLEDevice::init("PTSDog Vest");

    BLEServer *pServer = BLEDevice::createServer();
    pServer->setCallbacks(new VestServerCallbacks());

    BLEService *pService = pServer->createService(VEST_SERVICE_UUID);

    pCommandCharacteristic = pService->createCharacteristic(
        COMMAND_CHARACTERISTIC_UUID,
        BLECharacteristic::PROPERTY_WRITE
    );
    pCommandCharacteristic->setCallbacks(new CommandCallbacks());

    pResponseCharacteristic = pService->createCharacteristic(
        RESPONSE_CHARACTERISTIC_UUID,
        BLECharacteristic::PROPERTY_NOTIFY
    );
    pResponseCharacteristic->addDescriptor(new BLE2902());

    pService->start();

    BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(VEST_SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    BLEDevice::startAdvertising();

    Serial.println("ESP32 Vest BLE Server advertising...");
}

void loop() {
    delay(100);
}
