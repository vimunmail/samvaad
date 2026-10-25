/***************************************************************************
 * GESTALK v3.3 — 5-LEVEL QUANTIZED FEATURES + FILTERED MPU6050 PIPELINE
 *                + BATTERY MONITOR
 * Board   : Seeed XIAO ESP32-S3
 * Sensors : 6x MPU6050 via TCA9548A I2C mux
 * WRIST   : Channel 5 (Reference Anchor)
 * FINGERS : ch0=Thumb, ch3=Index, ch1=Middle, ch4=Ring, ch2=Little
 *
 * ====================== WHAT CHANGED FROM v3.2 ============================
 * 1) QUANTIZER is now 5-level: {-2,-1,0,+1,+2}  (was 3-level -1..+1).
 *    Calibrated finger mapping now spans the FULL range:
 *        open -> -2     mid -> 0     fist -> +2
 *    (the old v3.2 formula only reached -1..+1 for calibrated fingers, so
 *     5 levels gave them no extra resolution — fixed via `*4 - 2`.)
 *    Wrist accel + absolute-tilt fallback already use ±1g -> ±2 (the `*2.0`
 *    scale), so those are unchanged.
 *    >>> The feature value range changed again, so you MUST recollect data
 *    and retrain the TCN / RFC models. <<<
 *
 *    NOTE: only ACCEL is quantized. Wrist gyro stays integer deg/s — it
 *    carries rotational-rate info the model needs and must not be quantized.
 *
 * 2) BATTERY MONITOR added.
 *    - ADC read on GPIO1 (ADC1_CH0, BLE-safe) through a 2x divider.
 *    - Sampled + averaged, converted to a LiPo percentage every 1 s.
 *    - Printed in the debug block AND appended to the FEATURE packet as one
 *      extra byte. Battery is TELEMETRY, NOT a model feature — the TCN input
 *      is still 21 features; your Android parser must read 21 features for
 *      inference and the trailing byte purely as battery %.
 *
 * 3) PACKET FORMAT changed: a 1-byte battery field is appended.
 *        "<BBII18b3h"  (34 B)  ->  "<BBII18b3hB"  (35 B)
 *
 * ====================== SIGNAL CONDITIONING (unchanged) ===================
 *    a) Hardware DLPF tightened to ~10 Hz (anti-aliasing for 50 Hz read).
 *    b) 3-tap MEDIAN filter  -> rejects single-sample I2C spikes.
 *    c) 2nd-order BUTTERWORTH low-pass (RBJ biquad) -> smooth, stable.
 *       Accel cutoff 6 Hz, gyro cutoff 8 Hz (tune via #defines below).
 *    Filters are warm-started so there is no power-on ramp.
 *    The feature path + calibration use the FILTERED signal; RAW mode
 *    still streams the true unfiltered registers for diagnostics.
 *
 * ====================== FEATURE SET (21 features) =========================
 *    - Finger accel (quantized levels -2..+2):   5 fingers x 3 axes = 15
 *    - Wrist  accel (quantized levels -2..+2):   3
 *    - Wrist  gyro  (integer deg/s):             3      TOTAL = 21
 *    (+ 1 battery byte appended to the packet, NOT a feature)
 *
 *    Feature order:
 *    Thumb(ax,ay,az) Index Middle Ring Little | Wrist ax ay az | gx gy gz
 *
 * ====================== CALIBRATION (accel-based) =========================
 *    One-time, gravity-referenced, reproducible across power cycles. Records
 *    each finger's OPEN and FIST accel and maps that range onto the levels:
 *        open -> -2     fist -> +2     intermediate -> 0
 *    Finger axes that barely move between open/fist fall back to absolute
 *    tilt. Wrist axes always use absolute tilt (the wrist does not flex).
 *
 *    Routine (~12 s):
 *      settle 2 s (hold FLAT)
 *      5 s : SLOW blink (500 ms) -> hand FLAT, fingers OPEN
 *      5 s : FAST blink (100 ms) -> make a FIST, keep hand flat
 *    First boot with no saved profile runs it automatically. Press 'c'
 *    (serial or BLE) to redo it any time. Profile is stored in NVS.
 *
 *    status byte: bits 0..5 = per-sensor health, bit7 (0x80) = READY
 *      -> only record/predict on frames with READY set.
 *
 * FEATURE PACKET FORMAT : "<BBII18b3hB" (type 0xA1)  -> 35 bytes
 * RAW PACKET FORMAT     : "<BBII36h"    (type 0xB2)
 *
 * Commands (serial or BLE):
 *    c = recalibrate fingers   z = hard-zero (flat & still)
 *    m = raw/feature toggle     r = factory reset
 ***************************************************************************/

#include <Arduino.h>
#include <Wire.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Preferences.h>
#include <cmath>

// ------------------------------ CONFIG ---------------------------------
#define DEBUG_SERIAL    1

#define TCA9548A_ADDR   0x70
#define NUM_SENSORS     6
#define NUM_FINGERS     (NUM_SENSORS - 1)
#define LED_PIN         LED_BUILTIN          // XIAO: LOW = LED on

#define I2C_SDA_PIN     5
#define I2C_SCL_PIN     6

#define MPU6050_ADDR    0x68
#define REG_PWR_MGMT_1  0x6B
#define REG_CONFIG      0x1A
#define REG_GYRO_CFG    0x1B
#define REG_ACCEL_CFG   0x1C
#define REG_ACCEL_XOUT  0x3B
#define REG_WHO_AM_I    0x75

#define WRIST_CH        5

