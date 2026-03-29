const poolData = {
    UserPoolId: 'ap-south-1_K4N6nrJMh',
    ClientId: '1m0b7qehibua885o06882q8p1t'
};

const userPool = new AmazonCognitoIdentity.CognitoUserPool(poolData);
let cognitoUser;
let userJwtToken = null;

function showMessage(elementId, msg) {
    const el = document.getElementById(elementId);
    el.innerText = msg;
    el.classList.remove('hidden');
}

// 1. SIGN UP
function signUp() {
    const email = document.getElementById('emailInput').value;
    const password = document.getElementById('passwordInput').value;

    if (!email || !password) return alert("Please enter email and password.");
    showMessage('authMessage', 'Creating account...');

    const attributeList = [
        new AmazonCognitoIdentity.CognitoUserAttribute({ Name: 'email', Value: email })
    ];

    userPool.signUp(email, password, attributeList, null, function(err, result) {
        if (err) {
            showMessage('authMessage', err.message || JSON.stringify(err));
            return;
        }
        cognitoUser = result.user;
        switchView('verifyView');
    });
}

// 2. VERIFY EMAIL CODE
function verifyCode() {
    const code = document.getElementById('verifyCodeInput').value;
    
    cognitoUser.confirmRegistration(code, true, function(err, result) {
        if (err) {
            alert(err.message || JSON.stringify(err));
            return;
        }
        alert("Verification successful! You can now log in.");
        switchView('authView');
    });
}

// 3. LOG IN
function login() {
    const email = document.getElementById('emailInput').value;
    const password = document.getElementById('passwordInput').value;

    if (!email || !password) return alert("Please enter email and password.");
    showMessage('authMessage', 'Authenticating securely...');

    const authenticationDetails = new AmazonCognitoIdentity.AuthenticationDetails({
        Username: email,
        Password: password,
    });

    const userData = { Username: email, Pool: userPool };
    cognitoUser = new AmazonCognitoIdentity.CognitoUser(userData);

    cognitoUser.authenticateUser(authenticationDetails, {
        onSuccess: function(result) {
            // SUCCESS! grab the secure JWT Token (The VIP Pass)
            userJwtToken = result.getIdToken().getJwtToken();
            
            // Move to the dashboard
            document.getElementById('authMessage').classList.add('hidden');
            showDashboard();
        },
        onFailure: function(err) {
            showMessage('authMessage', err.message || JSON.stringify(err));
        },
    });
}

// 4. LOG OUT
// 4. LOG OUT
function logout() {
    if (cognitoUser) cognitoUser.signOut();
    userJwtToken = null;
    document.getElementById('emailInput').value = '';
    document.getElementById('passwordInput').value = '';
    switchView('authView'); 
}