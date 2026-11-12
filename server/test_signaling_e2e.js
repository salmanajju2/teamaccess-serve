const WebSocket = require('ws');
const http = require('http');

const PORT = 9090;
const BASE_URL = `http://localhost:${PORT}`;
const WS_URL = `ws://localhost:${PORT}`;

let testsPassed = 0;
let testsFailed = 0;

function assert(condition, message) {
    if (condition) {
        console.log(`  [PASS] ${message}`);
        testsPassed++;
    } else {
        console.error(`  [FAIL] ${message}`);
        testsFailed++;
    }
}

function httpGet(path) {
    return new Promise((resolve, reject) => {
        http.get(`${BASE_URL}${path}`, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => resolve({ statusCode: res.statusCode, data }));
        }).on('error', reject);
    });
}

function createClient() {
    return new Promise((resolve, reject) => {
        const ws = new WebSocket(WS_URL);
        ws.on('open', () => resolve(ws));
        ws.on('error', reject);
    });
}

function waitForMessage(ws, predicate, timeoutMs = 3000) {
    return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            ws.removeListener('message', listener);
            reject(new Error(`Timeout waiting for message matching predicate`));
        }, timeoutMs);

        function listener(data) {
            try {
                const parsed = JSON.parse(data.toString());
                if (predicate(parsed)) {
                    clearTimeout(timer);
                    ws.removeListener('message', listener);
                    resolve(parsed);
                }
            } catch (e) {}
        }

        ws.on('message', listener);
    });
}

