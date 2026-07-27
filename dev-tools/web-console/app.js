// Chattera dev console — throwaway dev tool, not a product frontend.
// Plain vanilla JS, no build step. See README.md for how to run it.

// ---------------------------------------------------------------------------
// PKCE helpers (Authorization Code + PKCE, RFC 7636)
// ---------------------------------------------------------------------------

function base64UrlEncode(bytes) {
  let binary = "";
  for (const b of new Uint8Array(bytes)) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function randomString(length) {
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  return base64UrlEncode(bytes);
}

async function sha256(text) {
  const data = new TextEncoder().encode(text);
  return crypto.subtle.digest("SHA-256", data);
}

// ---------------------------------------------------------------------------
// Token storage (sessionStorage — cleared when the tab closes)
// ---------------------------------------------------------------------------

const TOKEN_KEY = "chattera_dev_console_tokens";

function saveTokens(tokenResponse) {
  sessionStorage.setItem(TOKEN_KEY, JSON.stringify(tokenResponse));
}

function getTokens() {
  const raw = sessionStorage.getItem(TOKEN_KEY);
  return raw ? JSON.parse(raw) : null;
}

function clearTokens() {
  sessionStorage.removeItem(TOKEN_KEY);
}

function decodeJwtPayload(jwt) {
  const payload = jwt.split(".")[1];
  const normalized = payload.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "===".slice((normalized.length + 3) % 4);
  return JSON.parse(atob(padded));
}

// ---------------------------------------------------------------------------
// Login / callback / logout
// ---------------------------------------------------------------------------

async function startLogin() {
  const codeVerifier = randomString(64);
  const state = randomString(16);
  const challengeBytes = await sha256(codeVerifier);
  const codeChallenge = base64UrlEncode(challengeBytes);

  sessionStorage.setItem("pkce_code_verifier", codeVerifier);
  sessionStorage.setItem("pkce_state", state);

  const authUrl = new URL(`${CONFIG.keycloakBaseUrl}/realms/${CONFIG.realm}/protocol/openid-connect/auth`);
  authUrl.searchParams.set("client_id", CONFIG.clientId);
  authUrl.searchParams.set("response_type", "code");
  authUrl.searchParams.set("redirect_uri", CONFIG.redirectUri);
  authUrl.searchParams.set("scope", "openid");
  authUrl.searchParams.set("code_challenge", codeChallenge);
  authUrl.searchParams.set("code_challenge_method", "S256");
  authUrl.searchParams.set("state", state);

  window.location.href = authUrl.toString();
}

async function handleCallbackIfPresent() {
  const params = new URLSearchParams(window.location.search);
  const code = params.get("code");
  if (!code) return;

  const expectedState = sessionStorage.getItem("pkce_state");
  const returnedState = params.get("state");
  const codeVerifier = sessionStorage.getItem("pkce_code_verifier");

  // Clean the code/state out of the URL regardless of outcome so a page
  // refresh doesn't try to redeem the (single-use) code again.
  window.history.replaceState({}, document.title, window.location.pathname);

  if (!codeVerifier || !expectedState || expectedState !== returnedState) {
    showGlobalError("Login callback failed: missing or mismatched PKCE state. Please log in again.");
    return;
  }

  const body = new URLSearchParams();
  body.set("grant_type", "authorization_code");
  body.set("client_id", CONFIG.clientId);
  body.set("code", code);
  body.set("redirect_uri", CONFIG.redirectUri);
  body.set("code_verifier", codeVerifier);

  const response = await fetch(`${CONFIG.keycloakBaseUrl}/realms/${CONFIG.realm}/protocol/openid-connect/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: body.toString(),
  });

  sessionStorage.removeItem("pkce_code_verifier");
  sessionStorage.removeItem("pkce_state");

  const payload = await response.json().catch(() => null);
  if (!response.ok) {
    showGlobalError(`Token exchange failed: ${response.status} ${JSON.stringify(payload)}`);
    return;
  }

  saveTokens(payload);
}

function logout() {
  clearTokens();
  render();
}

// ---------------------------------------------------------------------------
// API helper
// ---------------------------------------------------------------------------

async function apiFetch(url, options = {}) {
  const tokens = getTokens();
  const headers = Object.assign({}, options.headers, {
    Authorization: `Bearer ${tokens.access_token}`,
  });
  if (options.body) headers["Content-Type"] = "application/json";

  const response = await fetch(url, Object.assign({}, options, { headers }));

  if (response.status === 204) return null;

  const text = await response.text();
  const parsed = text ? JSON.parse(text) : null;

  if (!response.ok) {
    if (response.status === 401) {
      clearTokens();
      render();
    }
    const detail = parsed ? (parsed.message || JSON.stringify(parsed)) : response.statusText;
    throw new Error(`${response.status} ${detail}`);
  }

  return parsed;
}

function showError(elementId, error) {
  const el = document.getElementById(elementId);
  el.textContent = error instanceof Error ? error.message : String(error);
  el.style.display = "block";
}

function clearError(elementId) {
  const el = document.getElementById(elementId);
  el.style.display = "none";
  el.textContent = "";
}

function showGlobalError(message) {
  showError("global-error", message);
}

// ---------------------------------------------------------------------------
// Profile panel
// ---------------------------------------------------------------------------

async function loadProfile() {
  clearError("profile-error");
  try {
    const profile = await apiFetch(`${CONFIG.profileServiceBaseUrl}/me`);
    renderProfile(profile);
  } catch (err) {
    showError("profile-error", err);
  }
}

function renderProfile(profile) {
  const dl = document.getElementById("profile-view");
  dl.innerHTML = "";
  const fields = [
    ["User ID", profile.userId],
    ["Display name", profile.displayName],
    ["Avatar URL", profile.avatarUrl],
    ["Timezone", profile.timezone],
    ["Status", profile.status],
    ["Created at", profile.createdAt],
  ];
  for (const [label, value] of fields) {
    const dt = document.createElement("dt");
    dt.textContent = label;
    const dd = document.createElement("dd");
    dd.textContent = value == null ? "" : value;
    dl.appendChild(dt);
    dl.appendChild(dd);
  }

  document.getElementById("profile-displayName").value = profile.displayName || "";
  document.getElementById("profile-avatarUrl").value = profile.avatarUrl || "";
  document.getElementById("profile-timezone").value = profile.timezone || "";
}

async function submitProfileForm(event) {
  event.preventDefault();
  clearError("profile-error");
  const body = {
    displayName: document.getElementById("profile-displayName").value || null,
    avatarUrl: document.getElementById("profile-avatarUrl").value || null,
    timezone: document.getElementById("profile-timezone").value || null,
  };
  try {
    const profile = await apiFetch(`${CONFIG.profileServiceBaseUrl}/me`, {
      method: "PUT",
      body: JSON.stringify(body),
    });
    renderProfile(profile);
  } catch (err) {
    showError("profile-error", err);
  }
}

// ---------------------------------------------------------------------------
// Rooms panel
// ---------------------------------------------------------------------------

let selectedRoom = null;

async function loadRooms() {
  clearError("rooms-error");
  try {
    const rooms = await apiFetch(`${CONFIG.chatServiceBaseUrl}/rooms`);
    renderRooms(rooms);
  } catch (err) {
    showError("rooms-error", err);
  }
}

function renderRooms(rooms) {
  const list = document.getElementById("room-list");
  list.innerHTML = "";
  for (const room of rooms) {
    const li = document.createElement("li");

    const link = document.createElement("a");
    link.href = "#";
    link.textContent = `${room.name} (${room.type})`;
    link.addEventListener("click", (e) => {
      e.preventDefault();
      openRoom(room);
    });
    li.appendChild(link);

    const badge = document.createElement("span");
    badge.className = "badge";
    badge.textContent = room.member ? `member (${room.role})` : "not a member";
    li.appendChild(badge);

    const actionBtn = document.createElement("button");
    actionBtn.textContent = room.member ? "Leave" : "Join";
    actionBtn.addEventListener("click", () => (room.member ? leaveRoom(room.id) : joinRoom(room.id)));
    li.appendChild(actionBtn);

    list.appendChild(li);
  }
}

async function submitCreateRoomForm(event) {
  event.preventDefault();
  clearError("rooms-error");
  const name = document.getElementById("room-name").value;
  const type = document.getElementById("room-type").value;
  try {
    await apiFetch(`${CONFIG.chatServiceBaseUrl}/rooms`, {
      method: "POST",
      body: JSON.stringify({ name, type }),
    });
    document.getElementById("room-name").value = "";
    await loadRooms();
  } catch (err) {
    showError("rooms-error", err);
  }
}

async function joinRoom(roomId) {
  clearError("rooms-error");
  try {
    await apiFetch(`${CONFIG.chatServiceBaseUrl}/rooms/${roomId}/join`, { method: "POST" });
    await loadRooms();
  } catch (err) {
    showError("rooms-error", err);
  }
}

async function leaveRoom(roomId) {
  clearError("rooms-error");
  try {
    await apiFetch(`${CONFIG.chatServiceBaseUrl}/rooms/${roomId}/leave`, { method: "POST" });
    await loadRooms();
    if (selectedRoom && selectedRoom.id === roomId) {
      selectedRoom = null;
      document.getElementById("messages-panel").style.display = "none";
    }
  } catch (err) {
    showError("rooms-error", err);
  }
}

// ---------------------------------------------------------------------------
// Messages panel
// ---------------------------------------------------------------------------

async function openRoom(room) {
  selectedRoom = room;
  document.getElementById("messages-panel").style.display = "block";
  document.getElementById("messages-room-name").textContent = room.name;
  await loadMessages();
}

async function loadMessages() {
  if (!selectedRoom) return;
  clearError("messages-error");
  try {
    const history = await apiFetch(`${CONFIG.chatServiceBaseUrl}/rooms/${selectedRoom.id}/messages`);
    renderMessages(history.messages);
  } catch (err) {
    showError("messages-error", err);
  }
}

function renderMessages(messages) {
  const list = document.getElementById("message-list");
  list.innerHTML = "";
  // API returns newest-first; show oldest-first like a normal chat transcript.
  const ordered = [...messages].reverse();
  for (const message of ordered) {
    const li = document.createElement("li");
    const meta = document.createElement("span");
    meta.className = "message-meta";
    meta.textContent = `[${message.createdAt}] ${message.senderId}: `;
    const content = document.createElement("span");
    content.textContent = message.content;
    li.appendChild(meta);
    li.appendChild(content);
    list.appendChild(li);
  }
}

async function submitPostMessageForm(event) {
  event.preventDefault();
  clearError("messages-error");
  const input = document.getElementById("message-content");
  try {
    await apiFetch(`${CONFIG.chatServiceBaseUrl}/rooms/${selectedRoom.id}/messages`, {
      method: "POST",
      body: JSON.stringify({ content: input.value }),
    });
    input.value = "";
    await loadMessages();
  } catch (err) {
    showError("messages-error", err);
  }
}

// ---------------------------------------------------------------------------
// Top-level render / wiring
// ---------------------------------------------------------------------------

function render() {
  const tokens = getTokens();
  const app = document.getElementById("app");
  const status = document.getElementById("auth-status");
  const loginBtn = document.getElementById("login-btn");
  const logoutBtn = document.getElementById("logout-btn");

  if (tokens && tokens.access_token) {
    let username = "(unknown)";
    try {
      username = decodeJwtPayload(tokens.access_token).preferred_username || username;
    } catch (e) {
      // ignore decode failure, keep placeholder
    }
    status.textContent = `Logged in as ${username}`;
    loginBtn.style.display = "none";
    logoutBtn.style.display = "inline-block";
    app.style.display = "block";
    loadProfile();
    loadRooms();
  } else {
    status.textContent = "Logged out";
    loginBtn.style.display = "inline-block";
    logoutBtn.style.display = "none";
    app.style.display = "none";
  }
}

async function init() {
  document.getElementById("login-btn").addEventListener("click", startLogin);
  document.getElementById("logout-btn").addEventListener("click", logout);
  document.getElementById("profile-form").addEventListener("submit", submitProfileForm);
  document.getElementById("create-room-form").addEventListener("submit", submitCreateRoomForm);
  document.getElementById("refresh-rooms-btn").addEventListener("click", loadRooms);
  document.getElementById("post-message-form").addEventListener("submit", submitPostMessageForm);

  await handleCallbackIfPresent();
  render();
}

init();
