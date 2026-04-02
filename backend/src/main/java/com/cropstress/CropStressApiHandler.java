package com.cropstress;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

public class CropStressApiHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final DynamoDbClient dynamoDb;
    private final Gson gson;
    private final String TABLE_NAME = "CropProfiles";

    public CropStressApiHandler() {
        this.dynamoDb = DynamoDbClient.builder().region(Region.AP_SOUTH_1).build();
        this.gson = new Gson();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");

        try {
            // Handle CORS Preflight automatically
            if ("OPTIONS".equals(request.getHttpMethod())) {
                return new APIGatewayProxyResponseEvent().withStatusCode(200).withHeaders(headers);
            }

            // 1. SECURE IDENTITY EXTRACTION
            Map<String, Object> authorizer = request.getRequestContext().getAuthorizer();
            if (authorizer == null || !authorizer.containsKey("claims")) {
                return new APIGatewayProxyResponseEvent().withStatusCode(401).withHeaders(headers).withBody("{\"error\": \"Unauthorized\"}");
            }
            Map<String, String> claims = (Map<String, String>) authorizer.get("claims");
            String secureUserId = claims.get("sub"); 
            String userEmail = claims.get("email"); 

            String path = request.getPath();
            String httpMethod = request.getHttpMethod();

            // 2. THE ROUTER (Expanded for full CRUD)
            if ("/predict".equals(path) && "POST".equals(httpMethod)) {
                return handlePredict(request, secureUserId, headers);
            } else if ("/crops".equals(path) && "GET".equals(httpMethod)) {
                return handleGetCrops(secureUserId, headers);
            } else if ("/crops".equals(path) && "POST".equals(httpMethod)) {
                return handleAddCrop(request, secureUserId, userEmail, headers, context);
            } else if ("/crops".equals(path) && "DELETE".equals(httpMethod)) {
                return handleDeleteCrop(request, secureUserId, headers);
            } else if ("/crops".equals(path) && "PUT".equals(httpMethod)) {
                return handleUpdateCropStatus(request, secureUserId, headers);
            }

            return new APIGatewayProxyResponseEvent().withStatusCode(404).withHeaders(headers).withBody("{\"error\": \"Route not found\"}");

        } catch (Exception e) {
            context.getLogger().log("ERROR: " + e.getMessage());
            return new APIGatewayProxyResponseEvent().withStatusCode(500).withHeaders(headers).withBody("{\"error\": \"Server Error\"}");
        }
    }

    // --- ROUTE: ADD NEW CROP (With Backfill & Duplicate Protection) ---
    private APIGatewayProxyResponseEvent handleAddCrop(APIGatewayProxyRequestEvent request, String userId, String userEmail, Map<String, String> headers, Context context) {
        JsonObject body = gson.fromJson(request.getBody(), JsonObject.class);
        String cropId = body.get("cropId").getAsString();
        String cropType = body.get("cropType").getAsString();
        String sowingDateStr = body.get("sowingDate").getAsString();
        
        LocalDate sowingDate = LocalDate.parse(sowingDateStr);
        long daysPast = ChronoUnit.DAYS.between(sowingDate, LocalDate.now());
        JsonArray historyArray = new JsonArray();

        // RETROACTIVE BACKFILL LOGIC
        if (daysPast > 0) {
            long apiPastDays = Math.min(daysPast, 90); // Open-Meteo free tier limit
            try {
                URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=12.98&longitude=79.13&daily=temperature_2m_max,temperature_2m_min,rain_sum&timezone=auto&past_days=" + apiPastDays + "&forecast_days=0");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                
                JsonObject weatherData = gson.fromJson(new InputStreamReader(conn.getInputStream()), JsonObject.class);
                JsonObject daily = weatherData.getAsJsonObject("daily");
                JsonArray times = daily.getAsJsonArray("time");
                JsonArray tempMaxs = daily.getAsJsonArray("temperature_2m_max");
                JsonArray tempMins = daily.getAsJsonArray("temperature_2m_min");
                JsonArray rains = daily.getAsJsonArray("rain_sum");

                // Loop through history and run the ML model for each past day
                for (int i = 0; i < times.size(); i++) {
                    LocalDate recordDate = LocalDate.parse(times.get(i).getAsString());
                    if (recordDate.isBefore(sowingDate) || recordDate.isEqual(LocalDate.now()) || recordDate.isAfter(LocalDate.now())) continue;

                    long daysSinceSowing = ChronoUnit.DAYS.between(sowingDate, recordDate);
                    String stage = calculateGrowthStage(daysSinceSowing);
                    
                    double tMax = tempMaxs.get(i).isJsonNull() ? 25.0 : tempMaxs.get(i).getAsDouble();
                    double tMin = tempMins.get(i).isJsonNull() ? 20.0 : tempMins.get(i).getAsDouble();
                    double rain = rains.get(i).isJsonNull() ? 0.0 : rains.get(i).getAsDouble();

                    double[] mlInput = buildMachineLearningArray(tMax, tMin, rain, daysSinceSowing, cropType, stage);
                    int winningClassIndex = getArgMax(CropStressPredictor.score(mlInput));
                    
                    JsonObject historyRecord = new JsonObject();
                    historyRecord.addProperty("date", recordDate.toString());
                    historyRecord.addProperty("stress", mapPredictionToLabel(winningClassIndex));
                    historyArray.add(historyRecord);
                }
                context.getLogger().log("Successfully backfilled " + historyArray.size() + " days of historical data.");
            } catch(Exception e) {
                context.getLogger().log("Warning: Could not fetch historical weather for backfill: " + e.getMessage());
            }
        }

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("userId", AttributeValue.builder().s(userId).build());
        item.put("farmerEmail", AttributeValue.builder().s(userEmail).build());
        item.put("cropId", AttributeValue.builder().s(cropId).build());
        item.put("cropType", AttributeValue.builder().s(cropType).build());
        item.put("sowingDate", AttributeValue.builder().s(sowingDateStr).build());
        item.put("status", AttributeValue.builder().s("ACTIVE").build()); // Default status
        item.put("stressHistory", AttributeValue.builder().s(gson.toJson(historyArray)).build()); // Save the generated history

        try {
            // Duplicate Protection: Fail if cropId already exists for this user
            PutItemRequest putReq = PutItemRequest.builder()
                .tableName(TABLE_NAME)
                .item(item)
                .conditionExpression("attribute_not_exists(cropId)")
                .build();
            dynamoDb.putItem(putReq);
            return new APIGatewayProxyResponseEvent().withStatusCode(200).withHeaders(headers).withBody("{\"message\": \"Crop saved successfully\"}");
        } catch (ConditionalCheckFailedException e) {
            return new APIGatewayProxyResponseEvent().withStatusCode(400).withHeaders(headers).withBody("{\"error\": \"A field with this name already exists.\"}");
        }
    }

    // --- ROUTE: GET ALL CROPS ---
    private APIGatewayProxyResponseEvent handleGetCrops(String userId, Map<String, String> headers) {
        QueryRequest queryReq = QueryRequest.builder()
                .tableName(TABLE_NAME)
                .keyConditionExpression("userId = :v_userId")
                .expressionAttributeValues(Map.of(":v_userId", AttributeValue.builder().s(userId).build()))
                .build();

        QueryResponse response = dynamoDb.query(queryReq);
        JsonArray cropsArray = new JsonArray();
        
        for (Map<String, AttributeValue> item : response.items()) {
            JsonObject crop = new JsonObject();
            crop.addProperty("cropId", item.get("cropId").s());
            crop.addProperty("cropType", item.get("cropType").s());
            crop.addProperty("sowingDate", item.get("sowingDate").s());
            crop.addProperty("status", item.containsKey("status") ? item.get("status").s() : "ACTIVE");
            crop.addProperty("stressHistory", item.containsKey("stressHistory") ? item.get("stressHistory").s() : "[]");
            cropsArray.add(crop);
        }

        return new APIGatewayProxyResponseEvent().withStatusCode(200).withHeaders(headers).withBody(gson.toJson(cropsArray));
    }

    // --- ROUTE: DELETE CROP ---
    private APIGatewayProxyResponseEvent handleDeleteCrop(APIGatewayProxyRequestEvent request, String userId, Map<String, String> headers) {
        if (request.getQueryStringParameters() == null || !request.getQueryStringParameters().containsKey("cropId")) {
            return new APIGatewayProxyResponseEvent().withStatusCode(400).withHeaders(headers).withBody("{\"error\": \"Missing cropId\"}");
        }
        String cropId = request.getQueryStringParameters().get("cropId");

        Map<String, AttributeValue> key = new HashMap<>();
        key.put("userId", AttributeValue.builder().s(userId).build());
        key.put("cropId", AttributeValue.builder().s(cropId).build());

        dynamoDb.deleteItem(DeleteItemRequest.builder().tableName(TABLE_NAME).key(key).build());
        return new APIGatewayProxyResponseEvent().withStatusCode(200).withHeaders(headers).withBody("{\"message\": \"Field deleted successfully\"}");
    }

    // --- ROUTE: UPDATE STATUS (Archive/Harvest) ---
    private APIGatewayProxyResponseEvent handleUpdateCropStatus(APIGatewayProxyRequestEvent request, String userId, Map<String, String> headers) {
        JsonObject body = gson.fromJson(request.getBody(), JsonObject.class);
        String oldCropId = body.get("cropId").getAsString();
        String status = body.get("status").getAsString(); // Expecting "HARVESTED"

        Map<String, AttributeValue> key = new HashMap<>();
        key.put("userId", AttributeValue.builder().s(userId).build());
        key.put("cropId", AttributeValue.builder().s(oldCropId).build());

        if ("HARVESTED".equals(status)) {
            // 1. Fetch the existing active crop data
            Map<String, AttributeValue> existingItem = dynamoDb.getItem(GetItemRequest.builder().tableName(TABLE_NAME).key(key).build()).item();
            
            if (existingItem != null && !existingItem.isEmpty()) {
                // 2. Create a new archived name
                String archivedCropId = oldCropId + " (Harvested " + LocalDate.now().toString() + ")";
                
                // 3. Build the new archived item (copying all old history)
                Map<String, AttributeValue> archivedItem = new HashMap<>(existingItem);
                archivedItem.put("cropId", AttributeValue.builder().s(archivedCropId).build());
                archivedItem.put("status", AttributeValue.builder().s("HARVESTED").build());
                
                // 4. Save the new archived item to DynamoDB
                dynamoDb.putItem(PutItemRequest.builder().tableName(TABLE_NAME).item(archivedItem).build());
                
                // 5. Delete the old active item so the original name is freed up!
                dynamoDb.deleteItem(DeleteItemRequest.builder().tableName(TABLE_NAME).key(key).build());
                
                return new APIGatewayProxyResponseEvent().withStatusCode(200).withHeaders(headers).withBody("{\"message\": \"Field archived successfully\"}");
            }
        }

        return new APIGatewayProxyResponseEvent().withStatusCode(400).withHeaders(headers).withBody("{\"error\": \"Could not archive field.\"}");
    }

    // --- ROUTE: RUN ML PREDICTION ---
    private APIGatewayProxyResponseEvent handlePredict(APIGatewayProxyRequestEvent request, String userId, Map<String, String> headers) {
        JsonObject body = gson.fromJson(request.getBody(), JsonObject.class);
        String cropId = body.get("cropId").getAsString();
        double tempMax = body.get("temperature_2m_max").getAsDouble();
        double tempMin = body.has("temperature_2m_min") ? body.get("temperature_2m_min").getAsDouble() : 25.0;
        double rain = body.get("rain").getAsDouble();

        Map<String, AttributeValue> key = new HashMap<>();
        key.put("userId", AttributeValue.builder().s(userId).build());
        key.put("cropId", AttributeValue.builder().s(cropId).build());

        Map<String, AttributeValue> item = dynamoDb.getItem(GetItemRequest.builder().tableName(TABLE_NAME).key(key).build()).item();
        if (item == null || item.isEmpty()) return new APIGatewayProxyResponseEvent().withStatusCode(404).withHeaders(headers).withBody("{\"error\": \"Crop not found\"}");

        String cropType = item.get("cropType").s();
        LocalDate sowingDate = LocalDate.parse(item.get("sowingDate").s());
        long daysSinceSowing = ChronoUnit.DAYS.between(sowingDate, LocalDate.now());
        String growthStage = calculateGrowthStage(daysSinceSowing);

        // THE HEURISTIC GUARDRAILS (Intercept extreme conditions before ML)
        // 1. Temperature Guardrail (Frost or Extreme Heat)
        if (tempMax < 10.0 || tempMax > 47.0) {
            JsonObject responseBody = new JsonObject();
            responseBody.addProperty("stress_level", "High"); 
            responseBody.addProperty("growth_stage", growthStage);
            responseBody.addProperty("days_since_sowing", daysSinceSowing);
            responseBody.addProperty("note", "Triggered by extreme temperature guardrail.");
            return new APIGatewayProxyResponseEvent().withStatusCode(200).withHeaders(headers).withBody(gson.toJson(responseBody));
        }

        // 2. Rainfall Guardrail (Flood / Waterlogging Risk)
        if (rain > 150.0) {
            JsonObject responseBody = new JsonObject();
            responseBody.addProperty("stress_level", "High"); 
            responseBody.addProperty("growth_stage", growthStage);
            responseBody.addProperty("days_since_sowing", daysSinceSowing);
            responseBody.addProperty("note", "Triggered by extreme rainfall guardrail (Flood Risk).");
            return new APIGatewayProxyResponseEvent().withStatusCode(200).withHeaders(headers).withBody(gson.toJson(responseBody));
        }

        double[] mlInput = buildMachineLearningArray(tempMax, tempMin, rain, daysSinceSowing, cropType, growthStage);
        int winningClassIndex = getArgMax(CropStressPredictor.score(mlInput));
        
        JsonObject responseBody = new JsonObject();
        responseBody.addProperty("stress_level", mapPredictionToLabel(winningClassIndex));
        responseBody.addProperty("growth_stage", growthStage);
        responseBody.addProperty("days_since_sowing", daysSinceSowing);

        return new APIGatewayProxyResponseEvent().withStatusCode(200).withHeaders(headers).withBody(gson.toJson(responseBody));
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