// Finger mapping [Thumb, Index, Middle, Ring, Little]
// FIXED: Swapped Thumb (previously 3) and Middle (previously 0) due to hardware wiring crossover
// FIXED: Swapped Index and Middle channels -> Index now ch3, Middle now ch1
const uint8_t SENSOR_CH_MAP[NUM_FINGERS] = {0, 3, 1, 4, 2};

// Full-scale settings
const float GYRO_SENS  = 32.8f;    // LSB per deg/s  @ ±1000 dps
const float ACCEL_SENS = 8192.0f;  // LSB per g      @ ±4 g

const float LOOP_HZ   = 50.0f;
const uint32_t LOOP_US = (uint32_t)(1000000.0f / LOOP_HZ);

// Quantizer (5-level: -2, -1, 0, +1, +2)
#define QUANT_LEVEL_MAX  2         // levels clamp to [-QUANT_LEVEL_MAX, +QUANT_LEVEL_MAX]
const float QUANT_HYST = 0.12f;    // hysteresis in LEVEL units (anti-flicker)
const float MIN_SPAN_G = 0.15f;    // min open->fist swing (g) to use calibrated axis

// --- Signal conditioning ---
#define FILTER_ENABLE     1        // master switch for software filtering
#define MEDIAN_ENABLE     1        // 3-tap median spike rejection (runs before the LPF)
#define ACCEL_LPF_HZ      6.0f     // accel low-pass cutoff (Hz) @ 50 Hz loop
#define GYRO_LPF_HZ       8.0f     // gyro  low-pass cutoff (Hz)  @ 50 Hz loop
#define DLPF_CFG_VALUE    0x05     // MPU6050 hardware DLPF: 0x05 ~= 10 Hz (anti-alias)

// Calibration LED blink periods
const int LED_BLINK_OPEN_MS = 500; // slow blink = hand FLAT & OPEN
const int LED_BLINK_FIST_MS = 100; // fast blink = TIGHT FIST

// Misc
const float ACCEL_OFFSET_LIMIT_G = 0.25f;
const uint8_t I2C_FAIL_LIMIT     = 25;
const uint32_t HEALTH_TIMEOUT_MS = 200;
const uint32_t READY_GRACE_MS    = 500;

// ---------------- Battery Monitor ----------------
#define BAT_ADC_PIN        1       // GPIO1 / A0 on XIAO ESP32-S3 (ADC1_CH0, BLE-safe)
#define BATTERY_SAMPLES    10

float   batteryVoltage = 0.0f;
uint8_t batteryPercent = 0;

// BLE UUIDs
#define SERVICE_UUID    "12345678-1234-1234-1234-1234567890ab"
#define DATA_CHAR_UUID  "abcdefab-1234-5678-1234-abcdefabcdef"
#define CMD_CHAR_UUID   "abcdefab-1234-5678-1234-abcdefabcd01"

// Commands
#define CMD_RECAL_FINGERS 0x01   // 'c'
#define CMD_REZERO_IMUS   0x02   // 'z'
#define CMD_FACTORY_RESET 0x03   // 'r'
#define CMD_MODE_RAW      0x10   // 'm'
#define CMD_MODE_FEATURE  0x11

#define PKT_FEATURE  0xA1
#define PKT_RAW      0xB2
#define STATUS_READY_BIT 0x80

enum StreamMode : uint8_t { MODE_FEATURE = 0, MODE_RAW = 1 };

// ------------------------------ GLOBALS --------------------------------
Preferences prefs;
BLEServer *pServer = nullptr;
BLECharacteristic *pDataChar = nullptr;
volatile bool deviceConnected = false;
bool oldDeviceConnected = false;
volatile uint8_t pendingCmd = 0;

StreamMode streamMode = MODE_FEATURE;

bool mtuOk = false;
bool mtuChecked = false;
unsigned long lastMtuWarn = 0;

bool sensorActive[NUM_SENSORS] = {false};
uint8_t i2cFailCount[NUM_SENSORS] = {0};
uint32_t lastGoodReadMs[NUM_SENSORS] = {0};
uint32_t sequenceNumber = 0;
unsigned long lastSerialPrintTime = 0;
uint32_t readyAtMs = 0;

int16_t rawVals[NUM_SENSORS][6];   // ax ay az gx gy gz (true raw — raw mode + filter input)
float   filtVals[NUM_SENSORS][6];  // filtered ax ay az gx gy gz (same LSB units as rawVals)

struct IMUOffsets { int16_t ax, ay, az, gx, gy, gz; };
IMUOffsets offsets[NUM_SENSORS];

float gyroBias[NUM_SENSORS][3];
uint16_t stillCount[NUM_SENSORS] = {0};

// Quantizer hysteresis state (last emitted level per axis)
int8_t fingerLevel[NUM_FINGERS][3] = {{0}};
int8_t wristLevel[3] = {0};

// Per-finger, per-axis OPEN / FIST accel references (g)
struct FingerCal { float openG[3]; float closedG[3]; };
FingerCal fingerCal[NUM_FINGERS];
bool fingerCalValid = false;

// ------------------------------ FILTERS --------------------------------
// 2nd-order Butterworth low-pass, RBJ biquad, Direct-Form II Transposed.
struct Biquad {
  float b0, b1, b2, a1, a2;   // normalized coefficients (a0 == 1)
  float z1, z2;               // state
  bool  primed;

  void setLowpass(float fs, float fc) {
    const float PI_F = 3.14159265358979f;
    float w0    = 2.0f * PI_F * fc / fs;
    float cosw0 = cosf(w0);
    float sinw0 = sinf(w0);
    float Q     = 0.70710678f;            // Butterworth (maximally flat)
    float alpha = sinw0 / (2.0f * Q);
    float a0    = 1.0f + alpha;
    b0 = (1.0f - cosw0) * 0.5f / a0;
    b1 = (1.0f - cosw0)        / a0;
    b2 = (1.0f - cosw0) * 0.5f / a0;
    a1 = (-2.0f * cosw0)       / a0;
    a2 = (1.0f - alpha)        / a0;
    z1 = z2 = 0.0f;
    primed = false;
  }

