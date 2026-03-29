package com.cropstress;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

// We use ScheduledEvent because this is triggered by a Cloud Timer, not an API Gateway web request
public class CropStressAutomationHandler implements RequestHandler<ScheduledEvent, String> {

    private final DynamoDbClient dynamoDb;
    private final SnsClient snsClient;
    private final Gson gson;
    private final String TABLE_NAME = "CropProfiles";
    
    // YOUR EXACT SNS TOPIC ARN
    private final String SNS_TOPIC_ARN = "arn:aws:sns:ap-south-1:118690287430:CropStressAlerts";

    public CropStressAutomationHandler() {
        this.dynamoDb = DynamoDbClient.builder().region(Region.AP_SOUTH_1).build();
        this.snsClient = SnsClient.builder().region(Region.AP_SOUTH_1).build();
        this.gson = new Gson();
    }

    @Override
    public String handleRequest(ScheduledEvent event, Context context) {
        context.getLogger().log("Waking up! Starting daily crop stress analysis...\n");

        try {
            // 1. FETCH LIVE WEATHER FROM OPEN-METEO (Using Vellore/Karigiri Coordinates: Lat 12.98, Lon 79.13)
            URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=12.98&longitude=79.13&daily=temperature_2m_max,temperature_2m_min,rain_sum&timezone=auto&forecast_days=1");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            
            JsonObject weatherData = gson.fromJson(new InputStreamReader(conn.getInputStream()), JsonObject.class);
            JsonObject daily = weatherData.getAsJsonObject("daily");
            
            double tempMax = daily.getAsJsonArray("temperature_2m_max").get(0).getAsDouble();
            double tempMin = daily.getAsJsonArray("temperature_2m_min").get(0).getAsDouble();
            double rain = daily.getAsJsonArray("rain_sum").get(0).getAsDouble();
            
            context.getLogger().log(String.format("Today's Forecast - Max: %.1fC, Min: %.1fC, Rain: %.1fmm\n", tempMax, tempMin, rain));

            // 2. SCAN ENTIRE DATABASE FOR ALL FARMER CROPS
            ScanRequest scanReq = ScanRequest.builder().tableName(TABLE_NAME).build();
            ScanResponse scanRes = dynamoDb.scan(scanReq);

            // 3. RUN ML MODEL FOR EACH CROP
            for (Map<String, AttributeValue> item : scanRes.items()) {
                String userId = item.get("userId").s();
                String cropId = item.get("cropId").s();
                String cropType = item.get("cropType").s();
                LocalDate sowingDate = LocalDate.parse(item.get("sowingDate").s());
                
                long daysSinceSowing = ChronoUnit.DAYS.between(sowingDate, LocalDate.now());
                String growthStage = calculateGrowthStage(daysSinceSowing);

                double[] mlInput = buildMachineLearningArray(tempMax, tempMin, rain, daysSinceSowing, cropType, growthStage);
                int winningClassIndex = getArgMax(CropStressPredictor.score(mlInput));
                String stressLevel = mapPredictionToLabel(winningClassIndex);

                context.getLogger().log(String.format("Analyzed %s (%s): %s Stress\n", cropId, cropType, stressLevel));

                // 4. TRIGGER SNS ALERT IF STRESS IS HIGH
                if ("High".equals(stressLevel)) {
                    String alertMessage = String.format(
                        "⚠️ HIGH CROP STRESS ALERT ⚠️\n\n" +
                        "Field: %s\n" +
                        "Crop: %s (Day %d, %s Stage)\n\n" +
                        "Today's Weather Trigger:\n" +
                        "- Max Temp: %.1f°C\n" +
                        "- Rain: %.1f mm\n\n" +
                        "Action Required: Please check your field for emergency irrigation requirements.",
                        cropId, cropType, daysSinceSowing, growthStage, tempMax, rain
                    );

                    PublishRequest pubReq = PublishRequest.builder()
                            .topicArn(SNS_TOPIC_ARN)
                            .subject("Urgent: High Crop Stress Detected - " + cropId)
                            .message(alertMessage)
                            .build();
                    
                    snsClient.publish(pubReq);
                    context.getLogger().log("--> Alert Email Sent to Farmer!\n");
                }
            }
            return "Daily analysis complete.";

        } catch (Exception e) {
            context.getLogger().log("ERROR: " + e.getMessage());
            return "Failed to run daily analysis.";
        }
    }

    // --- HELPER METHODS (Reused from main API) ---
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