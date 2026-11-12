# TeamAccess Signaling Server 🦀⚡

Ultra-low latency WebRTC Signaling & Rendezvous Server for **TeamAccess Remote**, powered by asynchronous Rust (`Tokio` + `Axum` + `DashMap`).

[![Deploy to Render](https://render.com/images/deploy-to-render-button.svg)](https://render.com)

---

## 🚀 Features
- **Pure Rust Engine:** Built on Tokio async runtime with Axum WebSocket handling.
- **Ultra-low Memory Footprint:** Consumes less than 15 MB RAM under load.
- **Microsecond Latency:** Lock-free concurrent session matching using `DashMap`.
- **Render Ready:** Includes multi-stage Alpine Dockerfile and `render.yaml` blueprint.
- **Built-in Dashboard:** Web status dashboard served at `/`.

---

## 📡 Endpoints
* `GET /` - Web dashboard
* `GET /health` - Health check endpoint (for keep-alive pings)
* `GET /api/status` - JSON status of active peers and server uptime
* `GET /ws` - WebSocket signaling endpoint (also supports root `/`)

---

## 🛠️ Deploy on Render

1. Create a New **Web Service** on [Render](https://dashboard.render.com).
2. Connect your GitHub repository (`teamaccess-serve`).
3. Set **Runtime** to `Docker`.
4. Render will automatically detect the multi-stage `Dockerfile` and deploy the Rust server with free SSL (`https://...` and `wss://...`).
