const SETTINGS_KEY = "tesla-lab-settings";
const STATE_KEY = "tesla-lab-references";
const MAX_ACTIVITY = 40;
const sessionSettings = readSession(SETTINGS_KEY, {});
const references = readSession(STATE_KEY, { accounts: [], cards: [], transactions: [] });
let toastTimeout;

const byId = (id) => document.getElementById(id);
const makeKey = () => `ui-${crypto.randomUUID()}`;
const settings = {
  baseUrl: byId("base-url"),
  apiKey: byId("api-key")
};
settings.baseUrl.value = sessionSettings.baseUrl || window.location.origin;
settings.apiKey.value = sessionSettings.apiKey || "";
byId("test-run-id").value = `run-${Date.now().toString(36)}-${crypto.randomUUID().slice(0, 8)}`;
renderReferenceOptions();

byId("save-settings").addEventListener("click", () => {
  const baseUrl = settings.baseUrl.value.trim().replace(/\/+$/, "");
  if (!/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/i.test(baseUrl)) {
    showToast("For local safety, use a localhost API URL.");
    return;
  }
  settings.baseUrl.value = baseUrl;
  sessionStorage.setItem(SETTINGS_KEY, JSON.stringify({
    baseUrl,
    apiKey: settings.apiKey.value
  }));
  setConnectionState("unchecked", "API not checked");
  showToast("Connection settings saved for this browser tab.");
});
byId("api-origin").textContent = "API not checked";

byId("apply-test-run").addEventListener("click", () => {
  const runId = byId("test-run-id").value.trim().replace(/[^A-Za-z0-9_-]/g, "-");
  if (!runId) {
    showToast("Enter a run ID first.");
    return;
  }
  byId("test-run-id").value = runId;
  byId("application-form").elements.customerReference.value = `${runId}-customer`;
  byId("transaction-form").elements.merchantReference.value = `${runId}-merchant`;
  showToast("Unique customer and merchant references applied.");
});

byId("application-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  await withBusy(form, async () => {
    const values = formValues(form);
    setResultPending("application-result");
    const result = await apiRequest("POST", "/api/applications", {
      ...values,
      requestedCreditLimit: Number(values.requestedCreditLimit),
      idempotencyKey: makeKey()
    });
    if (!result.ok) {
      showResult("application-result", result, "Application failed");
      return;
    }
    const application = result.data;
    if (application.accountReference) {
      remember("accounts", application.accountReference);
      byId("issue-form").elements.accountReference.value = application.accountReference;
      byId("lookup-account-reference").value = application.accountReference;
    }
    showResult("application-result", result, application.status === "APPROVED" ? "Application approved" : "Application declined");
  });
});

byId("issue-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  await withBusy(form, async () => {
    const values = formValues(form);
    const accountReference = values.accountReference.trim();
    setResultPending("card-result");
    const result = await apiRequest("POST", `/api/accounts/${encodeURIComponent(accountReference)}/cards`, {
      network: values.network,
      idempotencyKey: makeKey(),
      dailyLimit: Number(values.dailyLimit)
    });
    if (result.ok) {
      remember("cards", result.data.cardReference);
      byId("control-card-reference").value = result.data.cardReference;
      byId("transaction-form").elements.cardReference.value = result.data.cardReference;
      showToast(`Card issued: ${result.data.cardReference}`);
    }
    showResult("card-result", result, result.ok ? "Card issued" : "Card issuance failed");
  });
});

byId("transaction-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  await withBusy(form, async () => {
    const values = formValues(form);
    setResultPending("transaction-result");
    const result = await apiRequest("POST", "/api/transactions/initiate", {
      ...values,
      amount: Number(values.amount),
      idempotencyKey: makeKey()
    });
    if (result.ok) {
      remember("transactions", result.data.transactionReference);
      byId("stage-transaction-reference").value = result.data.transactionReference;
    }
    showResult("transaction-result", result, result.ok ? "Transaction initiated" : "Transaction failed");
  });
});