  // Warm-start the state so output begins at the first real sample (no ramp).
  void prime(float x0) {
    z1 = (b1 - a1 + b2 - a2) * x0;
    z2 = (b2 - a2) * x0;
    primed = true;
  }

  inline float process(float x) {
    if (!primed) prime(x);
    float y = b0 * x + z1;
    z1 = b1 * x - a1 * y + z2;
    z2 = b2 * x - a2 * y;
    return y;
  }
};

Biquad  lpf[NUM_SENSORS][6];          // one biquad per axis (ax ay az gx gy gz)
int16_t medHist[NUM_SENSORS][6][2];   // last two raw samples for the 3-tap median
bool    medPrimed[NUM_SENSORS] = {false};

static inline int16_t median3(int16_t a, int16_t b, int16_t c) {
  if (a > b) { int16_t t = a; a = b; b = t; }
  if (b > c) { int16_t t = b; b = c; c = t; }
  if (a > b) { int16_t t = a; a = b; b = t; }
  return b;
}

void initFilters() {
  for (int ch = 0; ch < NUM_SENSORS; ch++) {
    for (int a = 0; a < 3; a++) lpf[ch][a].setLowpass(LOOP_HZ, ACCEL_LPF_HZ); // accel
    for (int a = 3; a < 6; a++) lpf[ch][a].setLowpass(LOOP_HZ, GYRO_LPF_HZ);  // gyro
    medPrimed[ch] = false;
    for (int a = 0; a < 6; a++) filtVals[ch][a] = 0.0f;
  }
}

// ------------------------------ BATTERY --------------------------------
float readBatteryVoltage() {
  long sum = 0;
  for (int i = 0; i < BATTERY_SAMPLES; i++) {
    sum += analogRead(BAT_ADC_PIN);
    delay(2);
  }
  float adc = sum / (float)BATTERY_SAMPLES;
  // XIAO divider ratio (2x). 12-bit ADC, 3.3V reference.
  float voltage = (adc / 4095.0f) * 3.3f * 2.0f;
  return voltage;
}

uint8_t readBatteryPercentage(float v) {
  if (v >= 4.20f) return 100;
  if (v >= 4.10f) return 90;
  if (v >= 4.00f) return 80;
  if (v >= 3.92f) return 70;
  if (v >= 3.85f) return 60;
  if (v >= 3.80f) return 50;
  if (v >= 3.75f) return 40;
  if (v >= 3.70f) return 30;
  if (v >= 3.65f) return 20;
  if (v >= 3.55f) return 10;
  return 0;
}

// ------------------------------ PACKETS --------------------------------
struct __attribute__((packed)) FeaturePacket {
  uint8_t  type;
  uint8_t  sensorStatus;            // bits0-5 health, bit7 READY
  uint32_t timestamp;
  uint32_t packetCounter;
  int8_t   fingerAccel[NUM_FINGERS][3];  // 15 : levels -2..+2
  int8_t   wristAccel[3];                // 3  : levels -2..+2
  int16_t  wristGyro[3];                 // 3  : integer dps
  uint8_t  battery;                      // 1  : 0..100 % (telemetry, NOT a feature)
};
FeaturePacket featPacket;

struct __attribute__((packed)) RawPacket {
  uint8_t  type;
  uint8_t  sensorStatus;
  uint32_t timestamp;
  uint32_t packetCounter;
  int16_t  raw[NUM_SENSORS][6];
};
RawPacket rawPacket;

// ------------------------------ QUANTIZER ------------------------------
// Snap a continuous level estimate v to {-2,-1,0,+1,+2} with hysteresis.
// Level boundaries sit at half-integers (-1.5, -0.5, +0.5, +1.5).
int8_t snapLevel(float v, int8_t prev) {
  const float H = QUANT_HYST;
  int8_t lvl = prev;
  if (lvl < -QUANT_LEVEL_MAX) lvl = -QUANT_LEVEL_MAX;
  if (lvl >  QUANT_LEVEL_MAX) lvl =  QUANT_LEVEL_MAX;
  while (lvl <  QUANT_LEVEL_MAX && v > (lvl + 0.5f) + H) lvl++;
  while (lvl > -QUANT_LEVEL_MAX && v < (lvl - 0.5f) - H) lvl--;
  return lvl;
}

int16_t offAxis(uint8_t ch, int a) {
  return (a == 0) ? offsets[ch].ax : (a == 1) ? offsets[ch].ay : offsets[ch].az;
}

// ------------------------------ I2C LAYER ------------------------------
void tcaSelect(uint8_t channel) {
  Wire.beginTransmission(TCA9548A_ADDR);
  Wire.write(1 << channel);
  Wire.endTransmission();
}

bool mpuWrite(uint8_t reg, uint8_t val) {
  Wire.beginTransmission(MPU6050_ADDR);
  Wire.write(reg);
  Wire.write(val);
  return Wire.endTransmission() == 0;
}

bool mpuRead8(uint8_t reg, uint8_t &val) {
  Wire.beginTransmission(MPU6050_ADDR);
  Wire.write(reg);
  if (Wire.endTransmission() != 0) return false;
  if (Wire.requestFrom((uint8_t)MPU6050_ADDR, (uint8_t)1) != 1) {
    while (Wire.available()) Wire.read();
    return false;
  }
  val = Wire.read();
  return true;
}

