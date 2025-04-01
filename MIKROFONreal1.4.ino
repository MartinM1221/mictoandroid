#include <WiFi.h>
#include "driver/i2s.h"

// --- Wi-Fi Settings ---
const char* ssid = "HT_291847";         // Replace with your Wi-Fi network name
const char* password = "67960214089161444754"; // Replace with your Wi-Fi password

// --- I2S Pin Configuration ---
#define I2S_WS_PIN   15  // Word Select (L/R Clock)
#define I2S_SCK_PIN  14  // Serial Clock (Bit Clock)
#define I2S_SD_PIN   32  // Serial Data (Data Out from Mic)

// --- I2S Settings ---
#define I2S_PORT        I2S_NUM_0 // Use I2S Port 0
#define I2S_SAMPLE_RATE 16000     // Sample rate (Hz) - Adjust as needed
#define I2S_BITS_PER_SAMPLE I2S_BITS_PER_SAMPLE_32BIT // INMP441 outputs 32-bit samples (24 actual data bits)
#define I2S_READ_BUFFER_SIZE 1024 // Bytes to read at a time (must be multiple of bytes per sample * channels)
#define I2S_CHANNEL_FORMAT I2S_CHANNEL_FMT_ONLY_LEFT // L/R pin is GND, so mic acts as left channel

// --- Network Settings ---
#define TCP_PORT 8080 // Port for the TCP server

WiFiServer server(TCP_PORT);
WiFiClient client;

// Buffer to hold audio data read from I2S
int8_t i2s_read_buffer[I2S_READ_BUFFER_SIZE];

void setup() {
  Serial.begin(115200);
  Serial.println("INMP441 Audio Streamer Starting...");

  // --- Connect to Wi-Fi ---
  Serial.print("Connecting to ");
  Serial.println(ssid);
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("\nWiFi connected!");
  Serial.print("IP Address: ");
  Serial.println(WiFi.localIP());

  // --- Configure I2S ---
  Serial.println("Configuring I2S...");
  i2s_config_t i2s_config = {
      .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX), // Master mode, Receive data
      .sample_rate = I2S_SAMPLE_RATE,
      .bits_per_sample = I2S_BITS_PER_SAMPLE,
      .channel_format = I2S_CHANNEL_FORMAT,
      .communication_format = I2S_COMM_FORMAT_STAND_I2S, // Standard I2S protocol
      .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,        // Interrupt level 1
      .dma_buf_count = 4,                               // Number of DMA buffers
      .dma_buf_len = I2S_READ_BUFFER_SIZE / 4,          // Size of each DMA buffer in samples (bytes / (bits/8 * channels))
      .use_apll = false,                              // Don't use Audio PLL
      .tx_desc_auto_clear = false,
      .fixed_mclk = 0
  };

  i2s_pin_config_t pin_config = {
      .bck_io_num = I2S_SCK_PIN,   // Bit Clock
      .ws_io_num = I2S_WS_PIN,    // Word Select
      .data_out_num = I2S_PIN_NO_CHANGE, // Not used for microphone input
      .data_in_num = I2S_SD_PIN     // Serial Data In
  };

  // Install and start I2S driver
  esp_err_t err = i2s_driver_install(I2S_PORT, &i2s_config, 0, NULL);
  if (err != ESP_OK) {
    Serial.printf("Failed installing driver: %d\n", err);
    while (true);
  }

  err = i2s_set_pin(I2S_PORT, &pin_config);
  if (err != ESP_OK) {
    Serial.printf("Failed setting pin: %d\n", err);
    while (true);
  }

   // The INMP441 needs a clock signal *before* data starts. Add a small delay.
   // You might need to adjust clock settings if audio is noisy/distorted.
   // Consider using i2s_set_clk() for more control if needed.
   delay(500);


  Serial.println("I2S driver installed.");

  // --- Start TCP Server ---
  server.begin();
  Serial.printf("TCP Server started on port %d\n", TCP_PORT);
}

void loop() {
  // Check if a client has connected
  if (!client.connected()) {
    client = server.available(); // Listen for incoming clients
    if (client) {
      Serial.println("Client connected!");
      Serial.print("Client IP: ");
      Serial.println(client.remoteIP());
       // Clear any garbage data in the I2S buffer
       i2s_zero_dma_buffer(I2S_PORT);
    }
  }

  // If we have a connected client, read I2S data and send it
  if (client.connected()) {
    size_t bytes_read = 0;
    // Read data from I2S into the buffer
    esp_err_t result = i2s_read(I2S_PORT, i2s_read_buffer, I2S_READ_BUFFER_SIZE, &bytes_read, portMAX_DELAY); // Use portMAX_DELAY to wait until data is available

    if (result == ESP_OK && bytes_read > 0) {
      // Send the raw audio data buffer to the client
      size_t bytes_sent = client.write((const uint8_t*)i2s_read_buffer, bytes_read);
      if (bytes_sent != bytes_read) {
         Serial.println("Warning: Not all bytes were sent!");
         // Optional: Handle incomplete sends (e.g., retry, disconnect)
      }
      // Serial.printf("Read %d bytes, Sent %d bytes\n", bytes_read, bytes_sent); // Uncomment for debugging data flow
    } else if (result != ESP_OK) {
        Serial.printf("I2S Read Error: %d\n", result);
        // Optional: Handle read errors (e.g., restart I2S?)
    }

    // Check if client is still connected (write might fail if disconnected)
    if (!client.connected()) {
        Serial.println("Client disconnected.");
        client.stop();
    }

  } else {
    // Optional: Small delay when no client is connected to prevent busy-waiting
    delay(10);
  }
}