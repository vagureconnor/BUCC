const notice = document.querySelector("#notice");
const totalCountValue = document.querySelector("#total-count-value");
const dailyCountValue = document.querySelector("#daily-count-value");
const countUpdated = document.querySelector("#count-updated");
const progressRing = document.querySelector("#progress-ring");
const refreshCountButton = document.querySelector("#refresh-count-button");
const checkinButton = document.querySelector("#checkin-button");
const checkinThreeButton = document.querySelector("#checkin-three-button");
const checkinTimer = document.querySelector("#checkin-timer");
const windowStatus = document.querySelector("#window-status");
const accountStudentId = document.querySelector("#account-student-id");
const accountForm = document.querySelector("#account-settings-form");
const studentIdInput = document.querySelector("#student-id-input");
const campusInput = document.querySelector("#campus-input");
const healthcloudPasswordInput = document.querySelector("#healthcloud-password-input");
const healthcloudPasswordStatus = document.querySelector("#healthcloud-password-status");
const saveAccountButton = document.querySelector("#save-account-button");
let checkinInProgress = false;
let checkinRoundsActive = 1;
let initialSetupShown = false;
let settingsDirty = false;
let healthcloudPasswordConfigured = false;

function setNotice(message, type = "") {
  notice.textContent = message;
  notice.className = `notice ${type}`.trim();
}