bool mpuConfigure(uint8_t ch) {
  tcaSelect(ch);
  bool ok = true;
  ok &= mpuWrite(REG_PWR_MGMT_1, 0x01);
  ok &= mpuWrite(REG_CONFIG,     DLPF_CFG_VALUE);  // ~10 Hz hardware DLPF (anti-alias)
  ok &= mpuWrite(REG_GYRO_CFG,   0x10);            // ±1000 dps
  ok &= mpuWrite(REG_ACCEL_CFG,  0x08);            // ±4 g
  return ok;
}

void i2cBusRecover() {
  Serial.println("[I2C] Bus recovery: clocking SCL + STOP...");
  Wire.end();
  pinMode(I2C_SCL_PIN, OUTPUT_OPEN_DRAIN);
  pinMode(I2C_SDA_PIN, INPUT_PULLUP);
  digitalWrite(I2C_SCL_PIN, HIGH);
  delayMicroseconds(5);
  for (int i = 0; i < 9 && digitalRead(I2C_SDA_PIN) == LOW; i++) {
    digitalWrite(I2C_SCL_PIN, LOW);  delayMicroseconds(5);
    digitalWrite(I2C_SCL_PIN, HIGH); delayMicroseconds(5);
  }
  pinMode(I2C_SDA_PIN, OUTPUT_OPEN_DRAIN);
  digitalWrite(I2C_SDA_PIN, LOW);  delayMicroseconds(5);
  digitalWrite(I2C_SCL_PIN, HIGH); delayMicroseconds(5);
  digitalWrite(I2C_SDA_PIN, HIGH); delayMicroseconds(5);
  Wire.begin(I2C_SDA_PIN, I2C_SCL_PIN);
  Wire.setClock(400000);
}

bool readRawRegisters(uint8_t ch, int16_t &ax, int16_t &ay, int16_t &az,
                      int16_t &gx, int16_t &gy, int16_t &gz) {
  tcaSelect(ch);
  Wire.beginTransmission(MPU6050_ADDR);
  Wire.write(REG_ACCEL_XOUT);
  if (Wire.endTransmission() != 0) return false;
  uint8_t n = Wire.requestFrom((uint8_t)MPU6050_ADDR, (uint8_t)14);
  if (n == 14) {
    ax = (Wire.read() << 8) | Wire.read();
    ay = (Wire.read() << 8) | Wire.read();
    az = (Wire.read() << 8) | Wire.read();
    (void)((Wire.read() << 8) | Wire.read());   // temperature
    gx = (Wire.read() << 8) | Wire.read();
    gy = (Wire.read() << 8) | Wire.read();
    gz = (Wire.read() << 8) | Wire.read();
    return true;
  }
  while (Wire.available()) Wire.read();
  return false;
}

uint8_t computeSensorStatus() {
  uint8_t s = 0;
  uint32_t now = millis();
  for (int i = 0; i < NUM_SENSORS; i++) {
    if (sensorActive[i] && (now - lastGoodReadMs[i]) < HEALTH_TIMEOUT_MS)
      s |= (1 << i);
  }
  return s;
}

bool featuresReady() {
  if (readyAtMs == 0 || millis() < readyAtMs) return false;
  if (!sensorActive[WRIST_CH]) return false;
  if ((millis() - lastGoodReadMs[WRIST_CH]) >= HEALTH_TIMEOUT_MS) return false;
  return true;
}

// ------------------------------- BLE -----------------------------------
class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *s) override {
    deviceConnected = true; mtuChecked = false; mtuOk = false;
    digitalWrite(LED_PIN, LOW); // LOW is ON
  }
  void onDisconnect(BLEServer *s) override {
    deviceConnected = false; mtuChecked = false; mtuOk = false;
    digitalWrite(LED_PIN, HIGH); // HIGH is OFF
  }
};

class CmdCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    String v = c->getValue();
    if (v.length() > 0) pendingCmd = (uint8_t)v[0];
  }
};

void blinkLED(int count, int duration) {
  for (int i = 0; i < count; i++) {
    digitalWrite(LED_PIN, LOW);  delay(duration);
    digitalWrite(LED_PIN, HIGH); delay(duration);
  }
}

// --------------------------- SENSOR PIPELINE ---------------------------
void updateAllSensors() {
  for (int ch = 0; ch < NUM_SENSORS; ch++) {
    if (!sensorActive[ch]) continue;
    int16_t rAx, rAy, rAz, rGx, rGy, rGz;
    if (!readRawRegisters(ch, rAx, rAy, rAz, rGx, rGy, rGz)) {
      if (++i2cFailCount[ch] >= I2C_FAIL_LIMIT) {
        Serial.printf("[I2C] Channel %d: %d consecutive failures.\n", ch, i2cFailCount[ch]);
        i2cBusRecover();
        if (mpuConfigure(ch)) Serial.printf("[I2C] Channel %d re-initialised OK.\n", ch);
        else { Serial.printf("[I2C] Channel %d re-init FAILED — disabling.\n", ch); sensorActive[ch] = false; }
        i2cFailCount[ch] = 0;
      }
      continue;
    }
    i2cFailCount[ch] = 0;
    lastGoodReadMs[ch] = millis();

    rawVals[ch][0] = rAx; rawVals[ch][1] = rAy; rawVals[ch][2] = rAz;
    rawVals[ch][3] = rGx; rawVals[ch][4] = rGy; rawVals[ch][5] = rGz;

#if FILTER_ENABLE
    for (int a = 0; a < 6; a++) {
      int16_t x = rawVals[ch][a];
  #if MEDIAN_ENABLE
      int16_t m = medPrimed[ch] ? median3(x, medHist[ch][a][0], medHist[ch][a][1]) : x;
      medHist[ch][a][1] = medHist[ch][a][0];
      medHist[ch][a][0] = x;
      filtVals[ch][a] = lpf[ch][a].process((float)m);
  #else
      filtVals[ch][a] = lpf[ch][a].process((float)x);
  #endif
    }
    medPrimed[ch] = true;
#else
    for (int a = 0; a < 6; a++) filtVals[ch][a] = (float)rawVals[ch][a];
#endif

    float gxd = ((float)rGx - gyroBias[ch][0]) / GYRO_SENS;
    float gyd = ((float)rGy - gyroBias[ch][1]) / GYRO_SENS;
    float gzd = ((float)rGz - gyroBias[ch][2]) / GYRO_SENS;
    float gMag = fabsf(gxd) + fabsf(gyd) + fabsf(gzd);
    float aMag = sqrtf((float)rAx*rAx + (float)rAy*rAy + (float)rAz*rAz) / ACCEL_SENS;
    if (gMag < 3.0f && fabsf(aMag - 1.0f) < 0.06f) {
      if (stillCount[ch] < 60000) stillCount[ch]++;
      if (stillCount[ch] > (uint16_t)LOOP_HZ) {
        const float a = 0.002f;
        gyroBias[ch][0] = (1 - a) * gyroBias[ch][0] + a * (float)rGx;
        gyroBias[ch][1] = (1 - a) * gyroBias[ch][1] + a * (float)rGy;
        gyroBias[ch][2] = (1 - a) * gyroBias[ch][2] + a * (float)rGz;
      }
    } else {
      stillCount[ch] = 0;
    }
  }
}

