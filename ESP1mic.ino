#include <WiFi.h>
#include "driver/i2s.h"

// --- Wi-Fi Settings ---
const char* ssid = "//";         // Replace with your Wi-Fi network name
const char* password = "//"; // Replace with your Wi-Fi password

// --- I2S Pin Configuration ---
#define I2S_WS_PIN   15  // Word Select (L/R Clock) - Connect to INMP441 WS
#define I2S_SCK_PIN  14  // Serial Clock (Bit Clock) - Connect to INMP441 SCK
#define I2S_SD_PIN   32  // Serial Data (Data Out from Mic) - Connect to INMP441 SD

// --- I2S Settings ---
#define I2S_PORT        I2S_NUM_0 // Use I2S Port 0
#define I2S_SAMPLE_RATE 16000     // Sample rate (Hz)
#define I2S_BITS_PER_SAMPLE I2S_BITS_PER_SAMPLE_32BIT // INMP441 outputs 32-bit samples
#define I2S_READ_BUFFER_SIZE 1024 // Bytes to read at a time
#define I2S_CHANNEL_FORMAT I2S_CHANNEL_FMT_ONLY_LEFT // Assuming L/R tied to GND

// --- Network Settings ---
#define TCP_PORT 8080 // Port for the TCP server (Android connects here to RECEIVE)

WiFiServer server(TCP_PORT);
WiFiClient client;

// Buffer to hold audio data read from I2S
int8_t i2s_read_buffer[I2S_READ_BUFFER_SIZE];

void setup() {
  Serial.begin(115200);
  Serial.println("ESP32 #1: INMP441 Mic Sender Starting..."); // Identify board

  // --- Connect to Wi-Fi ---
  Serial.print("Connecting to "); Serial.println(ssid);
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) { delay(500); Serial.print("."); }
  Serial.println("\nWiFi connected!");
  Serial.print("THIS ESP32 (#1 Mic Sender) IP Address: "); // <<< NOTE THIS IP
  Serial.println(WiFi.localIP());                         // <<< NOTE THIS IP

  // --- Configure I2S ---
  Serial.println("Configuring I2S Mic Input...");
  i2s_config_t i2s_config = {
      .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX),
      .sample_rate = I2S_SAMPLE_RATE,
      .bits_per_sample = I2S_BITS_PER_SAMPLE,
      .channel_format = I2S_CHANNEL_FORMAT,
      .communication_format = I2S_COMM_FORMAT_STAND_I2S,
      .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
      .dma_buf_count = 4,
      .dma_buf_len = I2S_READ_BUFFER_SIZE / 4, // samples per buffer
      .use_apll = true, // Use APLL might be more stable
      .tx_desc_auto_clear = false,
      .fixed_mclk = 0
  };
  i2s_pin_config_t pin_config = {
      .bck_io_num = I2S_SCK_PIN,
      .ws_io_num = I2S_WS_PIN,
      .data_out_num = I2S_PIN_NO_CHANGE,
      .data_in_num = I2S_SD_PIN
  };
  esp_err_t err = i2s_driver_install(I2S_PORT, &i2s_config, 0, NULL);
  if (err != ESP_OK) { Serial.printf("Failed installing driver: %d\n", err); while (true); }
  err = i2s_set_pin(I2S_PORT, &pin_config);
  if (err != ESP_OK) { Serial.printf("Failed setting pin: %d\n", err); while (true); }
  delay(500);
  Serial.println("I2S Mic driver installed.");

  // --- Start TCP Server ---
  server.begin();
  Serial.printf("TCP Server started on port %d - waiting for Android client to connect...\n", TCP_PORT);
}

void loop() {
  // Check if a client has connected
  if (!client.connected()) {
    client = server.available();
    if (client) {
      Serial.println("Client connected to send Mic data!");
      Serial.print("Client IP: "); Serial.println(client.remoteIP());
      i2s_zero_dma_buffer(I2S_PORT); // Clear buffer on new connection
      client.setNoDelay(true); // Optional lower latency
    }
  }

  // If we have a connected client, read I2S data and send it
  if (client.connected()) {
    size_t bytes_read = 0;
    esp_err_t result = i2s_read(I2S_PORT, i2s_read_buffer, I2S_READ_BUFFER_SIZE, &bytes_read, 100 / portTICK_PERIOD_MS); // Wait up to 100ms

    if (result == ESP_OK && bytes_read > 0) {
      // Send the raw 32-bit audio data buffer to the client
      size_t bytes_sent = client.write((const uint8_t*)i2s_read_buffer, bytes_read);
      if (bytes_sent != bytes_read) {
         Serial.printf("Warning: Sent %d / %d bytes!\n", bytes_sent, bytes_read);
         client.stop(); // Assume error
      }
      // Serial.printf("Read %d bytes, Sent %d bytes\n", bytes_read, bytes_sent);
    } else if (result != ESP_OK && result != ESP_ERR_TIMEOUT) {
        Serial.printf("I2S Read Error: %d\n", result);
    }

    // Check if client is still connected
    if (!client.connected()) {
        Serial.println("Client disconnected.");
        // client.stop(); // Already stopped
    }
  } else {
     delay(10); // Wait briefly if no client
  }
}
