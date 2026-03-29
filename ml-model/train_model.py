import pandas as pd
from sklearn.model_selection import train_test_split
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score, classification_report
import joblib
import os
from datetime import datetime

print("Loading dataset...")
# 1. Load the generated dataset
df = pd.read_csv("crop_stress_dataset.csv")

# 2. Prepare the Features (X) and Target (y)
X = df.drop(columns=['time', 'stress_level'])
y = df['stress_level']

print("Encoding categorical data...")
# 3. Convert text columns into numbers
X_encoded = pd.get_dummies(X, columns=['crop_type', 'growth_stage'])

# Save the column blueprint
model_columns = list(X_encoded.columns)
joblib.dump(model_columns, 'model_columns.pkl')

print("Splitting data into training and testing sets...")
# 4. Split data: 80% for training, 20% for testing
X_train, X_test, y_train, y_test = train_test_split(X_encoded, y, test_size=0.2, random_state=42)

print("Training the Random Forest Model...")
# 5. Initialize and train the model
rf_model = RandomForestClassifier(n_estimators=100, random_state=42)
rf_model.fit(X_train, y_train)

print("Evaluating Model Performance...")
# 6. Test the model
predictions = rf_model.predict(X_test)
accuracy = accuracy_score(y_test, predictions)
class_report = classification_report(y_test, predictions)

# Calculate Feature Importances for Documentation
feature_importances = pd.DataFrame({
    'Feature': X_encoded.columns,
    'Importance': rf_model.feature_importances_
}).sort_values(by='Importance', ascending=False)

# 7. Save the trained model
joblib.dump(rf_model, 'crop_stress_rf_model.joblib')

# ---------------------------------------------------------
# 8. GENERATE DOCUMENTATION REPORT
# ---------------------------------------------------------
print("Generating evaluation report for documentation...")
os.makedirs("reports", exist_ok=True)
report_path = os.path.join("reports", "model_evaluation_report.txt")

with open(report_path, "w") as f:
    f.write("====================================================\n")
    f.write(" MACHINE LEARNING MODEL EVALUATION REPORT\n")
    f.write("====================================================\n")
    f.write(f"Date Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
    f.write(f"Model Type: Random Forest Classifier\n")
    f.write(f"Dataset Size: {len(df)} records\n")
    f.write(f"Training Split: 80% ({len(X_train)} records)\n")
    f.write(f"Testing Split: 20% ({len(X_test)} records)\n")
    f.write("----------------------------------------------------\n\n")
    
    f.write(f"OVERALL ACCURACY: {accuracy * 100:.2f}%\n\n")
    
    f.write("CLASSIFICATION REPORT:\n")
    f.write(class_report + "\n\n")
    
    f.write("FEATURE IMPORTANCE (What drove the predictions?):\n")
    f.write(feature_importances.to_string(index=False) + "\n\n")
    
    f.write("====================================================\n")
    f.write("Notes for Documentation:\n")
    f.write("- The model relies heavily on the top features listed above.\n")
    f.write("- '1' in encoded categorical columns indicates presence, '0' indicates absence.\n")

print(f"Model saved successfully as 'crop_stress_rf_model.joblib'")
print(f"Documentation saved successfully in '{report_path}'")