float accelG(uint8_t ch, int a) {
  return (filtVals[ch][a] - (float)offAxis(ch, a)) / ACCEL_SENS;
}

int8_t quantFingerAxis(int idx, int a, int8_t prev) {
  float g = accelG(SENSOR_CH_MAP[idx], a);
  float v;
  if (fingerCalValid) {
    float span = fingerCal[idx].closedG[a] - fingerCal[idx].openG[a];
    if (fabsf(span) >= MIN_SPAN_G)
      v = ((g - fingerCal[idx].openG[a]) / span) * 4.0f - 2.0f;  // open->-2, fist->+2
    else
      v = g * 2.0f;                                              // absolute tilt (±1g -> ±2)
  } else {
    v = g * 2.0f;
  }
  return snapLevel(v, prev);
}

// ----------------------------- NVS: OFFSETS ----------------------------
void computeHardZeroOffsets() {
  Serial.println("[CAL] HARD-ZERO: keep the glove COMPLETELY FLAT & STILL!");
  blinkLED(10, 50);
  const int samples = 400;
  prefs.begin("gestalk", false);
  for (int i = 0; i < NUM_SENSORS; i++) {
    if (!sensorActive[i]) continue;
    long axS = 0, ayS = 0, azS = 0, gxS = 0, gyS = 0, gzS = 0;
    for (int s = 0; s < samples; s++) {
      int16_t ax, ay, az, gx, gy, gz;
      if (readRawRegisters(i, ax, ay, az, gx, gy, gz)) {
        axS += ax; ayS += ay; azS += az; gxS += gx; gyS += gy; gzS += gz;
      }
      delay(2);
    }
    offsets[i].ax = axS / samples;
    offsets[i].ay = ayS / samples;
    offsets[i].az = (azS / samples) - (int16_t)ACCEL_SENS;  // keep the 1g on Z
    offsets[i].gx = gxS / samples;
    offsets[i].gy = gyS / samples;
    offsets[i].gz = gzS / samples;

    float offXg = fabsf((float)offsets[i].ax) / ACCEL_SENS;
    float offYg = fabsf((float)offsets[i].ay) / ACCEL_SENS;
    if (offXg > ACCEL_OFFSET_LIMIT_G || offYg > ACCEL_OFFSET_LIMIT_G) {
      Serial.printf("[CAL] Ch %d not flat (X %.2fg, Y %.2fg) — accel offsets discarded, gyro kept.\n", i, offXg, offYg);
      offsets[i].ax = 0; offsets[i].ay = 0; offsets[i].az = 0;
    }
    String key = "off_" + String(i);
    prefs.putBytes(key.c_str(), &offsets[i], sizeof(IMUOffsets));
  }
  prefs.putBool("imu_valid", true);
  prefs.end();
  Serial.println("[CAL] Hard-zero offsets saved to NVS.");
}

void loadStaticIMUOffsets() {
  prefs.begin("gestalk", true);
  bool valid = prefs.getBool("imu_valid", false);
  if (valid) {
    for (int i = 0; i < NUM_SENSORS; i++) {
      String key = "off_" + String(i);
      prefs.getBytes(key.c_str(), &offsets[i], sizeof(IMUOffsets));
    }
    prefs.end();
    Serial.println("[NVS] Static IMU offsets loaded.");
  } else {
    prefs.end();
    Serial.println("[NVS] No offsets found — running hard-zero now.");
    computeHardZeroOffsets();
  }
  for (int i = 0; i < NUM_SENSORS; i++) {
    gyroBias[i][0] = offsets[i].gx;
    gyroBias[i][1] = offsets[i].gy;
    gyroBias[i][2] = offsets[i].gz;
  }
}

