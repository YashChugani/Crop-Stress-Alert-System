// The base URL of API Gateway
const apiBaseUrl = "https://vja81okuxk.execute-api.ap-south-1.amazonaws.com/dev";

// Global state variables to hold data for fast UI switching
let allCrops = [];
let currentTab = 'ACTIVE';
let todayTemp = 25.0;
let todayRain = 0.0;
let stressChartInstance = null; // Holds the Chart.js instance

// --- UI NAVIGATION LOGIC ---

function switchView(viewId) {
    const views = ['authView', 'verifyView', 'dashboardView', 'addCropView', 'detailView', 'forgotPasswordView'];
    views.forEach(id => document.getElementById(id).classList.add('hidden'));
    document.getElementById(viewId).classList.remove('hidden');
}

function showDashboard() {
    switchView('dashboardView');
    fetchAndDisplayCrops(); 
}

function showAddCropView() {
    document.getElementById('newCropId').value = '';
    document.getElementById('newSowingDate').value = '';
    switchView('addCropView');
}

function switchDashboardTab(tabName) {
    currentTab = tabName;
    document.getElementById('tabActive').className = tabName === 'ACTIVE' ? 'tab-btn active-tab' : 'tab-btn inactive-tab';
    document.getElementById('tabHarvested').className = tabName === 'HARVESTED' ? 'tab-btn active-tab' : 'tab-btn inactive-tab';
    renderCropList(); // Instantly re-render without hitting the API again
}

function showDetailView(cropId) {
    // Find the specific crop from our local memory
    const crop = allCrops.find(c => c.cropId === cropId);
    if (!crop) return;

    document.getElementById('activeCropId').value = crop.cropId;
    document.getElementById('detailTitle').innerText = `🌱 ${crop.cropId} Analysis`;
    document.getElementById('resultBox').classList.add('hidden');
    
    // Pre-fill the weather inputs with today's live data
    document.getElementById('tempMax').value = todayTemp;
    document.getElementById('rain').value = todayRain;

    // Toggle Danger Zone buttons based on status
    const actionButtons = document.getElementById('fieldActionButtons');
    if (crop.status === 'HARVESTED') {
        actionButtons.classList.add('hidden'); // Hide buttons for archived crops
    } else {
        actionButtons.classList.remove('hidden');
    }

    // Draw the Historical Trend Chart
    drawHistoricalChart(crop.stressHistory);
    
    switchView('detailView');
}

// --- DATABASE & LIVE AI LOGIC ---

async function fetchAndDisplayCrops() {
    const cropListDiv = document.getElementById('cropList');
    cropListDiv.innerHTML = '<div class="loader">Fetching live weather and farm data...</div>';

    if (!userJwtToken) return;

    try {
        // 1. Fetch Today's Weather automatically
        try {
            const weatherRes = await fetch("https://api.open-meteo.com/v1/forecast?latitude=12.98&longitude=79.13&daily=temperature_2m_max,rain_sum&timezone=auto&forecast_days=1");
            const weatherData = await weatherRes.json();
            todayTemp = weatherData.daily.temperature_2m_max[0];
            todayRain = weatherData.daily.rain_sum[0];
        } catch (e) {
            console.error("Failed to fetch weather", e);
        }

        const bannerContainer = document.getElementById('weatherBannerContainer');
        if (bannerContainer) {
            bannerContainer.innerHTML = `<div class="weather-banner"><strong>🌤️ Today's Weather (Vellore):</strong> ${todayTemp}°C | 🌧️ ${todayRain}mm rain</div>`;
        }

        // 2. Fetch Crops from Database
        const response = await fetch(`${apiBaseUrl}/crops`, {
            method: 'GET',
            headers: { 'Authorization': `Bearer ${userJwtToken}` }
        });

        if (response.ok) {
            allCrops = await response.json(); // Store globally for fast tab switching
            renderCropList();
        } else {
            cropListDiv.innerHTML = '<p style="color: red; text-align: center;">Failed to load crops. Check console.</p>';
        }
    } catch (error) {
        cropListDiv.innerHTML = '<p style="color: red; text-align: center;">Network Error.</p>';
        console.error(error);
    }
}

