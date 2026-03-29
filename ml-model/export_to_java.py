import joblib
import m2cgen as m2c

print("Loading trained model...")
rf_model = joblib.load('crop_stress_rf_model.joblib')

print("Translating Random Forest into pure Java code...")
# This generates a native Java class containing the ML logic
java_code = m2c.export_to_java(rf_model, class_name="CropStressPredictor")

# Save the output to a .java file
with open('CropStressPredictor.java', 'w') as f:
    f.write(java_code)

print("Success! 'CropStressPredictor.java' has been generated.")