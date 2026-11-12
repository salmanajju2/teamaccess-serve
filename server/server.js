/**
 * TeamAccess Remote - High-Performance WebRTC Signaling & Rendezvous Server
 * Handles 9-digit peer matching, session authentication, and WebRTC SDP/ICE relay.
 */

const WebSocket = require('ws');
const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = process.env.PORT || 9090;
const server = http.createServer((req, res) => {
    if (req.url === '/' || req.url === '/index.html') {
        const filePath = path.join(__dirname, 'public', 'index.html');
        fs.readFile(filePath, (err, data) => {
            if (err) {
                res.writeHead(500, { 'Content-Type': 'text/plain' });
                res.end('Error loading dashboard');
                return;
            }
            res.writeHead(200, { 'Content-Type': 'text/html' });
            res.end(data);
        });
        return;
    }

    // JSON API Status endpoint
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
        status: 'online',
        service: 'TeamAccess Signaling Server',
        connectedPeers: clients.size,
        timestamp: new Date().toISOString()
    }));
});

const wss = new WebSocket.Server({ server });

// Map: peerId -> { ws, pin, partnerId }
const clients = new Map();

function generatePeerId() {
    let id;
    do {
        // Generate a 9-digit ID formatted like 123-456-789
        const raw = Math.floor(100000000 + Math.random() * 900000000).toString();
        id = `${raw.slice(0, 3)}-${raw.slice(3, 6)}-${raw.slice(6, 9)}`;
    } while (clients.has(id));
    return id;
}

function sendTo(ws, payload) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(payload));
    }
}

wss.on('connection', (ws) => {
    let currentPeerId = null;

    ws.on('message', (messageText) => {
        try {
            const data = JSON.parse(messageText);

            switch (data.type) {
                // 1. Host registers its ID and session PIN
                case 'register': {
                    let requestedId = data.peerId;
                    if (!requestedId) {
                        requestedId = generatePeerId();
                    } else if (clients.has(requestedId)) {
                        const existing = clients.get(requestedId);
                        if (existing.ws !== ws && existing.ws.readyState === WebSocket.OPEN) {
                            existing.ws.close(1000, 'Replaced by new connection');
                        }
                    }
                    currentPeerId = requestedId;
                    clients.set(currentPeerId, {
                        ws: ws,
                        pin: data.pin || '1234',
                        partnerId: null
                    });

                    sendTo(ws, {
                        type: 'registered',
                        peerId: currentPeerId,
                        message: 'Successfully registered with signaling server'
                    });
                    console.log(`[INFO] Peer registered: ${currentPeerId}`);
                    break;
                }

                // 2. Client initiates connection to Host with Partner ID & PIN
                case 'connect_request': {
                    const targetId = data.targetId;
                    const enteredPin = data.pin;
                    const target = clients.get(targetId);

                    if (!target) {
                        sendTo(ws, {
                            type: 'connect_error',
                            message: 'Partner ID not found or offline.'
                        });
                        return;
                    }

                    if (target.pin !== enteredPin) {
                        sendTo(ws, {
                            type: 'connect_error',
                            message: 'Invalid partner password/PIN.'
                        });
                        return;
                    }

                    // Pair the peers
                    clients.get(currentPeerId).partnerId = targetId;
                    target.partnerId = currentPeerId;

                    // Notify Host about incoming connection
                    sendTo(target.ws, {
                        type: 'incoming_connection',
                        fromId: currentPeerId
                    });

                    // Acknowledge Client
                    sendTo(ws, {
                        type: 'connect_accepted',
                        targetId: targetId
                    });
                    console.log(`[INFO] Paired ${currentPeerId} with ${targetId}`);
                    break;
                }

                // 3. WebRTC SDP Offer Relay
                case 'offer': {
                    const partnerId = clients.get(currentPeerId)?.partnerId;
                    const partner = clients.get(partnerId);
                    if (partner) {
                        sendTo(partner.ws, {
                            type: 'offer',
                            sdp: data.sdp,
                            fromId: currentPeerId
                        });
                    }
                    break;
                }

                // 4. WebRTC SDP Answer Relay
                case 'answer': {
                    const partnerId = clients.get(currentPeerId)?.partnerId;
                    const partner = clients.get(partnerId);
                    if (partner) {
                        sendTo(partner.ws, {
                            type: 'answer',
                            sdp: data.sdp,
                            fromId: currentPeerId
                        });
                    }
                    break;
                }

                // 5. ICE Candidate Relay
                case 'ice_candidate': {
                    const partnerId = clients.get(currentPeerId)?.partnerId;
                    const partner = clients.get(partnerId);
                    if (partner) {
                        sendTo(partner.ws, {
                            type: 'ice_candidate',
                            candidate: data.candidate,
                            fromId: currentPeerId
                        });
                    }
                    break;
                }

                // 6. Terminate session
                case 'disconnect_session': {
                    const partnerId = clients.get(currentPeerId)?.partnerId;
                    const partner = clients.get(partnerId);
                    if (partner) {
                        sendTo(partner.ws, {
                            type: 'session_ended',
                            message: 'Remote party disconnected the session.'
                        });
                        partner.partnerId = null;
                    }
                    if (clients.has(currentPeerId)) {
                        clients.get(currentPeerId).partnerId = null;
                    }
                    break;
                }

                // Heartbeat / ping
                case 'ping': {
                    sendTo(ws, { type: 'pong' });
                    break;
                }

                default:
                    console.warn(`[WARN] Unknown message type: ${data.type}`);
            }
        } catch (err) {
            console.error('[ERROR] Failed to process message:', err.message);
        }
    });

    ws.on('close', () => {
        if (currentPeerId && clients.has(currentPeerId)) {
            const partnerId = clients.get(currentPeerId).partnerId;
            const partner = clients.get(partnerId);
            if (partner) {
                sendTo(partner.ws, {
                    type: 'session_ended',
                    message: 'Partner went offline.'
                });
                partner.partnerId = null;
            }
            clients.delete(currentPeerId);
            console.log(`[INFO] Peer disconnected: ${currentPeerId}`);
        }
    });

    ws.on('error', (err) => {
        console.error(`[ERROR] WebSocket error on peer ${currentPeerId}:`, err.message);
    });
});

server.listen(PORT, () => {
    console.log(`=======================================================`);
    console.log(` TeamAccess Signaling Server running on port ${PORT}`);
    console.log(` WebSocket URL: ws://localhost:${PORT}`);
    console.log(`=======================================================`);
});
