use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        State,
    },
    http::StatusCode,
    response::{Html, IntoResponse, Response},
    routing::get,
    Json, Router,
};
use chrono::Utc;
use dashmap::DashMap;
use futures_util::{sink::SinkExt, stream::StreamExt};
use rand::Rng;
use serde::Serialize;
use serde_json::{json, Value};
use std::{net::SocketAddr, sync::Arc};
use tokio::sync::mpsc;
use tower_http::services::ServeDir;
use tracing::{info, warn};

#[derive(Clone)]
struct PeerSession {
    sender: mpsc::UnboundedSender<Message>,
    _pin: String,
    partner_id: Option<String>,
    device_name: String,
    is_host: bool,
}

type PeerMap = Arc<DashMap<String, PeerSession>>;

#[derive(Serialize)]
struct StatusResponse {
    status: &'static str,
    service: &'static str,
    connected_peers: usize,
    timestamp: String,
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "teamaccess_signaling_server=info,tower_http=info".into()),
        )
        .init();

    let peers: PeerMap = Arc::new(DashMap::new());

    let port: u16 = std::env::var("PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(9090);

    let app = Router::new()
        .route("/ws", get(ws_handler))
        .route("/", get(root_handler))
        .route("/api/status", get(status_handler))
        .route("/api/devices", get(devices_handler))
        .route("/health", get(health_handler))
        .nest_service("/public", ServeDir::new("public"))
        .fallback(fallback_handler)
        .with_state(peers.clone());

    let addr = SocketAddr::from(([0, 0, 0, 0], port));
    info!("=======================================================");
    info!(" TeamAccess Signaling Server (Rust) starting on {}", addr);
    info!(" WebSocket URL: ws://localhost:{}/ws", port);
    info!("=======================================================");

    let listener = tokio::net::TcpListener::bind(addr)
        .await
        .expect("Failed to bind TCP listener");

    axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .await
    .expect("Server encountered fatal error");
}

async fn root_handler(
    ws: Option<WebSocketUpgrade>,
    State(peers): State<PeerMap>,
) -> Response {
    if let Some(ws) = ws {
        ws.on_upgrade(move |socket| handle_socket(socket, peers))
    } else {
        index_handler().await.into_response()
    }
}

async fn index_handler() -> impl IntoResponse {
    match tokio::fs::read_to_string("public/index.html").await {
        Ok(content) => Html(content).into_response(),
        Err(_) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            "Error loading dashboard: public/index.html not found",
        )
            .into_response(),
    }
}

async fn status_handler(State(peers): State<PeerMap>) -> Json<StatusResponse> {
    Json(StatusResponse {
        status: "online",
        service: "TeamAccess Signaling Server (Rust Engine)",
        connected_peers: peers.len(),
        timestamp: Utc::now().to_rfc3339(),
    })
}

async fn devices_handler(State(peers): State<PeerMap>) -> Json<Value> {
    let devices: Vec<Value> = peers
        .iter()
        .filter(|entry| entry.is_host)
        .map(|entry| {
            json!({
                "id": entry.key(),
                "name": entry.device_name,
                "status": if entry.partner_id.is_some() { "busy" } else { "online" }
            })
        })
        .collect();
    Json(json!({ "devices": devices }))
}

async fn health_handler() -> &'static str {
    "OK"
}

async fn fallback_handler(State(peers): State<PeerMap>) -> Json<StatusResponse> {
    status_handler(State(peers)).await
}

async fn ws_handler(
    ws: WebSocketUpgrade,
    State(peers): State<PeerMap>,
) -> Response {
    ws.on_upgrade(move |socket| handle_socket(socket, peers))
}

fn normalize_id(id: &str) -> String {
    id.chars().filter(|c| c.is_ascii_digit()).collect()
}

fn generate_peer_id(peers: &DashMap<String, PeerSession>) -> String {
    let mut rng = rand::thread_rng();
    loop {
        let raw = rng.gen_range(100_000_000..999_999_999);
        let s = raw.to_string();
        let formatted = format!("{}-{}-{}", &s[0..3], &s[3..6], &s[6..9]);
        if !peers.contains_key(&formatted) {
            return formatted;
        }
    }
}

fn send_payload(sender: &mpsc::UnboundedSender<Message>, payload: &Value) {
    let text = payload.to_string();
    let _ = sender.send(Message::Text(text));
}

fn broadcast_device_list(peers: &PeerMap) {
    let devices: Vec<Value> = peers
        .iter()
        .filter(|entry| entry.is_host)
        .map(|entry| {
            json!({
                "id": entry.key(),
                "name": entry.device_name,
                "status": if entry.partner_id.is_some() { "busy" } else { "online" }
            })
        })
        .collect();

    let msg = json!({
        "type": "devices_list",
        "devices": devices
    });

    for entry in peers.iter() {
        send_payload(&entry.sender, &msg);
    }
}