function renderCropList() {
    const cropListDiv = document.getElementById('cropList');
    cropListDiv.innerHTML = '';

    // Filter crops based on the currently selected tab
    const filteredCrops = allCrops.filter(crop => crop.status === currentTab);

    if (filteredCrops.length === 0) {
        cropListDiv.innerHTML = `<p style="text-align:center; color:#666;">No ${currentTab.toLowerCase()} fields found.</p>`;
        return;
    }

    filteredCrops.forEach(crop => {
        const card = document.createElement('div');
        card.className = 'crop-card';
        card.onclick = () => showDetailView(crop.cropId);
        
        let badgeHtml = '';
        if (currentTab === 'HARVESTED') {
            badgeHtml = `<span class="status-badge" style="background:#e0e0e0; color:#666;">📦 Archived</span>`;
        } else {
            badgeHtml = `<span class="status-badge" id="badge-${crop.cropId}" style="background:#eee; color:#666;">⏳ Checking...</span>`;
        }

        card.innerHTML = `
            <div>
                <h4>${crop.cropId}</h4>
                <p>${crop.cropType} | Planted: ${crop.sowingDate}</p>
            </div>
            <div class="crop-card-actions">
                ${badgeHtml}
                <div class="nav-arrow">➔</div>
            </div>
        `;
        cropListDiv.appendChild(card);

        // Only run live background AI for ACTIVE crops
        if (currentTab === 'ACTIVE') {
            fetch(`${apiBaseUrl}/predict`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${userJwtToken}` 
                },
                body: JSON.stringify({
                    cropId: crop.cropId,
                    temperature_2m_max: todayTemp,
                    temperature_2m_min: 25.0, 
                    rain: todayRain
                })
            }).then(res => res.json()).then(data => {
                const badge = document.getElementById(`badge-${crop.cropId}`);
                if (badge && data.stress_level) {
                    badge.innerText = `${data.stress_level} Stress`;
                    badge.className = `status-badge stress-${data.stress_level}`; 
                }
            }).catch(err => console.error("Background AI failed for", crop.cropId, err));
        }
    });
}

// --- FIELD MANAGEMENT LOGIC (CRUD) ---

async function saveNewCrop() {
    const cropId = document.getElementById('newCropId').value;
    const cropType = document.getElementById('newCropType').value;
    const sowingDate = document.getElementById('newSowingDate').value;

    if(!cropId || !sowingDate) return alert("Please fill out all fields.");
    document.getElementById('newCropId').value = "Saving and calculating history... Please wait.";

    try {
        const response = await fetch(`${apiBaseUrl}/crops`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${userJwtToken}`
            },
            body: JSON.stringify({ cropId, cropType, sowingDate })
        });

        if (response.ok) {
            alert("✅ Field securely saved to DynamoDB!");
            showDashboard(); 
        } else {
            const data = await response.json();
            alert("Error: " + (data.error || "Failed to save crop."));
            document.getElementById('newCropId').value = cropId; // reset input
        }
    } catch (error) {
        alert("Network Error.");
        console.error(error);
    }
}

