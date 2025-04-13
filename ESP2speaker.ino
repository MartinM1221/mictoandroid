#include <WiFi.h>
#include "driver/i2s.h"
#include <ESP32Servo.h> // <<< Include Servo library

// --- Wi-Fi Settings ---
const char* ssid = "//";        // <<< REPLACE
const char* password = "//"; // <<< REPLACE

// --- Network Settings ---
#define AUDIO_PORT 8081 // Port for receiving audio data from Android
#define CONTROL_PORT 8082 // Port for receiving control commands

WiFiServer audioServer(AUDIO_PORT);
WiFiClient audioClient;

WiFiServer controlServer(CONTROL_PORT); // Server for control commands
WiFiClient controlClient;            // Client for control commands

// --- I2S Pin Configuration (for MAX98357A Speaker - OUTPUT) ---
#define I2S_SPEAKER_LRC_PIN   25 // WS/LRCK -> MAX98357A LRC
#define I2S_SPEAKER_BCLK_PIN  26 // SCK/BCLK -> MAX98357A BCK
#define I2S_SPEAKER_DIN_PIN   22 // SD/DOUT -> MAX98357A DIN

// --- I2S Settings ---
#define I2S_SPEAKER_PORT    I2S_NUM_0
#define I2S_SAMPLE_RATE     16000
#define I2S_BITS_PER_SAMPLE I2S_BITS_PER_SAMPLE_16BIT
#define I2S_CHANNEL_FORMAT  I2S_CHANNEL_FMT_RIGHT_LEFT // Use RIGHT_LEFT even for mono

// --- Audio Buffer ---
#define NETWORK_RECEIVE_BUFFER_SIZE 1024
uint8_t network_receive_buffer[NETWORK_RECEIVE_BUFFER_SIZE];

// --- Servo Configuration ---
#define SERVO1_PIN 23 // GPIO pin for Servo 1
#define SERVO2_PIN 18 // GPIO pin for Servo 2

Servo servo1; // Servo object 1
Servo servo2; // Servo object 2

// Function Prototypes
void setup_i2s_speaker();
void handleAudioClient();
void handleControlClient();

// --- Setup ---
void setup() {
  Serial.begin(115200);
  Serial.println("\n\n--- ESP32 #2: Speaker Receiver + Servo Control ---");

  // --- Connect to Wi-Fi ---
  Serial.print("Connecting to "); Serial.println(ssid);
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) { delay(500); Serial.print("."); }
  Serial.println("\nWiFi connected!");
  Serial.print("IP Address: "); Serial.println(WiFi.localIP());

  // --- Configure I2S Output (Speaker on I2S0) ---
  setup_i2s_speaker();

  // --- Setup Servos ---
  Serial.println("Setting up Servos...");
  // Allow allocation of all timers
  ESP32PWM::allocateTimer(0); ESP32PWM::allocateTimer(1);
  ESP32PWM::allocateTimer(2); ESP32PWM::allocateTimer(3);
  servo1.setPeriodHertz(50);    // Standard 50Hz servo frequency
  servo2.setPeriodHertz(50);
  // Attach servo specifying min/max pulse width (us) - adjust if needed for your servos
  servo1.attach(SERVO1_PIN, 500, 2500);
  servo2.attach(SERVO2_PIN, 500, 2500);
  servo1.write(90); // Set initial position
  servo2.write(90); // Set initial position
  Serial.println("Servos Attached.");

  // --- Start TCP Servers ---
  audioServer.begin();
  Serial.printf("Audio Server started on port %d\n", AUDIO_PORT);
  controlServer.begin();
  Serial.printf("Control Server started on port %d\n", CONTROL_PORT);
  Serial.println("Waiting for clients...");
}

