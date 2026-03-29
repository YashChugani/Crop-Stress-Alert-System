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

        try {
            // 1. SECURE IDENTITY EXTRACTION: Get true user ID from Cognito JWT
            Map<String, Object> authorizer = request.getRequestContext().getAuthorizer();
            if (authorizer == null || !authorizer.containsKey("claims")) {
                return new APIGatewayProxyResponseEvent().withStatusCode(401).withHeaders(headers).withBody("{\"error\": \"Unauthorized\"}");
            }
            Map<String, String> claims = (Map<String, String>) authorizer.get("claims");
            String secureUserId = claims.get("sub"); // 'sub' is the unique Cognito User ID

            String path = request.getPath();
            String httpMethod = request.getHttpMethod();

            // 2. THE ROUTER
            if ("/predict".equals(path) && "POST".equals(httpMethod)) {
                return handlePredict(request, secureUserId, headers);
            } else if ("/crops".equals(path) && "GET".equals(httpMethod)) {
                return handleGetCrops(secureUserId, headers);
            } else if ("/crops".equals(path) && "POST".equals(httpMethod)) {
                return handleAddCrop(request, secureUserId, headers);
            }

            return new APIGatewayProxyResponseEvent().withStatusCode(404).withHeaders(headers).withBody("{\"error\": \"Route not found\"}");

        } catch (Exception e) {
            context.getLogger().log("ERROR: " + e.getMessage());
            return new APIGatewayProxyResponseEvent().withStatusCode(500).withHeaders(headers).withBody("{\"error\": \"Server Error\"}");
        }
    }

    // --- ROUTE: ADD NEW CROP ---
    private APIGatewayProxyResponseEvent handleAddCrop(APIGatewayProxyRequestEvent request, String userId, Map<String, String> headers) {
        JsonObject body = gson.fromJson(request.getBody(), JsonObject.class);
        
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("userId", AttributeValue.builder().s(userId).build());
        item.put("cropId", AttributeValue.builder().s(body.get("cropId").getAsString()).build());
        item.put("cropType", AttributeValue.builder().s(body.get("cropType").getAsString()).build());
        item.put("sowingDate", AttributeValue.builder().s(body.get("sowingDate").getAsString()).build());

        PutItemRequest putReq = PutItemRequest.builder().tableName(TABLE_NAME).item(item).build();
        dynamoDb.putItem(putReq);

        return new APIGatewayProxyResponseEvent().withStatusCode(200).withHeaders(headers).withBody("{\"message\": \"Crop saved successfully\"}");
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
            cropsArray.add(crop);
        }

        return new APIGatewayProxyResponseEvent().withStatusCode(200).withHeaders(headers).withBody(gson.toJson(cropsArray));
    }

    // --- ROUTE: RUN ML PREDICTION (Updated to use secureUserId) ---
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