// ----------------------- FINGER CALIBRATION RUN ------------------------
// Non-blocking time-differential checks to allow crisp visual toggling
// of the inverted-logic user LED on Seeed XIAO ESP32-S3 boards.
void calPump(uint32_t ms, double sums[NUM_FINGERS][3], uint32_t *count,
             uint32_t skipMs, int blinkPeriod) {
  uint32_t start = millis();
  uint32_t lastUs = micros();
  uint32_t lastBlinkMs = millis();
  bool ledState = false;

  // Initialize starting physical state mapping cleanly
  digitalWrite(LED_PIN, ledState ? LOW : HIGH);

  while (millis() - start < ms) {
    uint32_t nowMs = millis();

    // Smooth timestamp interval comparison prevents data bus collision freezes
    if (nowMs - lastBlinkMs >= (uint32_t)blinkPeriod) {
      lastBlinkMs = nowMs;
      ledState = !ledState;
      digitalWrite(LED_PIN, ledState ? LOW : HIGH); // LOW is ON, HIGH is OFF
    }

    uint32_t nowUs = micros();
    if (nowUs - lastUs >= LOOP_US) {
      lastUs = nowUs;
      updateAllSensors();
      if (sums != nullptr && (millis() - start) > skipMs) {
        for (int idx = 0; idx < NUM_FINGERS; idx++) {
          uint8_t ch = SENSOR_CH_MAP[idx];
          if (!sensorActive[ch]) continue;
          sums[idx][0] += (double)accelG(ch, 0);
          sums[idx][1] += (double)accelG(ch, 1);
          sums[idx][2] += (double)accelG(ch, 2);
        }
        (*count)++;
      }
    }
  }
}

void runFingerCalibration() {
  Serial.println("\n[CAL] ===== FINGER CALIBRATION (~12s) =====");
  Serial.println("[CAL] Settling (2s)... hold hand FLAT.");
  calPump(2000, nullptr, nullptr, 0, 1000);

  double openSums[NUM_FINGERS][3]   = {{0}};
  double closedSums[NUM_FINGERS][3] = {{0}};
  uint32_t openN = 0, closedN = 0;

  Serial.println("[CAL] STEP 1/2: SLOW blink (500ms) -> hand FLAT, fingers OPEN (5s)");
  calPump(5000, openSums, &openN, 1000, LED_BLINK_OPEN_MS);

  Serial.println("[CAL] STEP 2/2: FAST blink (100ms) -> make a FIST, keep hand FLAT (5s)");
  calPump(5000, closedSums, &closedN, 1000, LED_BLINK_FIST_MS);

  const char *fn[NUM_FINGERS] = {"Thumb", "Index", "Middle", "Ring", "Little"};
  prefs.begin("gestalk", false);
  for (int idx = 0; idx < NUM_FINGERS; idx++) {
    float bestSpan = 0.0f; int bestAxis = 0;
    for (int a = 0; a < 3; a++) {
      fingerCal[idx].openG[a]   = openN   ? (float)(openSums[idx][a]   / openN)   : 0.0f;
      fingerCal[idx].closedG[a] = closedN ? (float)(closedSums[idx][a] / closedN) : 0.0f;
      float span = fabsf(fingerCal[idx].closedG[a] - fingerCal[idx].openG[a]);
      if (span > bestSpan) { bestSpan = span; bestAxis = a; }
    }
    const char *ax3[3] = {"X", "Y", "Z"};
    Serial.printf("[CAL] %-6s (Ch%d) best axis %s span %.2fg | open[% .2f % .2f % .2f] fist[% .2f % .2f % .2f] %s\n",
                  fn[idx], SENSOR_CH_MAP[idx], ax3[bestAxis], bestSpan,
                  fingerCal[idx].openG[0], fingerCal[idx].openG[1], fingerCal[idx].openG[2],
                  fingerCal[idx].closedG[0], fingerCal[idx].closedG[1], fingerCal[idx].closedG[2],
                  bestSpan < MIN_SPAN_G ? " << WARNING: little motion, check mounting!" : "");
    String key = "fcg_" + String(idx);
    prefs.putBytes(key.c_str(), &fingerCal[idx], sizeof(FingerCal));
  }
  prefs.putBool("fcg_valid", true);
  prefs.end();
  fingerCalValid = true;

  for (int i = 0; i < NUM_FINGERS; i++)
    for (int a = 0; a < 3; a++) fingerLevel[i][a] = 0;   // reset hysteresis state

  digitalWrite(LED_PIN, HIGH); // Ensure physical pin shuts OFF safely post extraction tracking run
  Serial.println("[CAL] Saved to NVS. Done.\n");
}

bool loadFingerCalibration() {
  prefs.begin("gestalk", true);
  bool valid = prefs.getBool("fcg_valid", false);
  if (valid) {
    for (int i = 0; i < NUM_FINGERS; i++) {
      String key = "fcg_" + String(i);
      prefs.getBytes(key.c_str(), &fingerCal[i], sizeof(FingerCal));
    }
  }
  prefs.end();
  fingerCalValid = valid;
  return valid;
}

// ----------------------------- COMMANDS --------------------------------
void handleCommands() {
  uint8_t cmd = pendingCmd;
  pendingCmd = 0;
  if (Serial.available()) {
    char c = Serial.read();
    if (c == 'c' || c == 'C') cmd = CMD_RECAL_FINGERS;
    if (c == 'z' || c == 'Z') cmd = CMD_REZERO_IMUS;
    if (c == 'r' || c == 'R') cmd = CMD_FACTORY_RESET;
    if (c == 'm' || c == 'M')
      cmd = (streamMode == MODE_FEATURE) ? CMD_MODE_RAW : CMD_MODE_FEATURE;
  }
  if (cmd == CMD_RECAL_FINGERS) {
    runFingerCalibration();
    readyAtMs = millis() + READY_GRACE_MS;
  } else if (cmd == CMD_REZERO_IMUS) {
    computeHardZeroOffsets();
    for (int i = 0; i < NUM_SENSORS; i++) {
      gyroBias[i][0] = offsets[i].gx;
      gyroBias[i][1] = offsets[i].gy;
      gyroBias[i][2] = offsets[i].gz;
      stillCount[i] = 0;
    }
  } else if (cmd == CMD_MODE_RAW) {
    streamMode = MODE_RAW; mtuChecked = false;
    Serial.println("[MODE] RAW streaming (fmt \"<BBII36h\").");
  } else if (cmd == CMD_MODE_FEATURE) {
    streamMode = MODE_FEATURE; mtuChecked = false;
    Serial.println("[MODE] FEATURE streaming (fmt \"<BBII18b3hB\").");
  } else if (cmd == CMD_FACTORY_RESET) {
    Serial.println("[SYSTEM] Factory reset: wiping NVS and rebooting...");
    prefs.begin("gestalk", false); prefs.clear(); prefs.end();
    delay(200); ESP.restart();
  }
}