// --- I2S Speaker Setup ---
void setup_i2s_speaker() {
  Serial.println("Configuring I2S Speaker Output (I2S_NUM_0)...");
    i2s_config_t i2s_speaker_config = {
     .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX),
     .sample_rate = I2S_SAMPLE_RATE,
     .bits_per_sample = I2S_BITS_PER_SAMPLE,
     .channel_format = I2S_CHANNEL_FORMAT,
     .communication_format = (i2s_comm_format_t)(I2S_COMM_FORMAT_STAND_I2S),
     .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
     .dma_buf_count = 8, // Increased buffer count
     .dma_buf_len = 256, // Increased buffer length
     .use_apll = true,
     .tx_desc_auto_clear = true,
     .fixed_mclk = 0
   };
    i2s_pin_config_t speaker_pin_config = {
     .bck_io_num = I2S_SPEAKER_BCLK_PIN,
     .ws_io_num = I2S_SPEAKER_LRC_PIN,
     .data_out_num = I2S_SPEAKER_DIN_PIN,
     .data_in_num = I2S_PIN_NO_CHANGE
   };
  esp_err_t err;
  err = i2s_driver_install(I2S_SPEAKER_PORT, &i2s_speaker_config, 0, NULL);
  if (err != ESP_OK) { Serial.printf("I2S Install failed: %d\n", err); while(true); }
  err = i2s_set_pin(I2S_SPEAKER_PORT, &speaker_pin_config);
  if (err != ESP_OK) { Serial.printf("I2S Set Pin failed: %d\n", err); while(true); }
  err = i2s_zero_dma_buffer(I2S_SPEAKER_PORT);
  if (err != ESP_OK) { Serial.printf("I2S Zero DMA failed: %d\n", err); }
  Serial.println("I2S Speaker driver installed.");
}

// --- Main Loop ---
void loop() {
  handleAudioClient();    // Handle audio connection and data
  handleControlClient();  // Handle control connection and commands
  delay(1); // Small delay to prevent tight loop hogging CPU
}

// --- Audio Client Handling ---
void handleAudioClient() {
  if (!audioClient.connected()) {
    audioClient = audioServer.available();
    if (audioClient) {
      Serial.println("Audio client connected!");
      Serial.print("Audio Client IP: "); Serial.println(audioClient.remoteIP());
      i2s_zero_dma_buffer(I2S_SPEAKER_PORT);
      audioClient.setNoDelay(true);
    }
  }
  if (audioClient.connected()) {
    int availableBytes = audioClient.available();
    if (availableBytes > 0) {
      size_t bytesToRead = min((size_t)availableBytes, sizeof(network_receive_buffer));
      size_t bytesRead = audioClient.read(network_receive_buffer, bytesToRead);
      if (bytesRead > 0) {
        if (bytesRead % 2 != 0) { Serial.printf("Warning: Odd audio bytes (%d).\n", bytesRead); }
        size_t bytes_written = 0;
        esp_err_t result = i2s_write(I2S_SPEAKER_PORT, network_receive_buffer, bytesRead, &bytes_written, portMAX_DELAY);
        if (result != ESP_OK) { Serial.printf("I2S Write Error: %d\n", result); }
        if (bytes_written != bytesRead) { Serial.printf("Warning: I2S Write %d/%d\n", bytes_written, bytesRead); }
      }
    }
    if (!audioClient.connected()) { Serial.println("Audio client disconnected."); }
  }
}

// --- Control Client Handling ---
void handleControlClient() {
  if (!controlClient.connected()) {
    controlClient = controlServer.available();
    if (controlClient) {
      Serial.println("Control client connected!");
      Serial.print("Control Client IP: "); Serial.println(controlClient.remoteIP());
      controlClient.setNoDelay(true);
    }
  }
  if (controlClient.connected()) {
    if (controlClient.available()) {
      String command = controlClient.readStringUntil('\n');
      command.trim();
      Serial.print("Received command: "); Serial.println(command);
      if (command.startsWith("S1=")) {
        int angle = command.substring(3).toInt();
        if (angle >= 0 && angle <= 180) { servo1.write(angle); Serial.print("Set Servo 1: "); Serial.println(angle); }
        else { Serial.println("Invalid S1 angle."); }
      } else if (command.startsWith("S2=")) {
        int angle = command.substring(3).toInt();
         if (angle >= 0 && angle <= 180) { servo2.write(angle); Serial.print("Set Servo 2: "); Serial.println(angle); }
         else { Serial.println("Invalid S2 angle."); }
      } else { Serial.println("Unknown command."); }
    }
     if (!controlClient.connected()) { Serial.println("Control client disconnected."); }
  }
}
