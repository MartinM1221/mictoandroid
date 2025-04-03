#include <WiFi.h>
#include "driver/i2s.h"
#include <math.h> // For sqrt in RMS calculation

// --- Wi-Fi Settings ---
const char* ssid = "HT_291847";         // Replace
const char* password = "67960214089161444754"; // Replace

// --- Network Settings ---
#define PORT_SEND_AUDIO    8080 // Port ESP32 sends INMP441 data FROM (DISABLED FOR TEST)
#define PORT_RECEIVE_AUDIO 8081 // Port ESP32 receives phone mic data ON

// --- I2S Pin Configuration (for INMP441 Microphone - INPUT) ---
// Pins defined but setup/use is commented out below
#define I2S_MIC_WS_PIN   15  // WS/LRCK
#define I2S_MIC_SCK_PIN  14  // SCK/BCLK
#define I2S_MIC_SD_PIN   32  // SD/DIN

// --- I2S Pin Configuration (for MAX98357A Speaker - OUTPUT) ---
// Using original pins 25, 26, 22 for this test
#define I2S_SPEAKER_LRC_PIN    25 // WS/LRCK (Connect to MAX98357A LRC)
#define I2S_SPEAKER_BCLK_PIN   26 // SCK/BCLK (Connect to MAX98357A BCK)
#define I2S_SPEAKER_DIN_PIN    22 // SD/DOUT (Connect to MAX98357A DIN)

// --- I2S Settings (Common - Assuming Phone Sends 16kHz, 16-bit) ---
#define I2S_SAMPLE_RATE         16000
#define I2S_BITS_PER_SAMPLE_OUT I2S_BITS_PER_SAMPLE_16BIT // Speaker expects 16-bit
#define I2S_BITS_PER_SAMPLE_IN  I2S_BITS_PER_SAMPLE_32BIT // INMP441 is 32-bit (NOT USED IN TEST)

// --- I2S Ports ---
#define I2S_MIC_PORT     I2S_NUM_0 // (DISABLED FOR TEST)
#define I2S_SPEAKER_PORT I2S_NUM_1 // Use I2S1 for Speaker Output

// --- Buffers ---
#define I2S_READ_BUFFER_SIZE    1024 // (NOT USED IN TEST)
#define NETWORK_RECEIVE_BUFFER_SIZE 1024 // Buffer for incoming phone audio

// int8_t i2sReadBuffer[I2S_READ_BUFFER_SIZE];           // (NOT USED IN TEST)
uint8_t networkReceiveBuffer[NETWORK_RECEIVE_BUFFER_SIZE]; // For receiving phone data AND sending to speaker

// --- Server and Client Objects ---
// WiFiServer serverSend(PORT_SEND_AUDIO); // (DISABLED FOR TEST)
WiFiServer serverReceive(PORT_RECEIVE_AUDIO);
// WiFiClient clientSend;    // (DISABLED FOR TEST)
WiFiClient clientReceive; // Client ESP32 receives data FROM (Android App)

// --- Global State ---
bool speaker_i2s_installed = false; // <<< ADDED FLAG

// --- Function Prototypes ---
// void setup_i2s_mic(); // (DISABLED FOR TEST)
void setup_i2s_speaker();
float calculateRMS(int16_t* audioData, size_t numSamples);
// void handleSendingClient(); // (DISABLED FOR TEST)
void handleReceivingClient();

// --- Setup Function ---
void setup() {
  Serial.begin(115200);
  Serial.println("\n\n--- ESP32 Bi-Directional Audio (SPEAKER ONLY TEST) ---"); // Modified title

  // --- Connect to Wi-Fi ---
  Serial.print("Connecting to "); Serial.println(ssid);
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) { delay(500); Serial.print("."); }
  Serial.println("\nWiFi connected!");
  Serial.print("ESP32 IP Address: "); Serial.println(WiFi.localIP());

  // --- Configure I2S Input (Microphone on I2S0) ---
  // setup_i2s_mic(); // <<<<------ COMMENTED OUT FOR TEST

  // --- Configure I2S Output (Speaker on I2S1) ---
  setup_i2s_speaker(); // Keep this active!

  // --- Start Receiver TCP Server ---
  // serverSend.begin(); // <<<<------ COMMENTED OUT FOR TEST
  // Serial.printf("TCP Server for Sending started on port %d\n", PORT_SEND_AUDIO); // <<<<------ COMMENTED OUT FOR TEST
  serverReceive.begin();
  Serial.printf("TCP Server for Receiving started on port %d\n", PORT_RECEIVE_AUDIO);
  Serial.println("Waiting for receiving client..."); // Modified message
}