async function requestJson(url, options = {}) {
  const response = await fetch(url, {
    ...options,
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.error || `请求失败：${response.status}`);
  return payload;
}

function beijingTime() {
  return new Intl.DateTimeFormat("en-GB", {
    timeZone: "Asia/Shanghai", hour: "2-digit", minute: "2-digit", hour12: false,
  }).format(new Date());
}

function toMinutes(value) {
  const [hours, minutes] = value.split(":").map(Number);
  return hours * 60 + minutes;
}

function renderWindowStatus(windows) {
  const time = beijingTime();
  const now = toMinutes(time);
  const ranges = windows.map((window) => window.split("-"));
  const active = ranges.some(([start, end]) => now >= toMinutes(start) && now <= toMinutes(end));
  const next = ranges.find(([start]) => now < toMinutes(start));
  const nextLabel = next ? next[0] : ranges[0][0];
  windowStatus.textContent = active
    ? `北京时间 ${time} · 当前处于打卡时段`
    : `北京时间 ${time} · 最近时段 ${next ? `今天 ${nextLabel}` : `明天 ${nextLabel}`}`;
  document.querySelector("#check-windows").textContent = windows.join("、");
  const helpWindows = document.querySelector("#help-windows");
  helpWindows.replaceChildren(...windows.map((window) => {
    const item = document.createElement("li");
    item.textContent = window.replace("-", " 至 ");
    return item;
  }));
}

function renderState(state) {
  const user = state.users[0];
  const todayCount = user?.today?.completed_rounds;
  const totalCount = user?.total_count;
  accountStudentId.textContent = user?.student_id || "未设置学号";
  if (!settingsDirty) {
    studentIdInput.value = user?.student_id || "";
    campusInput.value = user?.campus || "学院路";
  }
  totalCountValue.textContent = totalCount ?? "--";
  dailyCountValue.textContent = todayCount ?? "--";
  const totalProgress = Math.max(0, Math.min(32, Number(totalCount || 0)));
  progressRing.style.setProperty("--progress", `${(totalProgress / 32) * 100}%`);
  progressRing.setAttribute("aria-valuenow", String(totalProgress));
  countUpdated.textContent = user?.total_count_updated_at
    ? `健康云流水更新于 ${user.total_count_updated_at}`
    : "查询总有效次数";
  document.querySelector("#refreshed-at").textContent = state.refreshed_at;
  document.querySelector("#background-state").textContent = state.background?.running ? "运行中" : "未启动";
  const hasHealthCloudPassword = Boolean(user?.healthcloud_password_configured);
  healthcloudPasswordConfigured = hasHealthCloudPassword;
  healthcloudPasswordStatus.textContent = hasHealthCloudPassword
    ? "健康云密码已保存在 AutoTD 本地数据中；登录账号使用上方学号。"
    : "尚未设置健康云密码；登录账号使用上方学号。";
  checkinButton.disabled = checkinInProgress || !user || !hasHealthCloudPassword;
  checkinThreeButton.disabled = checkinInProgress || !user || !hasHealthCloudPassword;
  refreshCountButton.disabled = !user;
  checkinButton.textContent = checkinInProgress && checkinRoundsActive === 1 ? "打卡中…" : "打卡";
  checkinThreeButton.textContent = checkinInProgress && checkinRoundsActive === 3 ? "打卡三次中…" : "打卡三次";
  const checkinStatus = state.checkin_status;
  if (checkinInProgress) {
    checkinTimer.hidden = false;
    const phase = checkinStatus?.active
      ? (checkinStatus.message || checkinStatus.phase)
      : "正在准备打卡";
    const seconds = checkinStatus?.remaining_seconds;
    checkinTimer.textContent = seconds == null
      ? phase
      : `${phase} · ${formatCountdown(seconds)}`;
  } else {
    checkinTimer.hidden = true;
    checkinTimer.textContent = "";
  }
  renderWindowStatus(state.check_windows);
  if (!user && !initialSetupShown) {
    initialSetupShown = true;
    showPage("settings");
    setNotice("首次使用，请设置学号和校区。", "success");
  }
}

function formatCountdown(totalSeconds) {
  const seconds = Math.max(0, Number(totalSeconds) || 0);
  const minutes = Math.floor(seconds / 60);
  const remainder = seconds % 60;
  return `${String(minutes).padStart(2, "0")}:${String(remainder).padStart(2, "0")}`;
}

async function refresh(message = "状态已刷新") {
  const state = await requestJson("/api/state");
  renderState(state);
  setNotice(message, "success");
}

async function refreshCounts(refreshDaily = true) {
  renderState(await requestJson("/api/state"));
  if (refreshDaily) {
    const result = await requestJson("/api/counts/refresh", { method: "POST", body: "{}" });
    renderState(await requestJson("/api/state"));
    return result;
  }
  const result = { daily: null, total: null, errors: [] };
  try {
    result.total = await requestJson("/api/count/total/refresh", { method: "POST", body: "{}" });
    if (result.total.refresh_error) result.errors.push(result.total.refresh_error);
  } catch (error) {
    result.errors.push(`总有效次数刷新失败：${error.message}`);
  }
  renderState(await requestJson("/api/state"));
  return result;
}

function countRefreshMessage(result) {
  const parts = [];
  if (result.daily) {
    parts.push(result.daily.cached
      ? `今日已打卡 ${result.daily.count} 次（显示缓存）`
      : `今日已打卡 ${result.daily.count} 次`);
  }
  if (result.total) {
    parts.push(`总有效次数 ${result.total.count}${result.total.cached ? "（缓存）" : ""}`);
  }
  if (result.daily?.refresh_error) parts.push(result.daily.refresh_error);
  if (result.total?.refresh_error) parts.push(result.total.refresh_error);
  parts.push(...result.errors);
  return parts.join("；") || "次数已刷新";
}

async function withBusy(button, label, operation) {
  const previous = button.textContent;
  button.disabled = true;
  button.textContent = label;
  try {
    await operation();
  } catch (error) {
    setNotice(error.message, "error");
  } finally {
    button.disabled = false;
    button.textContent = previous;
  }
}

function showPage(pageId) {
  document.querySelectorAll(".tab-button").forEach((tab) => tab.classList.toggle("active", tab.dataset.page === pageId));
  document.querySelectorAll(".page").forEach((page) => { page.hidden = page.id !== pageId; });
}

document.querySelectorAll(".tab-button").forEach((button) => {
  button.addEventListener("click", () => showPage(button.dataset.page));
});

studentIdInput.addEventListener("input", () => { settingsDirty = true; });
campusInput.addEventListener("change", () => { settingsDirty = true; });

accountForm.addEventListener("submit", (event) => {
  event.preventDefault();
  withBusy(saveAccountButton, "保存中…", async () => {
    await requestJson("/api/settings/account", {
      method: "POST",
      body: JSON.stringify({
        student_id: studentIdInput.value.trim(),
        campus: campusInput.value,
        healthcloud_password: healthcloudPasswordInput.value,
      }),
    });
    healthcloudPasswordInput.value = "";
    const state = await requestJson("/api/state");
    settingsDirty = false;
    renderState(state);
    if (state.users[0]?.healthcloud_password_configured) {
      try {
        const result = await refreshCounts(true);
        if (result.errors.length) {
          setNotice(`设置已保存。${countRefreshMessage(result)}`, "error");
          return;
        }
      } catch (error) {
        setNotice(`设置已保存，但次数暂时无法刷新：${error.message}`, "error");
        return;
      }
    } else {
      const result = await refreshCounts(false);
      if (result.errors.length) {
        setNotice(`设置已保存。${countRefreshMessage(result)}`, "error");
        return;
      }
    }
    showPage("overview");
    setNotice("账号与健康云登录设置已保存。", "success");
  });
});

refreshCountButton.addEventListener("click", () => {
  withBusy(refreshCountButton, "查询中…", async () => {
    const result = await refreshCounts(healthcloudPasswordConfigured);
    setNotice(countRefreshMessage(result), result.errors.length ? "error" : "success");
  });
});

async function runCheckin(rounds) {
  checkinInProgress = true;
  checkinRoundsActive = rounds;
  checkinButton.disabled = true;
  checkinThreeButton.disabled = true;
  if (rounds === 1) checkinButton.textContent = "打卡中…";
  else checkinThreeButton.textContent = "打卡三次中…";
  checkinTimer.hidden = false;
  checkinTimer.textContent = "正在准备打卡";
  try {
    const result = await requestJson("/api/checkin", {
      method: "POST",
      body: JSON.stringify({ rounds }),
    });
    checkinInProgress = false;
    renderState(await requestJson("/api/state"));
    let countMessage = "";
    try {
      countMessage = countRefreshMessage(await refreshCounts(true));
    } catch (error) {
      countMessage = `次数刷新失败：${error.message}`;
    }
    renderState(await requestJson("/api/state"));
    setNotice([result.message, countMessage].filter(Boolean).join(" "), result.success ? "success" : "error");
  } catch (error) {
    setNotice(error.message, "error");
  } finally {
    checkinInProgress = false;
    requestJson("/api/state").then(renderState).catch(() => {
      checkinButton.textContent = "打卡";
      checkinThreeButton.textContent = "打卡三次";
      checkinButton.disabled = false;
      checkinThreeButton.disabled = false;
    });
  }
}

checkinButton.addEventListener("click", () => runCheckin(1));
checkinThreeButton.addEventListener("click", () => runCheckin(3));

refresh().then(async () => {
  const state = await requestJson("/api/state");
  if (!state.users[0]) return;
  try {
    const result = await refreshCounts(currentStateHasPassword());
    if (result.errors.length) setNotice(countRefreshMessage(result), "error");
  } catch (error) {
    setNotice(`次数暂时无法刷新：${error.message}`, "error");
  }
}).catch((error) => setNotice(error.message, "error"));

function currentStateHasPassword() {
  return healthcloudPasswordConfigured;
}

setInterval(() => {
  requestJson("/api/state").then(renderState).catch((error) => setNotice(error.message, "error"));
}, 30000);

setInterval(() => {
  if (!checkinInProgress) return;
  requestJson("/api/state").then(renderState).catch(() => {});
}, 1000);
