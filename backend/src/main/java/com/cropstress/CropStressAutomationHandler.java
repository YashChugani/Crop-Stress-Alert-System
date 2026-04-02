package com.cropstress;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.*;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

public class CropStressAutomationHandler implements RequestHandler<ScheduledEvent, String> {

    private final DynamoDbClient dynamoDb;
    private final SesClient sesClient;
    private final Gson gson;
    private final String TABLE_NAME = "CropProfiles";
    
    private final String SENDER_EMAIL = "yashchugani494@gmail.com"; 

    public CropStressAutomationHandler() {
        this.dynamoDb = DynamoDbClient.builder().region(Region.AP_SOUTH_1).build();
        this.sesClient = SesClient.builder().region(Region.AP_SOUTH_1).build();
        this.gson = new Gson();
    }

    @Override
    public String handleRequest(ScheduledEvent event, Context context) {
        context.getLogger().log("Waking up! Starting daily crop stress analysis...\n");

        try {
            // 1. FETCH LIVE WEATHER
            URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=12.98&longitude=79.13&daily=temperature_2m_max,temperature_2m_min,rain_sum&timezone=auto&forecast_days=1");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            
            JsonObject weatherData = gson.fromJson(new InputStreamReader(conn.getInputStream()), JsonObject.class);
            JsonObject daily = weatherData.getAsJsonObject("daily");
            
            double tempMax = daily.getAsJsonArray("temperature_2m_max").get(0).getAsDouble();
            double tempMin = daily.getAsJsonArray("temperature_2m_min").get(0).getAsDouble();
            double rain = daily.getAsJsonArray("rain_sum").get(0).getAsDouble();

            // 2. SCAN DATABASE
            ScanRequest scanReq = ScanRequest.builder().tableName(TABLE_NAME).build();
            ScanResponse scanRes = dynamoDb.scan(scanReq);

            // 3. EVALUATE & EMAIL SPECIFIC USERS
            for (Map<String, AttributeValue> item : scanRes.items()) {
                String cropId = item.get("cropId").s();
                String cropType = item.get("cropType").s();
                LocalDate sowingDate = LocalDate.parse(item.get("sowingDate").s());
                
                // Skip legacy database items safely
                if (!item.containsKey("farmerEmail")) continue;
                String recipientEmail = item.get("farmerEmail").s();
                
                // NEW: Skip crops that are already harvested!
                if (item.containsKey("status") && "HARVESTED".equals(item.get("status").s())) {
                    continue; 
                }
                
                long daysSinceSowing = ChronoUnit.DAYS.between(sowingDate, LocalDate.now());
                String growthStage = calculateGrowthStage(daysSinceSowing);

                double[] mlInput = buildMachineLearningArray(tempMax, tempMin, rain, daysSinceSowing, cropType, growthStage);
                int winningClassIndex = getArgMax(CropStressPredictor.score(mlInput));
                String stressLevel = mapPredictionToLabel(winningClassIndex);

                // NEW: Update DynamoDB with today's historical record
                try {
                    String historyStr = item.containsKey("stressHistory") ? item.get("stressHistory").s() : "[]";
                    JsonArray historyArray = gson.fromJson(historyStr, JsonArray.class);
                    
                    JsonObject todayRecord = new JsonObject();
                    todayRecord.addProperty("date", LocalDate.now().toString());
                    todayRecord.addProperty("stress", stressLevel);
                    historyArray.add(todayRecord);

                    Map<String, AttributeValue> key = new HashMap<>();
                    key.put("userId", item.get("userId"));
                    key.put("cropId", item.get("cropId"));

                    Map<String, AttributeValueUpdate> updates = new HashMap<>();
                    updates.put("stressHistory", AttributeValueUpdate.builder()
                            .value(AttributeValue.builder().s(gson.toJson(historyArray)).build())
                            .action(AttributeAction.PUT)
                            .build());

                    dynamoDb.updateItem(UpdateItemRequest.builder()
                            .tableName(TABLE_NAME)
                            .key(key)
                            .attributeUpdates(updates)
                            .build());
                    context.getLogger().log("Updated history for " + cropId + "\n");
                } catch (Exception e) {
                    context.getLogger().log("Warning: Failed to update history for " + cropId + ": " + e.getMessage());
                }

                if ("High".equals(stressLevel)) {
                    String subject = "⚠️ Urgent: High Crop Stress Detected - " + cropId;
                    String bodyText = String.format(
                        "Dear Farmer,\n\n" +
                        "Our Cloud AI has detected HIGH stress conditions for your field: %s.\n\n" +
                        "Crop: %s (Day %d, %s Stage)\n\n" +
                        "Today's Weather Trigger:\n" +
                        "- Max Temp: %.1f°C\n" +
                        "- Rain: %.1f mm\n\n" +
                        "Please check your field for emergency irrigation or protective measures.",
                        cropId, cropType, daysSinceSowing, growthStage, tempMax, rain
                    );

                    sendPersonalizedEmail(recipientEmail, subject, bodyText, context);
                }
            }
            return "Daily analysis complete.";

        } catch (Exception e) {
            context.getLogger().log("ERROR: " + e.getMessage());
            return "Failed to run daily analysis.";
        }
    }

    private void sendPersonalizedEmail(String recipientEmail, String subject, String bodyText, Context context) {
        try {
            SendEmailRequest request = SendEmailRequest.builder()
                .source(SENDER_EMAIL)
                .destination(Destination.builder().toAddresses(recipientEmail).build())
                .message(Message.builder()
                    .subject(Content.builder().data(subject).build())
                    .body(Body.builder().text(Content.builder().data(bodyText).build()).build())
                    .build()
                ).build();

            sesClient.sendEmail(request);
            context.getLogger().log("--> Secure Personalized Email Sent to: " + recipientEmail + "\n");
        } catch (Exception e) {
            context.getLogger().log("Failed to send email to " + recipientEmail + " (Are they verified in SES Sandbox?): " + e.getMessage() + "\n");
        }
    }

    // --- HELPER METHODS ---
    private String calculateGrowthStage(long days) {
        if (days < 30) return "Vegetative";
        if (days < 75) return "Flowering";
        return "Maturity";
    }

    private double[] buildMachineLearningArray(double tMax, double tMin, double rain, long days, String cropType, String stage) {
        double[] features = new double[10];
        features[0] = tMax; features[1] = tMin; features[2] = rain; features[3] = (double) days;
        features[4] = cropType.equalsIgnoreCase("Maize") ? 1.0 : 0.0;
        features[5] = cropType.equalsIgnoreCase("Rice") ? 1.0 : 0.0;
        features[6] = cropType.equalsIgnoreCase("Wheat") ? 1.0 : 0.0;
        features[7] = stage.equalsIgnoreCase("Flowering") ? 1.0 : 0.0;
        features[8] = stage.equalsIgnoreCase("Maturity") ? 1.0 : 0.0;
        features[9] = stage.equalsIgnoreCase("Vegetative") ? 1.0 : 0.0;
        return features;
    }

    private int getArgMax(double[] array) {
        int bestIdx = 0; double max = array[0];
        for (int i = 1; i < array.length; i++) { if (array[i] > max) { max = array[i]; bestIdx = i; } }
        return bestIdx;
    }

    private String mapPredictionToLabel(int winningIndex) {
        if (winningIndex == 0) return "High";
        if (winningIndex == 1) return "Low";
        return "Medium";
    }
}