document.querySelectorAll("[data-card-action]").forEach((button) => {
  button.addEventListener("click", async () => {
    const cardReference = byId("control-card-reference").value.trim();
    if (!cardReference) return showToast("Enter a card reference first.");
    const action = button.dataset.cardAction;
    let method = "POST";
    let path = `/api/cards/${encodeURIComponent(cardReference)}/${action}`;
    let body;
    if (action === "details") {
      method = "GET";
      path = `/api/cards/${encodeURIComponent(cardReference)}`;
    } else if (action === "limits") {
      method = "PUT";
      path = `/api/cards/${encodeURIComponent(cardReference)}/limits`;
      body = { dailyLimit: Number(byId("control-daily-limit").value) };
    } else if (action === "pin-change") {
      body = { securePinChangeReference: byId("pin-reference").value.trim() };
    } else if (action === "replace") {
      const issueForm = byId("issue-form");
      body = {
        network: issueForm.elements.network.value,
        idempotencyKey: makeKey(),
        dailyLimit: Number(byId("control-daily-limit").value)
      };
    }
    await withBusy(button, async () => {
      setResultPending("card-result");
      const result = await apiRequest(method, path, body);
      if (result.ok && action === "replace") {
        remember("cards", result.data.cardReference);
        byId("control-card-reference").value = result.data.cardReference;
        byId("transaction-form").elements.cardReference.value = result.data.cardReference;
      }
      showResult("card-result", result, result.ok ? `Card ${action}` : `Card ${action} failed`);
    });
  });
});

document.querySelectorAll("[data-txn-action]").forEach((button) => {
  button.addEventListener("click", async () => {
    const transactionReference = byId("stage-transaction-reference").value.trim();
    if (!transactionReference) return showToast("Enter a transaction reference first.");
    const action = button.dataset.txnAction;
    let path = `/api/transactions/${encodeURIComponent(transactionReference)}/${action}`;
    let method = "POST";
    let body;
    if (action === "details") {
      path = `/api/transactions/${encodeURIComponent(transactionReference)}`;
      method = "GET";
    } else if (action === "clear") {
      body = { amount: Number(byId("event-amount").value) };
    } else if (["refund", "chargeback", "fees", "adjustments"].includes(action)) {
      body = {
        amount: Number(byId("event-amount").value),
        reason: byId("event-reason").value.trim(),
        idempotencyKey: makeKey()
      };
    } else if (action === "disputes") {
      body = { reason: byId("event-reason").value.trim(), idempotencyKey: makeKey() };
    }
    await withBusy(button, async () => {
      setResultPending("transaction-stage-result");
      const result = await apiRequest(method, path, body);
      showResult("transaction-stage-result", result, result.ok ? `Transaction ${action}` : `Transaction ${action} failed`);
    });
  });
});

document.querySelectorAll("[data-account-action]").forEach((button) => {
  button.addEventListener("click", async () => {
    const accountReference = byId("lookup-account-reference").value.trim();
    if (!accountReference) return showToast("Enter an account reference first.");
    const action = button.dataset.accountAction;
    const suffix = action === "account" ? "" : action === "transactions" ? "/transactions" : "/reconciliation";
    await withBusy(button, async () => {
      setResultPending("account-result");
      const result = await apiRequest("GET", `/api/accounts/${encodeURIComponent(accountReference)}${suffix}`);
      showResult("account-result", result, result.ok ? actionLabel(action) : "Account request failed");
    });
  });
});

byId("clear-log").addEventListener("click", () => {
  byId("activity-log").replaceChildren();
  const empty = document.createElement("div");
  empty.className = "empty-state";
  empty.innerHTML = "<span>⌁</span><p>Activity cleared.</p><small>New API calls will appear here.</small>";
  byId("activity-log").append(empty);
});

