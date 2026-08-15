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
  disconnectStomp();
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
      disconnectStomp();
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
  // Order matters: the first two fields are the ones shown in the compact
  // sidebar summary (see .profile-view CSS), the rest are kept in the DOM
  // but hidden there — still useful if this markup is ever expanded.
  const fields = [
    ["Display name", profile.displayName],
    ["Status", profile.status],
    ["User ID", profile.userId],
    ["Avatar URL", profile.avatarUrl],
    ["Timezone", profile.timezone],
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

  const avatar = document.getElementById("profile-avatar");
  const initialsSource = (profile.displayName || profile.userId || "?").trim();
  avatar.textContent = initialsSource ? initialsSource.slice(0, 1).toUpperCase() : "?";

  document.getElementById("profile-displayName").value = profile.displayName || "";
  document.getElementById("profile-avatarUrl").value = profile.avatarUrl || "";
  document.getElementById("profile-timezone").value = profile.timezone || "";
}

async function submitProfileForm(event) {
  event.preventDefault();
  clearError("profile-error");
  const submitBtn = event.target.querySelector('button[type="submit"]');
  const body = {
    displayName: document.getElementById("profile-displayName").value || null,
    avatarUrl: document.getElementById("profile-avatarUrl").value || null,
    timezone: document.getElementById("profile-timezone").value || null,
  };
  submitBtn.disabled = true;
  try {
    const profile = await apiFetch(`${CONFIG.profileServiceBaseUrl}/me`, {
      method: "PUT",
      body: JSON.stringify(body),
    });
    renderProfile(profile);
  } catch (err) {
    showError("profile-error", err);
  } finally {
    submitBtn.disabled = false;
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
    li.dataset.roomId = String(room.id);
    if (selectedRoom && String(selectedRoom.id) === String(room.id)) {
      li.classList.add("active");
    }

    const link = document.createElement("a");
    link.href = "#";
    link.textContent = room.name;
    link.addEventListener("click", (e) => {
      e.preventDefault();
      openRoom(room);
    });
    li.appendChild(link);

    const typeBadge = document.createElement("span");
    typeBadge.className = "badge";
    typeBadge.textContent = room.type;
    li.appendChild(typeBadge);

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
  const submitBtn = event.target.querySelector('button[type="submit"]');
  const name = document.getElementById("room-name").value;
  const type = document.getElementById("room-type").value;
  submitBtn.disabled = true;
  try {
    await apiFetch(`${CONFIG.chatServiceBaseUrl}/rooms`, {
      method: "POST",
      body: JSON.stringify({ name, type }),
    });
    document.getElementById("room-name").value = "";
    await loadRooms();
  } catch (err) {
    showError("rooms-error", err);
  } finally {
    submitBtn.disabled = false;
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
      document.getElementById("chat-empty-state").style.display = "flex";
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
  document.getElementById("chat-empty-state").style.display = "none";
  document.getElementById("messages-panel").style.display = "flex";
  document.getElementById("messages-room-name").textContent = room.name;
  document.getElementById("members-panel").style.display = "none";
  document.querySelectorAll("#room-list li").forEach((li) => {
    li.classList.toggle("active", li.dataset.roomId === String(room.id));
  });
  await Promise.all([loadMessages(), loadMembers()]);
  if (stompClient && stompClient.connected) {
    subscribeToRoomTopic(room.id);
  } else {
    connectStomp();
  }
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

function getCurrentUserId() {
  const tokens = getTokens();
  if (!tokens || !tokens.access_token) return null;
  try {
    return decodeJwtPayload(tokens.access_token).sub;
  } catch (e) {
    return null;
  }
}

function formatTimestamp(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
}

// messageId -> the <li> row, so live status-change events (CHAT-107) can
// update an already-rendered bubble in place instead of a full re-render.
const messageRowsByMessageId = new Map();

function buildMessageRow(message) {
  const currentUserId = getCurrentUserId();
  const isOwn = currentUserId != null && message.senderId === currentUserId;

  const row = document.createElement("li");
  row.className = `message-row ${isOwn ? "own" : "other"}`;
  row.dataset.messageId = String(message.id);

  const meta = document.createElement("div");
  meta.className = "message-meta";
  const sender = document.createElement("span");
  sender.className = "sender";
  sender.textContent = isOwn ? "You" : message.senderId;
  meta.appendChild(sender);
  meta.appendChild(document.createTextNode(` · ${formatTimestamp(message.createdAt)}`));

  const bubble = document.createElement("div");
  bubble.className = "message-bubble";
  bubble.textContent = message.content;

  row.appendChild(meta);
  row.appendChild(bubble);

  // Delivered/read status is only meaningful to show on your own messages -
  // a nice-to-have visual per CHAT-107, not required.
  if (isOwn) {
    const status = document.createElement("span");
    status.className = "message-status";
    status.textContent = statusLabel(message.status);
    meta.appendChild(status);
  }

  return row;
}

function statusLabel(status) {
  if (status === "READ") return "Read";
  if (status === "DELIVERED") return "Delivered";
  return "Sent";
}

function renderMessages(messages) {
  const list = document.getElementById("message-list");
  list.innerHTML = "";
  messageRowsByMessageId.clear();
  // API returns newest-first; show oldest-first like a normal chat transcript.
  const ordered = [...messages].reverse();
  for (const message of ordered) {
    const row = buildMessageRow(message);
    messageRowsByMessageId.set(String(message.id), row);
    list.appendChild(row);
  }
  list.scrollTop = list.scrollHeight;
  maybeSendReadReceipt(ordered[ordered.length - 1]);
}

// ---------------------------------------------------------------------------
// Real-time delivery (CHAT-107) - STOMP over WebSocket via ws-gateway
// ---------------------------------------------------------------------------

let stompClient = null;
let currentRoomSubscription = null;

// Connects once per login session; reused across room switches (only the
// topic subscription changes, per solution-architecture.md - clients
// SUBSCRIBE to /topic/rooms.{roomId} for each conversation they open).
function connectStomp() {
  const tokens = getTokens();
  if (!tokens || !tokens.access_token || stompClient) return;

  stompClient = new StompJs.Client({
    brokerURL: CONFIG.wsGatewayUrl,
    connectHeaders: { Authorization: `Bearer ${tokens.access_token}` },
    reconnectDelay: 5000,
    onConnect: () => {
      if (selectedRoom) subscribeToRoomTopic(selectedRoom.id);
    },
    onStompError: (frame) => {
      showError("messages-error", new Error(frame.headers?.message || "Real-time connection error"));
    },
  });
  stompClient.activate();
}

function disconnectStomp() {
  if (stompClient) {
    stompClient.deactivate();
  }
  stompClient = null;
  currentRoomSubscription = null;
  messageRowsByMessageId.clear();
}

function subscribeToRoomTopic(roomId) {
  if (!stompClient || !stompClient.connected) return;
  if (currentRoomSubscription) {
    currentRoomSubscription.unsubscribe();
  }
  currentRoomSubscription = stompClient.subscribe(`/topic/rooms.${roomId}`, (frame) => {
    handleIncomingRoomEvent(JSON.parse(frame.body));
  });
}

// A room's topic carries two event shapes - a new message (has `content`)
// or a delivered/read status change (has `status`) - see
// solution-architecture.md "Real-time delivery - CHAT-107 implementation
// decisions".
function handleIncomingRoomEvent(event) {
  if (!selectedRoom || String(event.roomId) !== String(selectedRoom.id)) return;
  if (event.content !== undefined) {
    appendLiveMessage(event);
  } else if (event.status !== undefined) {
    updateMessageStatusBadge(event.messageId, event.status);
  }
}

function appendLiveMessage(event) {
  // The sender also SUBSCRIBEs to the room it just posted to (so it can see
  // live status ticks on its own messages), so it receives its own message
  // back as a live echo in addition to the manual reload the post-message
  // form already does. Guard against rendering the same messageId twice
  // regardless of which path (reload vs. live push) wins the race.
  if (messageRowsByMessageId.has(String(event.messageId))) return;

  const list = document.getElementById("message-list");
  const message = {
    id: event.messageId,
    senderId: event.senderId,
    content: event.content,
    createdAt: event.createdAt,
    status: "SENT",
  };
  const row = buildMessageRow(message);
  messageRowsByMessageId.set(String(message.id), row);
  list.appendChild(row);
  list.scrollTop = list.scrollHeight;
  maybeSendReadReceipt(message);
}

function updateMessageStatusBadge(messageId, status) {
  const row = messageRowsByMessageId.get(String(messageId));
  if (!row) return;
  const statusEl = row.querySelector(".message-status");
  if (statusEl) statusEl.textContent = statusLabel(status);
}

// Nice-to-have demonstration of the read side of CHAT-107: if the most
// recent message in the open room wasn't sent by us, tell ws-gateway we've
// "read" up to it. Real read-tracking (scroll/focus based) is out of scope
// for this throwaway dev tool.
function maybeSendReadReceipt(lastMessage) {
  if (!lastMessage || !selectedRoom || !stompClient || !stompClient.connected) return;
  const currentUserId = getCurrentUserId();
  if (!currentUserId || lastMessage.senderId === currentUserId) return;
  stompClient.publish({
    destination: "/app/message.read",
    body: JSON.stringify({ roomId: selectedRoom.id, lastReadMessageId: lastMessage.id }),
  });
}

// ---------------------------------------------------------------------------
// Room members panel
// ---------------------------------------------------------------------------

async function loadMembers() {
  if (!selectedRoom) return;
  clearError("members-error");
  try {
    const members = await apiFetch(`${CONFIG.chatServiceBaseUrl}/rooms/${selectedRoom.id}/members`);
    renderMembers(members);
  } catch (err) {
    showError("members-error", err);
  }
}

function renderMembers(members) {
  const list = document.getElementById("member-list");
  list.innerHTML = "";
  const currentUserId = getCurrentUserId();
  for (const member of members) {
    const li = document.createElement("li");

    const avatar = document.createElement("span");
    avatar.className = "member-avatar";
    avatar.textContent = member.userId ? member.userId.slice(0, 1).toUpperCase() : "?";
    li.appendChild(avatar);

    const userId = document.createElement("span");
    userId.className = "member-userid";
    userId.textContent = member.userId === currentUserId ? `${member.userId} (you)` : member.userId;
    userId.title = member.userId;
    li.appendChild(userId);

    const roleBadge = document.createElement("span");
    roleBadge.className = `member-role-badge ${member.role === "OWNER" ? "owner" : "member"}`;
    roleBadge.textContent = member.role;
    li.appendChild(roleBadge);

    list.appendChild(li);
  }

  document.getElementById("toggle-members-btn").textContent = `Members (${members.length})`;
}

function toggleMembersPanel() {
  const panel = document.getElementById("members-panel");
  panel.style.display = panel.style.display === "none" ? "block" : "none";
}

// ---------------------------------------------------------------------------
// Messages panel (post form)
// ---------------------------------------------------------------------------

async function submitPostMessageForm(event) {
  event.preventDefault();
  clearError("messages-error");
  const submitBtn = event.target.querySelector('button[type="submit"]');
  const input = document.getElementById("message-content");
  submitBtn.disabled = true;
  try {
    await apiFetch(`${CONFIG.chatServiceBaseUrl}/rooms/${selectedRoom.id}/messages`, {
      method: "POST",
      body: JSON.stringify({ content: input.value }),
    });
    input.value = "";
    await loadMessages();
  } catch (err) {
    showError("messages-error", err);
  } finally {
    submitBtn.disabled = false;
  }
}

// ---------------------------------------------------------------------------
// Top-level render / wiring
// ---------------------------------------------------------------------------

function render() {
  const tokens = getTokens();
  const app = document.getElementById("app");
  const loginHero = document.getElementById("login-hero");
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
    status.textContent = username;
    loginBtn.style.display = "none";
    logoutBtn.style.display = "inline-block";
    loginHero.style.display = "none";
    app.style.display = "flex";
    loadProfile();
    loadRooms();
    connectStomp();
  } else {
    status.textContent = "";
    loginBtn.style.display = "inline-block";
    logoutBtn.style.display = "none";
    loginHero.style.display = "flex";
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
  document.getElementById("toggle-members-btn").addEventListener("click", toggleMembersPanel);

  await handleCallbackIfPresent();
  render();
}

init();