/* // <<<<------ START COMMENT BLOCK FOR MIC SETUP
// --- I2S Mic Setup (I2S_NUM_0, RX) ---
void setup_i2s_mic() {
 // ... (Mic setup code remains commented out) ...
}
*/ // <<<<------ END COMMENT BLOCK FOR MIC SETUP


// --- I2S Speaker Setup (I2S_NUM_1, TX) ---
void setup_i2s_speaker() {
  Serial.println("Configuring I2S Speaker Output (I2S_NUM_1)...");
  // ... (i2s_speaker_config definition remains the same) ...
   i2s_config_t i2s_speaker_config = {
    .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX), // TX Mode
    .sample_rate = I2S_SAMPLE_RATE,                     // Use the same rate as received audio
    .bits_per_sample = I2S_BITS_PER_SAMPLE_OUT,         // Speaker expects 16-bit
    .channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT,       // Standard Stereo format for MAX98357A
    .communication_format = I2S_COMM_FORMAT_STAND_I2S,
    .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
    .dma_buf_count = 4,
    .dma_buf_len = NETWORK_RECEIVE_BUFFER_SIZE / 4, // In samples = 1024 / (16/8 * 2) = 256
    .use_apll = false,
    .tx_desc_auto_clear = true,
    .fixed_mclk = 0
  };
   i2s_pin_config_t speaker_pin_config = {
    .bck_io_num = I2S_SPEAKER_BCLK_PIN,
    .ws_io_num = I2S_SPEAKER_LRC_PIN,
    .data_out_num = I2S_SPEAKER_DIN_PIN,
    .data_in_num = I2S_PIN_NO_CHANGE // No RX for speaker
  };

  esp_err_t err = i2s_driver_install(I2S_SPEAKER_PORT, &i2s_speaker_config, 0, NULL);
  if (err != ESP_OK) {
      Serial.printf("Failed to install I2S Speaker driver: %s\n", esp_err_to_name(err));
      // Do NOT set the flag if install fails
      speaker_i2s_installed = false; // Explicitly set false on failure
      while(true); // Halt on error
  }

   err = i2s_set_pin(I2S_SPEAKER_PORT, &speaker_pin_config);
   if (err != ESP_OK) {
       Serial.printf("Failed to set I2S Speaker pins: %s\n", esp_err_to_name(err));
       // Do NOT set the flag if pin setting fails
       speaker_i2s_installed = false; // Explicitly set false on failure
       while(true); // Halt on error <<< WATCH THIS LINE!
   }

   // Ensure DMA buffer is clear for speaker output - only if previous steps succeeded
   err = i2s_zero_dma_buffer(I2S_SPEAKER_PORT);
   if (err != ESP_OK) {
       Serial.printf("Failed to zero Speaker DMA buffer: %s\n", esp_err_to_name(err));
       // Proceeding even if zero fails might be okay, but good to note.
   }

  Serial.println("I2S Speaker driver installed.");
  speaker_i2s_installed = true; // <<< SET FLAG TO TRUE ON SUCCESS
}


// --- Main Loop ---
void loop() {
  // --- Task 1: Handle Sending Audio (INMP441 -> Android) ---
  // handleSendingClient(); // <<<<------ COMMENTED OUT FOR TEST

  // --- Task 2: Handle Receiving Audio (Android Mic -> ESP32 Speaker) ---
  handleReceivingClient(); // Keep this active!

  delay(1); // Small delay to prevent watchdog timer issues
}

/* // <<<<------ START COMMENT BLOCK FOR SENDING CLIENT HANDLER
// --- Function to Handle Sending Client (Port: PORT_SEND_AUDIO) ---
void handleSendingClient() {
 // ... (Sending client handler code remains commented out) ...
}
*/ // <<<<------ END COMMENT BLOCK FOR SENDING CLIENT HANDLER


