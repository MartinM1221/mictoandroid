#include <WiFi.h>
#include "driver/i2s.h"

// --- Wi-Fi Settings ---
const char* ssid = "//";         // Replace with your Wi-Fi network name
const char* password = "//"; // Replace with your Wi-Fi password

// --- Network Settings ---
#define TCP_PORT 8081 // Port for this TCP server (Android connects here to SEND)

WiFiServer server(TCP_PORT);
WiFiClient client;

// --- I2S Pin Configuration (for MAX98357A Speaker - OUTPUT) ---
#define I2S_SPEAKER_LRC_PIN    25 // WS/LRCK -> MAX98357A LRC
#define I2S_SPEAKER_BCLK_PIN   26 // SCK/BCLK -> MAX98357A BCK
#define I2S_SPEAKER_DIN_PIN    22 // SD/DOUT -> MAX98357A DIN

// --- I2S Settings ---
#define I2S_SPEAKER_PORT     I2S_NUM_0 // Use I2S Port 0 for output
#define I2S_SAMPLE_RATE      16000     // Must match sample rate of incoming data
// *** CRITICAL: Set bits per sample to match the DATA RECEIVED (Android sends 16-bit) ***
#define I2S_BITS_PER_SAMPLE  I2S_BITS_PER_SAMPLE_16BIT
#define I2S_CHANNEL_FORMAT   I2S_CHANNEL_FMT_RIGHT_LEFT // Standard Stereo/Mono format

// --- Buffer ---
#define NETWORK_RECEIVE_BUFFER_SIZE 1024 // Bytes to buffer network data
uint8_t network_receive_buffer[NETWORK_RECEIVE_BUFFER_SIZE];

// --- Function Prototypes ---
void setup_i2s_speaker();

// --- Setup ---
void setup() {
  Serial.begin(115200);
  Serial.println("\n\n--- ESP32 #2: Speaker Receiver Starting ---"); // Identify board

  // --- Connect to Wi-Fi ---
  Serial.print("Connecting to "); Serial.println(ssid);
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) { delay(500); Serial.print("."); }
  Serial.println("\nWiFi connected!");
  Serial.print("THIS ESP32 (#2 Speaker Recv) IP Address: "); // <<< NOTE THIS IP
  Serial.println(WiFi.localIP());                           // <<< NOTE THIS IP

  // --- Configure I2S Output (Speaker on I2S0) ---
  setup_i2s_speaker();

  // --- Start TCP Server ---
  server.begin();
  Serial.printf("TCP Server started on port %d - waiting for Android client to connect...\n", TCP_PORT);
}

// --- I2S Speaker Setup (I2S_NUM_0, TX) ---
void setup_i2s_speaker() {
  Serial.println("Configuring I2S Speaker Output (I2S_NUM_0)...");
   i2s_config_t i2s_speaker_config = {
    .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX),
    .sample_rate = I2S_SAMPLE_RATE,
    .bits_per_sample = I2S_BITS_PER_SAMPLE, // Configured for 16-bit
    .channel_format = I2S_CHANNEL_FORMAT,
    .communication_format = I2S_COMM_FORMAT_STAND_I2S,
    .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
    .dma_buf_count = 6, // Slightly more buffers for network jitter
    .dma_buf_len = 128, // samples per buffer
    .use_apll = true, // Use APLL
    .tx_desc_auto_clear = true,
    .fixed_mclk = 0
  };
   i2s_pin_config_t speaker_pin_config = {
    .bck_io_num = I2S_SPEAKER_BCLK_PIN,
    .ws_io_num = I2S_SPEAKER_LRC_PIN,
    .data_out_num = I2S_SPEAKER_DIN_PIN,
    .data_in_num = I2S_PIN_NO_CHANGE
  };

  esp_err_t err = i2s_driver_install(I2S_SPEAKER_PORT, &i2s_speaker_config, 0, NULL);
  if (err != ESP_OK) { Serial.printf("Failed to install I2S Speaker driver: %s\n", esp_err_to_name(err)); while(true); }
   err = i2s_set_pin(I2S_SPEAKER_PORT, &speaker_pin_config);
   if (err != ESP_OK) { Serial.printf("Failed to set I2S Speaker pins: %s\n", esp_err_to_name(err)); while(true); }
   err = i2s_zero_dma_buffer(I2S_SPEAKER_PORT);
   if (err != ESP_OK) { Serial.printf("Failed to zero Speaker DMA buffer: %s\n", esp_err_to_name(err)); }

  Serial.println("I2S Speaker driver installed.");
}

// --- Main Loop ---
void loop() {
  // Check if a client has connected
  if (!client.connected()) {
    client = server.available();
    if (client) {
      Serial.println("Client connected to receive Speaker data!");
      Serial.print("Client IP: "); Serial.println(client.remoteIP());
      i2s_zero_dma_buffer(I2S_SPEAKER_PORT); // Clear buffer
      client.setNoDelay(true); // Optional lower latency
    }
  }

  // If we have a connected client, read network data and send to I2S speaker
  if (client.connected()) {
    int availableBytes = client.available();
    if (availableBytes > 0) {
      // Read data directly into the receive buffer
      size_t bytesToRead = min((size_t)availableBytes, sizeof(network_receive_buffer));
      size_t bytesRead = client.read(network_receive_buffer, bytesToRead);

      if (bytesRead > 0) {
          // Check if data amount is valid (multiple of 16-bit samples = 2 bytes)
          if (bytesRead % 2 != 0) {
              Serial.printf("Warning: Received odd number of bytes (%d). Might be data corruption.\n", bytesRead);
              // Decide how to handle: discard, wait for more, process only even part?
              // For now, just proceed, i2s_write might handle partial frames poorly.
          }

          // Write the raw 16-bit received data directly to the speaker I2S port
          size_t bytes_written = 0;
          esp_err_t result = i2s_write(I2S_SPEAKER_PORT, network_receive_buffer, bytesRead, &bytes_written, portMAX_DELAY); // Wait if buffer full

          if (result != ESP_OK) {
              Serial.printf("I2S Write Error: %s\n", esp_err_to_name(result));
          }
          if (bytes_written != bytesRead) {
             Serial.printf("Warning: I2S Write issue. Wrote %d / %d bytes.\n", bytes_written, bytesRead);
          }
         // Serial.printf("Received %d bytes, Wrote %d bytes to I2S\n", bytesRead, bytes_written); // Uncomment for debugging
      }
    } else {
      // No data available right now
      delay(1); // Prevent tight loop
    }

    // Check if client is still connected
    if (!client.connected()) {
        Serial.println("Client disconnected.");
        // client.stop(); // Already stopped or handled by client object
    }
  } else {
     delay(10); // Wait briefly if no client
  }
}