async fn handle_socket(socket: WebSocket, peers: PeerMap) {
    let (mut ws_sender, mut ws_receiver) = socket.split();
    let (tx, mut rx) = mpsc::unbounded_channel::<Message>();

    // Forward outbound messages from channel to actual websocket
    let forward_task = tokio::spawn(async move {
        while let Some(msg) = rx.recv().await {
            if ws_sender.send(msg).await.is_err() {
                break;
            }
        }
    });

    let mut current_peer_id: Option<String> = None;

    while let Some(Ok(msg)) = ws_receiver.next().await {
        if let Message::Text(text) = msg {
            let data: Value = match serde_json::from_str(&text) {
                Ok(v) => v,
                Err(e) => {
                    warn!("Invalid JSON received: {}", e);
                    continue;
                }
            };

            let msg_type = match data.get("type").and_then(|t| t.as_str()) {
                Some(t) => t,
                None => continue,
            };

            match msg_type {
                "get_devices" => {
                    let devices: Vec<Value> = peers
                        .iter()
                        .filter(|entry| entry.is_host)
                        .map(|entry| {
                            json!({
                                "id": entry.key(),
                                "name": entry.device_name,
                                "status": if entry.partner_id.is_some() { "busy" } else { "online" }
                            })
                        })
                        .collect();

                    send_payload(
                        &tx,
                        &json!({
                            "type": "devices_list",
                            "devices": devices
                        }),
                    );
                }

                "register" => {
                    let mut requested_id = data
                        .get("peerId")
                        .and_then(|v| v.as_str())
                        .map(|s| s.to_string());

                    let pin = data
                        .get("pin")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string();

                    let device_name = data
                        .get("deviceName")
                        .and_then(|v| v.as_str())
                        .unwrap_or("Android Phone")
                        .to_string();

                    let is_host = data
                        .get("isHost")
                        .and_then(|v| v.as_bool())
                        .unwrap_or(true);

                    let peer_id = match requested_id.take() {
                        Some(id) if !id.trim().is_empty() => {
                            if let Some(existing) = peers.get(&id) {
                                send_payload(
                                    &existing.sender,
                                    &json!({
                                        "type": "error",
                                        "message": "Replaced by new connection"
                                    }),
                                );
                            }
                            id
                        }
                        _ => generate_peer_id(&peers),
                    };

                    current_peer_id = Some(peer_id.clone());
                    peers.insert(
                        peer_id.clone(),
                        PeerSession {
                            sender: tx.clone(),
                            _pin: pin,
                            partner_id: None,
                            device_name,
                            is_host,
                        },
                    );

                    info!("Peer registered: {} (Host: {})", peer_id, is_host);
                    send_payload(
                        &tx,
                        &json!({
                            "type": "registered",
                            "peerId": peer_id,
                            "message": "Successfully registered with Rust signaling server"
                        }),
                    );

                    // Notify all dashboards of the updated online devices list
                    broadcast_device_list(&peers);
                }

                "connect_request" => {
                    let my_id = match current_peer_id {
                        Some(ref id) => id.clone(),
                        None => {
                            let temp_id = generate_peer_id(&peers);
                            current_peer_id = Some(temp_id.clone());
                            peers.insert(
                                temp_id.clone(),
                                PeerSession {
                                    sender: tx.clone(),
                                    _pin: "".to_string(),
                                    partner_id: None,
                                    device_name: "Web Controller".to_string(),
                                    is_host: false,
                                },
                            );
                            info!("Auto-registered connecting client: {}", temp_id);
                            temp_id
                        }
                    };

                    let target_id_raw = data
                        .get("targetId")
                        .and_then(|v| v.as_str())
                        .unwrap_or("");

                    let norm_target = normalize_id(target_id_raw);
                    let matched_target_id = peers.iter().find_map(|entry| {
                        if normalize_id(entry.key()) == norm_target {
                            Some(entry.key().clone())
                        } else {
                            None
                        }
                    });

                    let target_id = match matched_target_id {
                        Some(id) => id,
                        None => {
                            send_payload(
                                &tx,
                                &json!({
                                    "type": "connect_error",
                                    "message": "Device not found or offline."
                                }),
                            );
                            continue;
                        }
                    };

                    let target_session = match peers.get(&target_id) {
                        Some(session) => session.clone(),
                        None => {
                            send_payload(
                                &tx,
                                &json!({
                                    "type": "connect_error",
                                    "message": "Device not found or offline."
                                }),
                            );
                            continue;
                        }
                    };

                    // If target already has an active partner, notify the old partner of session takeover
                    if let Some(old_partner_id) = target_session.partner_id.clone() {
                        if old_partner_id != my_id {
                            if let Some(mut old_partner) = peers.get_mut(&old_partner_id) {
                                send_payload(
                                    &old_partner.sender,
                                    &json!({
                                        "type": "session_ended",
                                        "message": "Session taken over by new connection"
                                    }),
                                );
                                old_partner.partner_id = None;
                            }
                        }
                    }

                    // Pair both peers directly (Zero-password instant 1-click connect)
                    if let Some(mut me) = peers.get_mut(&my_id) {
                        me.partner_id = Some(target_id.clone());
                    }
                    if let Some(mut target) = peers.get_mut(&target_id) {
                        target.partner_id = Some(my_id.clone());
                    }

                    // Notify Host device
                    send_payload(
                        &target_session.sender,
                        &json!({
                            "type": "incoming_connection",
                            "fromId": my_id
                        }),
                    );

                    // Acknowledge Web Client
                    send_payload(
                        &tx,
                        &json!({
                            "type": "connect_accepted",
                            "targetId": target_id
                        }),
                    );

                    info!("Paired 1-Click Connection: {} -> {}", my_id, target_id);
                    broadcast_device_list(&peers);
                }

                "offer" => {
                    if let Some(ref my_id) = current_peer_id {
                        if let Some(partner_id) = peers.get(my_id).and_then(|p| p.partner_id.clone()) {
                            if let Some(partner) = peers.get(&partner_id) {
                                send_payload(
                                    &partner.sender,
                                    &json!({
                                        "type": "offer",
                                        "sdp": data.get("sdp"),
                                        "fromId": my_id
                                    }),
                                );
                            }
                        }
                    }
                }

                "answer" => {
                    if let Some(ref my_id) = current_peer_id {
                        if let Some(partner_id) = peers.get(my_id).and_then(|p| p.partner_id.clone()) {
                            if let Some(partner) = peers.get(&partner_id) {
                                send_payload(
                                    &partner.sender,
                                    &json!({
                                        "type": "answer",
                                        "sdp": data.get("sdp"),
                                        "fromId": my_id
                                    }),
                                );
                            }
                        }
                    }
                }

                "ice_candidate" => {
                    if let Some(ref my_id) = current_peer_id {
                        if let Some(partner_id) = peers.get(my_id).and_then(|p| p.partner_id.clone()) {
                            if let Some(partner) = peers.get(&partner_id) {
                                send_payload(
                                    &partner.sender,
                                    &json!({
                                        "type": "ice_candidate",
                                        "candidate": data.get("candidate"),
                                        "fromId": my_id
                                    }),
                                );
                            }
                        }
                    }
                }

                "disconnect_session" => {
                    if let Some(ref my_id) = current_peer_id {
                        if let Some(partner_id) = peers.get(my_id).and_then(|p| p.partner_id.clone()) {
                            if let Some(mut partner) = peers.get_mut(&partner_id) {
                                send_payload(
                                    &partner.sender,
                                    &json!({
                                        "type": "session_ended",
                                        "message": "Remote party disconnected the session."
                                    }),
                                );
                                partner.partner_id = None;
                            }
                        }
                        if let Some(mut me) = peers.get_mut(my_id) {
                            me.partner_id = None;
                        }
                        broadcast_device_list(&peers);
                    }
                }

                // ── Mic / Location monitoring passthrough ──
                "mic_data" | "location_data" | "location_update" => {
                    // Forward to partner if paired
                    if let Some(ref my_id) = current_peer_id {
                        if let Some(partner_id) = peers.get(my_id).and_then(|p| p.partner_id.clone()) {
                            if let Some(partner) = peers.get(&partner_id) {
                                send_payload(&partner.sender, &data);
                            }
                        }
                    }
                }

                "ping" => {
                    send_payload(&tx, &json!({ "type": "pong" }));
                }

                other => {
                    warn!("Unknown message type: {}", other);
                }
            }
        } else if let Message::Close(_) = msg {
            break;
        }
    }

    // Clean up disconnected peer
    if let Some(ref my_id) = current_peer_id {
        if let Some((_, session)) = peers.remove(my_id) {
            if let Some(ref partner_id) = session.partner_id {
                if let Some(mut partner) = peers.get_mut(partner_id) {
                    send_payload(
                        &partner.sender,
                        &json!({
                            "type": "session_ended",
                            "message": "Device went offline."
                        }),
                    );
                    partner.partner_id = None;
                }
            }
            info!("Peer disconnected and cleaned up: {}", my_id);
            broadcast_device_list(&peers);
        }
    }

    forward_task.abort();
}