async function runTests() {
    console.log('========================================================');
    console.log(' STARTING END-TO-END APPLICATION SIGNALING TEST SUITE');
    console.log('========================================================\n');

    // Test 1: HTTP Root Dashboard
    console.log('[TEST 1] Testing HTTP Dashboard GET /');
    const rootRes = await httpGet('/');
    assert(rootRes.statusCode === 200 && rootRes.data.includes('TeamAccess Remote'), 'Dashboard loads successfully with 200 OK');

    // Test 2: HTTP Status JSON API
    console.log('\n[TEST 2] Testing HTTP JSON API GET /api/status');
    const apiRes = await httpGet('/api/status');
    assert(apiRes.statusCode === 200, 'API endpoint returns 200 OK');
    const statusJson = JSON.parse(apiRes.data);
    assert(statusJson.status === 'online', 'Server reports status online');

    // Test 3: WebSocket Host Registration
    console.log('\n[TEST 3] Testing Host Registration over WebSocket');
    const hostWs = await createClient();
    hostWs.send(JSON.stringify({ type: 'register', pin: '9876' }));
    const hostReg = await waitForMessage(hostWs, m => m.type === 'registered');
    assert(hostReg.type === 'registered', 'Host received registered event');
    assert(/^\d{3}-\d{3}-\d{3}$/.test(hostReg.peerId), `Host got valid 9-digit peer ID: ${hostReg.peerId}`);
    const hostPeerId = hostReg.peerId;

    // Test 4: WebSocket Client Registration
    console.log('\n[TEST 4] Testing Client Registration over WebSocket');
    const clientWs = await createClient();
    clientWs.send(JSON.stringify({ type: 'register', pin: '1111' }));
    const clientReg = await waitForMessage(clientWs, m => m.type === 'registered');
    assert(clientReg.type === 'registered', 'Client registered event received');
    const clientPeerId = clientReg.peerId;

    // Test 5: Negative Test - Connect to non-existent ID
    console.log('\n[TEST 5] Testing Connect Request to non-existent peer');
    clientWs.send(JSON.stringify({ type: 'connect_request', targetId: '000-000-000', pin: '9876' }));
    const notFoundErr = await waitForMessage(clientWs, m => m.type === 'connect_error');
    assert(notFoundErr.message.includes('not found') || notFoundErr.message.includes('offline'), 'Correctly rejected with "Partner ID not found"');

    // Test 6: Negative Test - Connect with wrong PIN
    console.log('\n[TEST 6] Testing Connect Request with wrong PIN');
    clientWs.send(JSON.stringify({ type: 'connect_request', targetId: hostPeerId, pin: '0000' }));
    const wrongPinErr = await waitForMessage(clientWs, m => m.type === 'connect_error');
    assert(wrongPinErr.message.includes('Invalid partner password/PIN'), 'Correctly rejected with "Invalid partner password/PIN"');

    // Test 7: Positive Test - Connect with valid PIN
    console.log('\n[TEST 7] Testing Connect Request with correct PIN (9876)');
    const incomingPromise = waitForMessage(hostWs, m => m.type === 'incoming_connection');
    const acceptedPromise = waitForMessage(clientWs, m => m.type === 'connect_accepted');
    clientWs.send(JSON.stringify({ type: 'connect_request', targetId: hostPeerId, pin: '9876' }));

    const incoming = await incomingPromise;
    const accepted = await acceptedPromise;
    assert(incoming.fromId === clientPeerId, `Host notified of incoming connection from ${incoming.fromId}`);
    assert(accepted.targetId === hostPeerId, `Client connection accepted for ${accepted.targetId}`);

    // Test 8: WebRTC SDP Offer Relay
    console.log('\n[TEST 8] Testing WebRTC SDP Offer Relay');
    const offerPromise = waitForMessage(hostWs, m => m.type === 'offer');
    const dummySdp = 'v=0\r\no=- 42 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n';
    clientWs.send(JSON.stringify({ type: 'offer', sdp: dummySdp }));
    const receivedOffer = await offerPromise;
    assert(receivedOffer.sdp === dummySdp, 'Host received exact SDP offer from client');
    assert(receivedOffer.fromId === clientPeerId, `Offer fromId matches client (${receivedOffer.fromId})`);

    // Test 9: WebRTC SDP Answer Relay
    console.log('\n[TEST 9] Testing WebRTC SDP Answer Relay');
    const answerPromise = waitForMessage(clientWs, m => m.type === 'answer');
    const dummyAnswerSdp = 'v=0\r\no=- 84 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n';
    hostWs.send(JSON.stringify({ type: 'answer', sdp: dummyAnswerSdp }));
    const receivedAnswer = await answerPromise;
    assert(receivedAnswer.sdp === dummyAnswerSdp, 'Client received exact SDP answer from host');
    assert(receivedAnswer.fromId === hostPeerId, `Answer fromId matches host (${receivedAnswer.fromId})`);

    // Test 10: WebRTC ICE Candidate Relay
    console.log('\n[TEST 10] Testing WebRTC ICE Candidate Relay');
    const icePromise = waitForMessage(hostWs, m => m.type === 'ice_candidate');
    const dummyCandidate = { candidate: 'candidate:1 1 UDP 2130706431 192.168.1.100 50000 typ host', sdpMid: '0', sdpMLineIndex: 0 };
    clientWs.send(JSON.stringify({ type: 'ice_candidate', candidate: dummyCandidate }));
    const receivedIce = await icePromise;
    assert(receivedIce.candidate.candidate === dummyCandidate.candidate, 'ICE candidate correctly relayed to host');

    // Test 11: Heartbeat Ping / Pong
    console.log('\n[TEST 11] Testing Ping / Pong Keep-Alive');
    const pongPromise = waitForMessage(clientWs, m => m.type === 'pong');
    clientWs.send(JSON.stringify({ type: 'ping' }));
    const pong = await pongPromise;
    assert(pong.type === 'pong', 'Server responded with pong to keep-alive heartbeat');

    // Test 12: Session Termination
    console.log('\n[TEST 12] Testing Graceful Session Disconnect');
    const sessionEndPromise = waitForMessage(hostWs, m => m.type === 'session_ended');
    clientWs.send(JSON.stringify({ type: 'disconnect_session' }));
    const endMsg = await sessionEndPromise;
    assert(endMsg.type === 'session_ended', 'Host received session_ended notification');

    // Cleanup
    hostWs.close();
    clientWs.close();

    console.log('\n========================================================');
    console.log(` TEST SUMMARY: ${testsPassed} PASSED, ${testsFailed} FAILED`);
    console.log('========================================================\n');

    process.exit(testsFailed === 0 ? 0 : 1);
}

runTests().catch(err => {
    console.error('Fatal test error:', err);
    process.exit(1);
});