async function markHarvested() {
    const cropId = document.getElementById('activeCropId').value;
    if (!confirm(`Are you sure you want to mark '${cropId}' as Harvested? It will be moved to the archive and daily AI analysis will stop.`)) return;

    try {
        const response = await fetch(`${apiBaseUrl}/crops`, {
            method: 'PUT',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${userJwtToken}`
            },
            body: JSON.stringify({ cropId: cropId, status: "HARVESTED" })
        });

        if (response.ok) {
            alert(`✅ ${cropId} has been successfully archived.`);
            currentTab = 'HARVESTED'; // Auto-switch to the archive tab
            showDashboard();
        } else {
            alert("Error updating status.");
        }
    } catch (error) {
        alert("Network Error.");
    }
}

async function deleteField() {
    const cropId = document.getElementById('activeCropId').value;
    if (!confirm(`⚠️ WARNING: Are you sure you want to PERMANENTLY DELETE '${cropId}'? This action cannot be undone.`)) return;

    try {
        const response = await fetch(`${apiBaseUrl}/crops?cropId=${encodeURIComponent(cropId)}`, {
            method: 'DELETE',
            headers: { 'Authorization': `Bearer ${userJwtToken}` }
        });

        if (response.ok) {
            alert(`🗑️ ${cropId} deleted successfully.`);
            showDashboard();
        } else {
            alert("Error deleting field.");
        }
    } catch (error) {
        alert("Network Error.");
    }
}

// --- CHART.JS VISUALIZATION LOGIC ---

function drawHistoricalChart(historyStr) {
    const chartContainer = document.getElementById('chartContainer');
    const ctx = document.getElementById('stressChart').getContext('2d');
    
    // Destroy the old chart
    if (stressChartInstance) {
        stressChartInstance.destroy();
    }

    const history = JSON.parse(historyStr || '[]');

    if (history.length === 0) {
        chartContainer.classList.add('hidden');
        return;
    }
    
    chartContainer.classList.remove('hidden');

    // Parse data for Chart.js
    const labels = history.map(record => record.date.substring(5)); // Show MM-DD
    const dataPoints = history.map(record => {
        if (record.stress === 'High') return 3;
        if (record.stress === 'Medium') return 2;
        return 1; // Low
    });

    // Determine line color based on the most recent stress level
    const latestStress = dataPoints[dataPoints.length - 1];
    let lineColor = '#4caf50';
    if (latestStress === 2) lineColor = '#f57c00';
    if (latestStress === 3) lineColor = '#d32f2f';

    stressChartInstance = new Chart(ctx, {
        type: 'line',
        data: {
            labels: labels,
            datasets: [{
                label: 'Historical Stress Level',
                data: dataPoints,
                borderColor: lineColor,
                backgroundColor: `${lineColor}33`,
                borderWidth: 3,
                pointBackgroundColor: lineColor,
                pointRadius: 4,
                fill: true,
                tension: 0.3
            }]
        },
        options: {
            responsive: true,
            scales: {
                y: {
                    min: 0,
                    max: 4,
                    ticks: {
                        stepSize: 1,
                        callback: function(value) {
                            if (value === 1) return 'Low';
                            if (value === 2) return 'Med';
                            if (value === 3) return 'High';
                            return '';
                        }
                    }
                }
            },
            plugins: {
                tooltip: {
                    callbacks: {
                        label: function(context) {
                            let label = context.parsed.y;
                            if (label === 1) return 'Low Stress';
                            if (label === 2) return 'Medium Stress';
                            if (label === 3) return 'High Stress';
                            return label;
                        }
                    }
                }
            }
        }
    });
}

// --- ML PREDICTION LOGIC ---

async function runPrediction() {
    if (!userJwtToken) {
        alert("Security Error: No valid session token found.");
        logout();
        return;
    }

    const tempInput = document.getElementById('tempMax').value;
    const rainInput = document.getElementById('rain').value;

    if (tempInput === '' || rainInput === '') return alert("Please enter both temperature and rain values.");

    const temp = parseFloat(tempInput);
    const rain = parseFloat(rainInput);

    if (temp < -50 || temp > 60) return alert("Invalid Input: Please enter a realistic temperature between -50°C and 60°C.");
    if (rain < 0 || rain > 2000) return alert("Invalid Input: Please enter a realistic rainfall amount between 0mm and 2000mm.");

    const loader = document.getElementById('apiLoader');
    const resultBox = document.getElementById('resultBox');
    loader.classList.remove('hidden');
    resultBox.classList.add('hidden');
    resultBox.classList.remove('stress-Low', 'stress-Medium', 'stress-High');

    const payload = {
        cropId: document.getElementById('activeCropId').value, 
        temperature_2m_max: temp,
        temperature_2m_min: 25.0, 
        rain: rain
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