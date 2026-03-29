import requests
import pandas as pd
import numpy as np

def fetch_weather_data(lat, lon, start_date, end_date):
    print("Fetching historical weather data...")
    url = f"https://archive-api.open-meteo.com/v1/archive?latitude={lat}&longitude={lon}&start_date={start_date}&end_date={end_date}&daily=temperature_2m_max,temperature_2m_min,rain_sum&timezone=auto"
    
    response = requests.get(url)
    if response.status_code == 200:
        data = response.json()
        df = pd.DataFrame(data['daily'])
        # Rename rain_sum to rain for easier reading
        df.rename(columns={'rain_sum': 'rain'}, inplace=True)
        df['time'] = pd.to_datetime(df['time'])
        return df
    else:
        print(f"Failed to fetch data. Status code: {response.status_code}")
        return None

def add_crop_context_and_labels(weather_df):
    print("Simulating Crop Context Module and applying stress labels...")
    
    # Define our crops and their typical growing duration in days
    crops = ['Rice', 'Wheat', 'Maize']
    all_crop_data = []

    for crop in crops:
        # Create a copy of the weather timeline for this specific crop
        crop_df = weather_df.copy()
        crop_df['crop_type'] = crop
        
        # Simulate a sowing season (Simplified for the dataset)
        # Let's say we plant Rice in June, Wheat in November, Maize in April
        sowing_months = {'Rice': 6, 'Wheat': 11, 'Maize': 4}
        sowing_month = sowing_months[crop]
        
        # Calculate 'Days Since Sowing' based on the month
        # If the current month is >= sowing month, calculate days. If not, crop isn't planted yet (0).
        def calculate_growth_stage(row):
            month = row['time'].month
            
            # Very basic synthetic logic to simulate a growing season
            if month >= sowing_month and month < sowing_month + 4: 
                days_since_sowing = (month - sowing_month) * 30 + row['time'].day
                
                # Determine Growth Stage based on days
                if days_since_sowing < 30:
                    stage = "Vegetative"
                elif days_since_sowing < 75:
                    stage = "Flowering"
                else:
                    stage = "Maturity"
            else:
                days_since_sowing = 0
                stage = "Fallow_or_Harvested" # Not growing
                
            return pd.Series([days_since_sowing, stage])

        crop_df[['days_since_sowing', 'growth_stage']] = crop_df.apply(calculate_growth_stage, axis=1)
        
        # Apply Stress Rules based on Crop AND Growth Stage
        stress_labels = []
        for index, row in crop_df.iterrows():
            stage = row['growth_stage']
            t_max = row['temperature_2m_max']
            rain = row['rain']
            
            if stage == "Fallow_or_Harvested":
                stress_labels.append("No_Crop")
                continue
                
            # Dynamic Rules
            if crop == 'Wheat' and stage == 'Flowering' and t_max > 30.0:
                stress_labels.append("High") # Wheat is very sensitive to heat during flowering
            elif crop == 'Rice' and rain < 2.0 and t_max > 35.0:
                stress_labels.append("High") # Rice needs water
            elif crop == 'Maize' and stage == 'Vegetative' and t_max > 38.0:
                stress_labels.append("Medium")
            elif t_max > 36.0 and rain < 1.0:
                stress_labels.append("High") # General extreme heat/dry stress
            elif t_max > 33.0 and rain < 5.0:
                stress_labels.append("Medium")
            else:
                stress_labels.append("Low")
                
        crop_df['stress_level'] = stress_labels
        all_crop_data.append(crop_df)

    # Combine all crop data into one massive dataset
    final_df = pd.concat(all_crop_data, ignore_index=True)
    
    # Filter out the "No_Crop" days so our ML model only learns from active growing days
    final_df = final_df[final_df['stress_level'] != "No_Crop"]
    
    return final_df

if __name__ == "__main__":
    LATITUDE = 12.9165 
    LONGITUDE = 79.1325
    
    weather_df = fetch_weather_data(LATITUDE, LONGITUDE, "2021-01-01", "2023-12-31")
    
    if weather_df is not None:
        final_dataset = add_crop_context_and_labels(weather_df)
        
        # Save to CSV
        final_dataset.to_csv("crop_stress_dataset.csv", index=False)
        print("Dataset generated successfully: 'crop_stress_dataset.csv'")
        print(final_dataset[['time', 'crop_type', 'growth_stage', 'temperature_2m_max', 'rain', 'stress_level']].head(15))