async function apiRequest(method, path, body) {
  const baseUrl = settings.baseUrl.value.trim().replace(/\/+$/, "");
  const apiKey = settings.apiKey.value.trim();
  if (!/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/i.test(baseUrl)) {
    setConnectionState("needs-configuration", "Use a localhost API URL");
    showToast("For local safety, use a localhost API URL.");
    return { ok: false, status: 0, data: { error: "API base URL must use localhost or 127.0.0.1." } };
  }
  if (!apiKey) {
    setConnectionState("needs-configuration", "API key required");
    showToast("Enter your Tesla API key in Connection settings.");
    return { ok: false, status: 0, data: { error: "API key is required." } };
  }
  setConnectionState("checking", "Request in progress");
  const headers = { "X-Tesla-API-Key": apiKey, "Accept": "application/json" };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  let response;
  let data;
  try {
    response = await fetch(`${baseUrl}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    const text = await response.text();
    try { data = text ? JSON.parse(text) : {}; } catch { data = { response: text }; }
  } catch (error) {
    data = { error: `Could not connect to the API: ${error.message}` };
    setConnectionState("unreachable", "API unavailable");
    addActivity(method, path, 0, data, body);
    return { ok: false, status: 0, data };
  }
  addActivity(method, path, response.status, data, body);
  if (response.status === 401) {
    data.error = "Unauthorized. Check the API key. The default local key is local-development-only.";
    setConnectionState("unauthorized", "API reachable · key rejected");
  } else {
    setConnectionState(response.ok ? "ready" : "reachable-with-error",
      response.ok ? "API reachable" : `API reachable · HTTP ${response.status}`);
  }
  return { ok: response.ok, status: response.status, data };
}

function showResult(targetId, result, title) {
  const container = byId(targetId);
  container.dataset.state = result.ok ? "success" : "error";
  container.dataset.httpStatus = String(result.status);
  container.replaceChildren();
  const card = document.createElement("div");
  card.className = `result-card${result.ok ? "" : " is-error"}`;
  card.dataset.testid = "result-card";
  const heading = document.createElement("div");
  heading.className = "result-title";
  const label = document.createElement("span");
  label.textContent = title;
  const status = document.createElement("span");
  status.textContent = result.status ? `HTTP ${result.status}` : "CONNECTION ERROR";
  status.dataset.testid = "result-http-status";
  status.dataset.httpStatus = String(result.status);
  heading.append(label, status);
  const payload = document.createElement("pre");
  payload.textContent = JSON.stringify(result.data, null, 2);
  payload.dataset.testid = "result-response";
  card.append(heading, payload);
  container.append(card);
}

function setResultPending(targetId) {
  const container = byId(targetId);
  container.dataset.state = "loading";
  container.removeAttribute("data-http-status");
  container.replaceChildren();
  const pending = document.createElement("div");
  pending.className = "result-pending";
  pending.textContent = "Request in progress…";
  pending.dataset.testid = "result-pending";
  container.append(pending);
}

function setConnectionState(state, message) {
  const connection = byId("api-connection");
  connection.dataset.state = state;
  connection.title = message;
  byId("api-origin").textContent = message;
}

async function withBusy(element, operation) {
  const submitButton = element instanceof HTMLFormElement
    ? element.querySelector('button[type="submit"]')
    : element;
  const wasDisabled = submitButton ? submitButton.disabled : false;
  element.setAttribute("aria-busy", "true");
  element.dataset.state = "loading";
  if (submitButton) submitButton.disabled = true;
  try {
    return await operation();
  } finally {
    element.setAttribute("aria-busy", "false");
    element.dataset.state = "complete";
    if (submitButton) submitButton.disabled = wasDisabled;
  }
}

function addActivity(method, path, status, data, requestBody) {
  const log = byId("activity-log");
  if (log.querySelector(".empty-state")) log.replaceChildren();
  const entry = document.createElement("div");
  entry.className = "activity-entry";
  entry.dataset.testid = "activity-entry";
  entry.dataset.state = status >= 200 && status < 300 ? "success" : "error";
  const methodLabel = document.createElement("span");
  methodLabel.className = "activity-method";
  methodLabel.textContent = method;
  const info = document.createElement("div");
  info.className = "activity-info";
  const url = document.createElement("strong");
  url.textContent = path;
  const payload = document.createElement("pre");
  payload.textContent = JSON.stringify({ request: requestBody ?? null, response: data }, null, 2);
  info.append(url, payload);
  const statusLabel = document.createElement("span");
  statusLabel.className = `activity-status${status < 200 || status >= 300 ? " error" : ""}`;
  statusLabel.textContent = status || "ERROR";
  statusLabel.dataset.httpStatus = String(status);
  entry.append(methodLabel, info, statusLabel);
  log.prepend(entry);
  while (log.children.length > MAX_ACTIVITY) log.lastElementChild.remove();
}

function remember(type, value) {
  if (value && !references[type].includes(value)) references[type].unshift(value);
  references[type] = references[type].slice(0, 30);
  sessionStorage.setItem(STATE_KEY, JSON.stringify(references));
  renderReferenceOptions();
}

function renderReferenceOptions() {
  const options = [
    ["account-options", references.accounts],
    ["card-options", references.cards],
    ["transaction-options", references.transactions]
  ];
  options.forEach(([id, values]) => {
    const list = byId(id);
    list.replaceChildren(...values.map((value) => {
      const option = document.createElement("option");
      option.value = value;
      return option;
    }));
  });
}

function readSession(key, fallback) {
  try {
    const value = sessionStorage.getItem(key);
    return value ? { ...fallback, ...JSON.parse(value) } : fallback;
  } catch {
    return fallback;
  }
}

function formValues(form) {
  return Object.fromEntries(new FormData(form).entries());
}

function actionLabel(action) {
  return action === "transactions" ? "Transaction history" : action === "reconciliation" ? "Reconciliation" : "Account summary";
}

function showToast(message) {
  const toast = byId("toast");
  toast.textContent = message;
  toast.classList.add("show");
  clearTimeout(toastTimeout);
  toastTimeout = setTimeout(() => toast.classList.remove("show"), 3200);
}