// --- Function to Handle Receiving Client (Port: PORT_RECEIVE_AUDIO) ---
void handleReceivingClient() {
  // Check for new client connection
  if (!clientReceive.connected()) {
      WiFiClient newClient = serverReceive.available();
      if (newClient) {
          Serial.printf("[Receive Server %d] Client connected: %s\n", PORT_RECEIVE_AUDIO, newClient.remoteIP().toString().c_str());
          if(clientReceive) { clientReceive.stop(); } // Stop old client if any
          clientReceive = newClient;
          // Ensure speaker buffer is cleared IF speaker setup was successful
          if (speaker_i2s_installed) { // <<< CHECK FLAG
             i2s_zero_dma_buffer(I2S_SPEAKER_PORT); // Clear Speaker I2S buffer on new connection
          }
      }
  }

  // If client is connected, check for and process data
  // Only proceed if the speaker driver actually installed correctly
  if (clientReceive.connected() && speaker_i2s_installed) { // <<< CHECK FLAG
    int availableBytes = clientReceive.available();
    if (availableBytes > 0) {
      // Determine how much data to read (up to buffer size)
      size_t bytesToRead = min((size_t)availableBytes, (size_t)NETWORK_RECEIVE_BUFFER_SIZE);

      // Read data from network into the buffer
      size_t bytesRead = clientReceive.read(networkReceiveBuffer, bytesToRead);

      if (bytesRead > 0) {
        // --- PLAY AUDIO ---
        // Write the received data directly to the Speaker's I2S (I2S_NUM_1)
        size_t bytes_written_to_speaker = 0;
        esp_err_t write_result = i2s_write(I2S_SPEAKER_PORT, networkReceiveBuffer, bytesRead, &bytes_written_to_speaker, portMAX_DELAY);

        if (write_result != ESP_OK) {
            Serial.printf("[Receive Server %d] I2S Speaker Write Error: %s\n", PORT_RECEIVE_AUDIO, esp_err_to_name(write_result));
        }
        if (bytes_written_to_speaker != bytesRead) {
             Serial.printf("[Receive Server %d] Warning: Wrote %d/%d bytes to speaker\n", PORT_RECEIVE_AUDIO, bytes_written_to_speaker, bytesRead);
        }

        // --- Optional: Calculate and print RMS (using the received 16-bit data) ---
        size_t numSamples = bytesRead / (I2S_BITS_PER_SAMPLE_OUT / 8); // Divide by bytes per sample (16/8=2)
        if (numSamples > 0) {
          float rms = calculateRMS((int16_t*)networkReceiveBuffer, numSamples);
          // Only print RMS occasionally to avoid flooding Serial
          static unsigned long lastRmsPrint = 0;
          if (millis() - lastRmsPrint > 500) { // Print RMS every 500ms
               Serial.printf("[Receive Server %d] Received %d bytes, Played %d bytes, RMS: %.2f\n", PORT_RECEIVE_AUDIO, bytesRead, bytes_written_to_speaker, rms);
               lastRmsPrint = millis();
          }
        }
        // --- End Optional RMS ---
      }
    }

    // Check if client disconnected
    if (!clientReceive.connected()) {
      Serial.printf("[Receive Server %d] Client disconnected.\n", PORT_RECEIVE_AUDIO);
      clientReceive.stop();
    }
  } else if (clientReceive.connected() && !speaker_i2s_installed) { // <<< CHECK FLAG
      // Handle case where client is connected but speaker isn't ready
       Serial.println("[Receive Server] Warning: Client connected but speaker I2S failed setup. Ignoring data.");
       // Read and discard data to prevent buffer buildup on client side (optional)
       while (clientReceive.available()) {
           clientReceive.read();
       }
      // clientReceive.stop(); // Alternative: disconnect client if speaker failed
  }
}


// --- Helper Function - Calculate RMS --- (Function remains unchanged)
// Assumes input data is 16-bit PCM signed
float calculateRMS(int16_t* audioData, size_t numSamples) {
  if (numSamples == 0) return 0.0;
  double sumSquare = 0.0;
  for (size_t i = 0; i < numSamples; i++) {
    double sample = (double)audioData[i];
    sumSquare += sample * sample;
  }
  return sqrt(sumSquare / numSamples);
}