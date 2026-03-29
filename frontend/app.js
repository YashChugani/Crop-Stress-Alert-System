// The base URL of API Gateway
const apiBaseUrl = "https://vja81okuxk.execute-api.ap-south-1.amazonaws.com/dev";

// --- UI NAVIGATION LOGIC ---

function switchView(viewId) {
    const views = ['authView', 'verifyView', 'dashboardView', 'addCropView', 'detailView'];
    views.forEach(id => document.getElementById(id).classList.add('hidden'));
    document.getElementById(viewId).classList.remove('hidden');
}

function showDashboard() {
    switchView('dashboardView');
    fetchAndDisplayCrops(); // Refresh the list from the real database every time we view the dashboard
}

function showAddCropView() {
    document.getElementById('newCropId').value = '';
    document.getElementById('newSowingDate').value = '';
    switchView('addCropView');
}

function showDetailView(cropId) {
    document.getElementById('activeCropId').value = cropId;
    document.getElementById('detailTitle').innerText = `🌱 ${cropId} Analysis`;
    document.getElementById('resultBox').classList.add('hidden');
    document.getElementById('tempMax').value = '';
    document.getElementById('rain').value = '';
    switchView('detailView');
}

// --- DATABASE LOGIC (DynamoDB via API Gateway) ---

async function fetchAndDisplayCrops() {
    const cropListDiv = document.getElementById('cropList');
    cropListDiv.innerHTML = '<div class="loader">Securely fetching your fields from AWS...</div>';

    if (!userJwtToken) return;

    try {
        // 1. Send GET request to /crops with JWT
        const response = await fetch(`${apiBaseUrl}/crops`, {
            method: 'GET',
            headers: {
                'Authorization': `Bearer ${userJwtToken}`
            }
        });

        if (response.ok) {
            const crops = await response.json();
            cropListDiv.innerHTML = ''; 
            
            if (crops.length === 0) {
                cropListDiv.innerHTML = '<p style="text-align:center; color:#666;">No fields registered yet. Click Add New Field!</p>';
                return;
            }

            // 2. Generate the cards dynamically from the database
            crops.forEach(crop => {
                const card = document.createElement('div');
                card.className = 'crop-card';
                card.onclick = () => showDetailView(crop.cropId);
                card.innerHTML = `
                    <div>
                        <h4>${crop.cropId}</h4>
                        <p>${crop.cropType} | Planted: ${crop.sowingDate}</p>
                    </div>
                    <div style="color: #4caf50; font-size: 1.2em;">➔</div>
                `;
                cropListDiv.appendChild(card);
            });
        } else {
            cropListDiv.innerHTML = '<p style="color: red; text-align: center;">Failed to load crops. Check console.</p>';
        }
    } catch (error) {
        cropListDiv.innerHTML = '<p style="color: red; text-align: center;">Network Error.</p>';
        console.error(error);
    }
}

async function saveNewCrop() {
    const cropId = document.getElementById('newCropId').value;
    const cropType = document.getElementById('newCropType').value;
    const sowingDate = document.getElementById('newSowingDate').value;

    if(!cropId || !sowingDate) return alert("Please fill out all fields.");

    try {
        // 1. Send POST request to /crops with JWT
        const response = await fetch(`${apiBaseUrl}/crops`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${userJwtToken}`
            },
            body: JSON.stringify({
                cropId: cropId,
                cropType: cropType,
                sowingDate: sowingDate
            })
        });

        if (response.ok) {
            alert("✅ Field securely saved to DynamoDB!");
            showDashboard(); // Slide back to dashboard to see the newly fetched list
        } else {
            alert("Error saving crop to the cloud.");
        }
    } catch (error) {
        alert("Network Error.");
        console.error(error);
    }
}

// --- ML PREDICTION LOGIC ---

async function runPrediction() {
    if (!userJwtToken) {
        alert("Security Error: No valid session token found.");
        logout();
        return;
    }

    const loader = document.getElementById('apiLoader');
    const resultBox = document.getElementById('resultBox');
    loader.classList.remove('hidden');
    resultBox.classList.add('hidden');
    resultBox.classList.remove('stress-Low', 'stress-Medium', 'stress-High');

    const payload = {
        cropId: document.getElementById('activeCropId').value, 
        temperature_2m_max: parseFloat(document.getElementById('tempMax').value),
        temperature_2m_min: 25.0, 
        rain: parseFloat(document.getElementById('rain').value)
    };

    try {
        const response = await fetch(`${apiBaseUrl}/predict`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${userJwtToken}` 
            },
            body: JSON.stringify(payload)
        });

        const data = await response.json();
        loader.classList.add('hidden');

        if (response.ok) {
            document.getElementById('stressText').innerText = `Stress Level: ${data.stress_level}`;
            document.getElementById('stageText').innerText = data.growth_stage;
            document.getElementById('ageText').innerText = data.days_since_sowing;

            resultBox.classList.add(`stress-${data.stress_level}`);
            resultBox.classList.remove('hidden');
        } else {
            alert("Error from Cloud: " + (data.error || "Unknown Error"));
        }
    } catch (error) {
        loader.classList.add('hidden');
        alert("Failed to connect to AWS. Check console.");
        console.error(error);
    }
}