// ------------------------------- SETUP ---------------------------------
void setup() {
  Serial.begin(460800);
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, HIGH);

  // ADC config for battery monitor
  analogReadResolution(12);
  pinMode(BAT_ADC_PIN, INPUT);

  Wire.begin(I2C_SDA_PIN, I2C_SCL_PIN);
  Wire.setClock(400000);
  delay(500);

  for (int i = 0; i < NUM_SENSORS; i++) {
    tcaSelect(i);
    uint8_t who = 0x00;
    if (!mpuRead8(REG_WHO_AM_I, who)) { Serial.printf("[WARN] Channel %d: no WHO_AM_I.\n", i); continue; }
    if (who != 0x68) Serial.printf("[WARN] Channel %d: WHO_AM_I = 0x%02X (expected 0x68).\n", i, who);
    if (mpuConfigure(i)) sensorActive[i] = true;
    else Serial.printf("[WARN] Channel %d configuration failed.\n", i);
  }

  initFilters();   // must run before any updateAllSensors() (calibration uses it)
  loadStaticIMUOffsets();

  // Prime battery reading once so the first packets carry a real value.
  batteryVoltage = readBatteryVoltage();
  batteryPercent = readBatteryPercentage(batteryVoltage);

  // One-time finger calibration: auto-run on first boot, else load from NVS.
  if (loadFingerCalibration()) {
    Serial.println("[NVS] Finger calibration profile loaded.");
  } else {
    Serial.println("[NVS] No calibration found — running one-time calibration now.");
    runFingerCalibration();
  }
  readyAtMs = millis() + READY_GRACE_MS;

  BLEDevice::init("Gestalk_Glove");
  BLEDevice::setMTU(247);
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());
  BLEService *pService = pServer->createService(SERVICE_UUID);

  pDataChar = pService->createCharacteristic(
      DATA_CHAR_UUID,
      BLECharacteristic::PROPERTY_NOTIFY | BLECharacteristic::PROPERTY_READ);
  pDataChar->addDescriptor(new BLE2902());

  BLECharacteristic *pCmdChar = pService->createCharacteristic(
      CMD_CHAR_UUID, BLECharacteristic::PROPERTY_WRITE);
  pCmdChar->setCallbacks(new CmdCallbacks());

  pService->start();
  BLEDevice::getAdvertising()->addServiceUUID(SERVICE_UUID);
  BLEDevice::startAdvertising();

  Serial.printf("[SYSTEM] Setup finished. Feature pkt: %u B | Raw pkt: %u B | %.0f Hz.\n",
                (unsigned)sizeof(FeaturePacket), (unsigned)sizeof(RawPacket), LOOP_HZ);
  Serial.println("[SYSTEM] FEATURE fmt \"<BBII18b3hB\" (type 0xA1) | 21 features + 1 battery byte | levels -2..+2");
  Serial.println("[SYSTEM] order: Thumb(ax,ay,az) Index Middle Ring Little | Wrist ax ay az | gx gy gz | battery");
  Serial.printf("[SYSTEM] Filters: HW DLPF=0x%02X | median %s | accel LPF %.1f Hz | gyro LPF %.1f Hz\n",
                DLPF_CFG_VALUE,
#if FILTER_ENABLE && MEDIAN_ENABLE
                "ON",
#else
                "OFF",
#endif
                ACCEL_LPF_HZ, GYRO_LPF_HZ);
  Serial.println("[SYSTEM] *** Only use frames with READY bit (0x80) set. ***");
  Serial.printf("[SYSTEM] Client app must request BLE MTU >= %u.\n", (unsigned)(sizeof(FeaturePacket) + 3));
  Serial.println("[SYSTEM] Commands: c = recalibrate, z = hard-zero, m = raw/feature, r = factory reset");
}

