# 🌱 Crop Stress Intelligence System

An enterprise-grade, serverless Decision Support System (DSS) designed to help farmers proactively manage crop health. This application uses Machine Learning and live weather data to predict agricultural stress levels and automatically alert farmers of critical conditions before crop damage occurs.

---

## 🚀 Live Demo
**Frontend Hosted on AWS Amplify:** [Project Link](https://dev.dyl92v2uf6oaj.amplifyapp.com)  
*(Note: This live demo is hosted on AWS Free Tier and may become unavailable after the free tier period expires. If the link is down, please see the Local Setup instructions below.)*

---

## 🏗️ Architecture & Tech Stack

This project implements a fully serverless cloud architecture on Amazon Web Services (AWS), ensuring high scalability, zero server maintenance, and robust security.

### **Frontend (Client Layer)**
* **Tech:** Vanilla JavaScript, HTML5, CSS3
* **Hosting:** AWS Amplify
* **Architecture:** Single Page Application (SPA) offering a seamless, app-like experience without page reloads.

### **Security & Identity (Auth Layer)**
* **Tech:** Amazon Cognito
* **Implementation:** Secure user registration and login flow issuing JSON Web Tokens (JWT). The frontend utilizes a Public App Client (no client secret) for secure browser-based authentication.

### **API & Routing (Network Layer)**
* **Tech:** Amazon API Gateway
* **Implementation:** RESTful API secured by a Cognito Authorizer. It validates JWTs on every request, ensuring only authenticated farmers can read/write data or trigger the ML model.

### **Backend Compute & Machine Learning (Logic Layer)**
* **Tech:** Java 17, AWS Lambda, m2cgen
* **Implementation:** * The original Python Random Forest model was transpiled into pure Java to bypass Lambda deployment limits and achieve millisecond execution times.
  * A central Lambda router handles CRUD operations for the farmer's dashboard and executes the ML inference.

### **Database (Data Layer)**
* **Tech:** Amazon DynamoDB
* **Implementation:** A highly scalable NoSQL table (`CropProfiles`) that securely maps individual fields (crop type, sowing date) to verified Cognito User IDs.

### **Automation & Alerts (The "Invisible Guardian")**
* **Tech:** Amazon EventBridge, AWS Lambda, Amazon SNS, Open-Meteo API
* **Implementation:** A scheduled EventBridge cron job wakes up a dedicated Lambda function daily at 6:30 AM IST. It fetches live weather forecasts for the region, scans the database, runs the ML model against every registered field, and triggers an Amazon SNS email alert to the farmer if "High" stress is detected.

---

## ✨ Key Features

1. **Secure Multi-Tenant Dashboard:** Farmers can securely log in and manage their specific fields without seeing other users' data.
2. **Dynamic Agronomic Calculations:** The system automatically calculates crop age (days since sowing) and current growth stage (Vegetative, Flowering, Maturity) in real-time.
3. **On-Demand ML Analysis:** Users can manually input extreme weather scenarios to simulate how their crops will react.
4. **Proactive Daily Monitoring:** Completely automated background processing that monitors weather forecasts without requiring the user to open the app.
5. **Emergency Notifications:** Push alerts delivered directly to the farmer's inbox when dangerous weather combinations threaten sensitive crop stages.

---

## 🧠 Machine Learning Model Information

The predictive engine of this application is powered by a custom-trained Machine Learning model designed to analyze environmental and agronomic factors to determine crop stress levels.

* **Algorithm:** Random Forest Classifier
* **Dataset Size:** 915 agricultural records
* **Data Split:** 80% Training (732 records) / 20% Testing (183 records)
* **Overall Accuracy:** 97.27%

### Model Performance Metrics
The model classifies crop stress into three distinct categories with high reliability:
* **Low Stress:** 99% F1-Score
* **Medium Stress:** 96% F1-Score
* **High Stress:** 95% F1-Score

### Key Predictive Features (Feature Importance)
The model's decision-making process is primarily driven by the following environmental and temporal factors:
1. **Maximum Temperature (`temperature_2m_max`):** 45.75% importance
2. **Rainfall (`rain`):** 23.58% importance
3. **Minimum Temperature (`temperature_2m_min`):** 12.98% importance
4. **Crop Age (`days_since_sowing`):** 7.31% importance

*(Note: Categorical features like Crop Type and Growth Stage were one-hot encoded for model training.)*

---

## 📂 Project Structure

```text
├── frontend/                  # Single Page Application files
│   ├── index.html             # UI Structure
│   ├── styles.css             # Agriculture-themed styling
│   ├── app.js                 # API fetching, routing, and UI logic
│   └── auth.js                # Cognito JWT authentication logic
├── src/main/java/com/cropstress/
│   ├── CropStressApiHandler.java        # Main REST API Router & DB Logic
│   ├── CropStressAutomationHandler.java # Daily EventBridge Cron Job
│   └── CropStressPredictor.java         # Transpiled Random Forest ML Model
├── pom.xml                    # Maven dependencies (AWS SDK, Gson)
└── README.md                  # Project documentation
```

---

## 🛠️ Local Development Setup

**Note:** Since this is a cloud-native serverless application, local execution requires deploying the AWS infrastructure first.

1. Clone the repository.
2. Run `mvn clean package` to build the backend Java `.jar`.
3. Deploy the backend to AWS Lambda, API Gateway, and DynamoDB.
4. Update `apiBaseUrl` in `frontend/app.js` with your new API Gateway endpoint.
5. Update `poolData` in `frontend/auth.js` with your Cognito User Pool credentials.
6. Open `frontend/index.html` in any modern web browser.

---

## 👨‍💻 Contributors

* **Yash Chugani** - *System Architecture, ML Integration, & Full-Stack Development*
* [GitHub](https://github.com/YashChugani)

---