// -------------------------------- LOOP ---------------------------------
void loop() {
  handleCommands();

  // ---------------- Battery update (1 Hz) ----------------
  static uint32_t lastBatteryRead = 0;
  if (millis() - lastBatteryRead > 1000) {
    lastBatteryRead = millis();
    batteryVoltage = readBatteryVoltage();
    batteryPercent = readBatteryPercentage(batteryVoltage);
  }

  if (!deviceConnected && oldDeviceConnected) {
    delay(500); BLEDevice::startAdvertising(); oldDeviceConnected = deviceConnected;
  }
  if (deviceConnected && !oldDeviceConnected) oldDeviceConnected = deviceConnected;

  static uint32_t nextTickUs = 0;
  uint32_t nowUs = micros();
  if (nextTickUs == 0) nextTickUs = nowUs;
  if ((int32_t)(nowUs - nextTickUs) < 0) return;
  nextTickUs += LOOP_US;
  if ((int32_t)(nowUs - nextTickUs) > (int32_t)(4 * LOOP_US)) nextTickUs = nowUs + LOOP_US;

  updateAllSensors();

  uint8_t status = computeSensorStatus();
  if (featuresReady()) status |= STATUS_READY_BIT;

  // ---------------- RAW MODE (true unfiltered registers) ----------------
  if (streamMode == MODE_RAW) {
    rawPacket.type = PKT_RAW; rawPacket.sensorStatus = status;
    rawPacket.timestamp = millis(); rawPacket.packetCounter = sequenceNumber++;
    memcpy(rawPacket.raw, rawVals, sizeof(rawVals));
    if (deviceConnected && pDataChar != nullptr) {
      if (!mtuChecked) {
        uint16_t peerMtu = pServer->getPeerMTU(pServer->getConnId());
        mtuOk = (peerMtu >= sizeof(RawPacket) + 3); mtuChecked = true;
        Serial.printf("[BLE] MTU = %u -> %s\n", peerMtu, mtuOk ? "OK (RAW)." : "TOO SMALL!");
      }
      if (mtuOk) { pDataChar->setValue((uint8_t *)&rawPacket, sizeof(RawPacket)); pDataChar->notify(); }
    }
    return;
  }

  // ---------------- FEATURE MODE (filtered + quantized) ----------------
  featPacket.type          = PKT_FEATURE;
  featPacket.sensorStatus  = status;
  featPacket.timestamp     = millis();
  featPacket.packetCounter = sequenceNumber++;

  // 15 finger accel levels (calibrated, -2..+2)
  for (int idx = 0; idx < NUM_FINGERS; idx++) {
    uint8_t fCh = SENSOR_CH_MAP[idx];
    if (sensorActive[fCh]) {
      for (int a = 0; a < 3; a++) {
        fingerLevel[idx][a] = quantFingerAxis(idx, a, fingerLevel[idx][a]);
        featPacket.fingerAccel[idx][a] = fingerLevel[idx][a];
      }
    } else {
      for (int a = 0; a < 3; a++) { featPacket.fingerAccel[idx][a] = 0; fingerLevel[idx][a] = 0; }
    }
  }

  // 3 wrist accel levels (absolute tilt, -2..+2) + 3 wrist gyro (integer dps)
  if (sensorActive[WRIST_CH]) {
    for (int a = 0; a < 3; a++) {
      wristLevel[a] = snapLevel(accelG(WRIST_CH, a) * 2.0f, wristLevel[a]);
      featPacket.wristAccel[a] = wristLevel[a];
    }
    float gxd = (filtVals[WRIST_CH][3] - gyroBias[WRIST_CH][0]) / GYRO_SENS;
    float gyd = (filtVals[WRIST_CH][4] - gyroBias[WRIST_CH][1]) / GYRO_SENS;
    float gzd = (filtVals[WRIST_CH][5] - gyroBias[WRIST_CH][2]) / GYRO_SENS;
    featPacket.wristGyro[0] = (int16_t)lroundf(gxd);
    featPacket.wristGyro[1] = (int16_t)lroundf(gyd);
    featPacket.wristGyro[2] = (int16_t)lroundf(gzd);
  } else {
    featPacket.wristAccel[0] = featPacket.wristAccel[1] = featPacket.wristAccel[2] = 0;
    featPacket.wristGyro[0]  = featPacket.wristGyro[1]  = featPacket.wristGyro[2]  = 0;
  }

  // Battery telemetry (NOT a model feature)
  featPacket.battery = batteryPercent;

  // ---------------- BLE notify ----------------
  if (deviceConnected && pDataChar != nullptr) {
    if (!mtuChecked) {
      uint16_t peerMtu = pServer->getPeerMTU(pServer->getConnId());
      mtuOk = (peerMtu >= sizeof(FeaturePacket) + 3); mtuChecked = true;
      Serial.printf("[BLE] MTU = %u -> %s\n", peerMtu, mtuOk ? "OK, streaming." : "TOO SMALL!");
    }
    if (mtuOk) {
      pDataChar->setValue((uint8_t *)&featPacket, sizeof(FeaturePacket));
      pDataChar->notify();
    } else if (millis() - lastMtuWarn > 2000) {
      lastMtuWarn = millis();
      Serial.printf("[BLE] MTU too small — app must request MTU >= %u\n", (unsigned)(sizeof(FeaturePacket) + 3));
      mtuChecked = false;
    }
  }

  // ---------------- Debug print (5 Hz) ----------------
#if DEBUG_SERIAL
  if (millis() - lastSerialPrintTime >= 200) {
    lastSerialPrintTime = millis();
    const char *fn[NUM_FINGERS] = {"Thumb", "Index", "Middle", "Ring", "Little"};
    Serial.printf("\n--- Frame %u | BLE %s | health 0x%02X | %s | cal %s ---\n",
                  featPacket.packetCounter, deviceConnected ? "CONN" : "ADV",
                  featPacket.sensorStatus, (status & STATUS_READY_BIT) ? "READY" : "settling",
                  fingerCalValid ? "OK" : "NONE");
    for (int i = 0; i < NUM_FINGERS; i++)
      Serial.printf("%-6s [% d % d % d]  ", fn[i],
                    featPacket.fingerAccel[i][0], featPacket.fingerAccel[i][1], featPacket.fingerAccel[i][2]);
    Serial.println();

    // WRIST PARAMETERS PRESERVED DIRECTLY BELOW
    Serial.printf("Wrist  acc[% d % d % d]  gyro[% d % d % d] dps\n",
                  featPacket.wristAccel[0], featPacket.wristAccel[1], featPacket.wristAccel[2],
                  featPacket.wristGyro[0], featPacket.wristGyro[1], featPacket.wristGyro[2]);

    Serial.printf("Battery %.2fV  %d%%\n", batteryVoltage, batteryPercent);
  }
#endif
}
