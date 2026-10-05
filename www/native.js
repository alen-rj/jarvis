"use strict";
/* Native layer: only runs inside the Android app (Capacitor). On the website it does nothing.
   It swaps the browser-only pieces (speech, wake word, app launching) for real phone features. */
(function () {
  const C = window.Capacitor;
  if (!(C && C.isNativePlatform && C.isNativePlatform() && C.registerPlugin)) return;
  const NJ = C.registerPlugin("Jarvis");
  window.NJ = NJ;
  document.documentElement.classList.add("native");
  const enc = encodeURIComponent;
  const say = t => { addTurn("ai", t); caption(t); return speak(t); };
  let nativeListening = false;

  /* ---------- browser shims the main script touches ---------- */
  try {
    Object.defineProperty(window, "speechSynthesis", { configurable: true, value: {
      cancel() { NJ.stopSpeaking().catch(() => {}); }, speak() {}, getVoices() { return []; },
      speaking: false, pending: false, set onvoiceschanged(v) {} } });
  } catch (e) {}

  /* ---------- live signals into the core animation ---------- */
  NJ.addListener("partial", d => { heard(d.text); bump(.7); });
  NJ.addListener("rms", d => bump(Math.max(0, Math.min(1, (d.rms + 2) / 10))));
  NJ.addListener("ttsRange", () => bump(.85));
  NJ.addListener("modelProgress", d => { if (d.pct >= 0) { caption(`Downloading the offline wake-word model… ${d.pct}%`); bump(.4); } });
  NJ.addListener("wake", () => { closeSheets(); if (!nativeListening) setTimeout(() => window.startCommand(), 250); });

  /* ---------- speech in ---------- */
  window.stopRec = function () { if (nativeListening) { nativeListening = false; NJ.stopListening().catch(() => {}); } };
  window.startCommand = async function () {
    if (nativeListening) { window.stopRec(); setState("idle"); window.resumeWake(); return; }
    await NJ.stopSpeaking().catch(() => {});
    await NJ.pauseWake().catch(() => {});
    const p = await NJ.requestPerms({ aliases: ["microphone"] }).catch(() => ({}));
    if (p.microphone !== "granted") { fail("Microphone permission is off. Open Settings, then Phone setup, to allow it."); return; }
    nativeListening = true; setState("listening"); heard(""); chime();
    let r;
    try { r = await NJ.listen({ lang: cfg.lang }); }
    catch (e) { nativeListening = false; fail(e.message || "Voice input failed."); window.resumeWake(); return; }
    if (!nativeListening) return; // cancelled by a tap
    nativeListening = false;
    const txt = (r.text || "").trim();
    if (txt) { handle(txt); return; }
    if (r.error === "network") fail("Speech recognition needs internet. For offline use, download your language in Google app → Settings → Voice → Offline speech recognition.");
    else if (r.error === "cancelled") setState("idle");
    else { setState("idle"); caption("I didn't catch that."); }
    window.resumeWake();
  };

  /* ---------- speech out ---------- */
  window.speak = function (text) {
    return new Promise(async res => {
      if (!cfg.voiceOn || !text) { setState("idle"); res(); window.resumeWake(); return; }
      await NJ.pauseWake().catch(() => {});
      setState("speaking");
      const pulse = setInterval(() => bump(.3 + Math.random() * .3), 180);
      try { await NJ.speak({ text: text.replace(/https?:\/\/\S+/g, ""), lang: cfg.lang, voice: cfg.voice || "", rate: 1.0, pitch: 0.92 }); }
      catch (e) { caption(text); }
      clearInterval(pulse);
      if (S.state === "speaking") setState("idle");
      res(); window.resumeWake();
    });
  };
  window.loadVoices = async function () {
    const sel = $("#optVoicePick"); if (!sel) return;
    const r = await NJ.getVoices().catch(() => ({ voices: [] }));
    sel.innerHTML = "";
    const o0 = document.createElement("option"); o0.value = ""; o0.textContent = "Phone default"; sel.appendChild(o0);
    (r.voices || []).forEach(v => { const o = document.createElement("option"); o.value = v.name; o.textContent = `${v.name} (${v.lang})`; sel.appendChild(o); });
    sel.value = cfg.voice || "";
  };

  /* ---------- wake word (background service) ---------- */
  window.startWake = function () { window.resumeWake(); };
  window.resumeWake = function () { if (cfg.wake && !busy && !nativeListening) NJ.resumeWake().catch(() => {}); };

  async function enableWake() {
    const p = await NJ.requestPerms({ aliases: ["microphone", "notifications"] }).catch(() => ({}));
    if (p.microphone !== "granted") { caption("I need the microphone for the wake word."); return false; }
    const st = await NJ.wakeStatus().catch(() => ({}));
    if (!st.modelReady) {
      setState("executing", "Downloading");
      caption("Downloading the offline wake-word model, about 40 MB. This happens once.");
      try { await NJ.prepareWakeModel(); }
      catch (e) { fail((e.message || "Download failed") + " Check your connection and try again."); return false; }
    }
    try { await NJ.startWake({ word: cfg.name }); }
    catch (e) { fail(e.message || "Couldn't start background listening."); return false; }
    setState("idle");
    caption(`Listening for “Hey ${cfg.name}”, even with the screen off.`);
    const s = await NJ.checkPerms().catch(() => ({}));
    if (!s.overlay) offer({ title: "Let me open when you call", sub: "Allow “Display over other apps”", label: "Allow", onClick: () => NJ.openSetting({ which: "overlay" }) });
    if (!s.battery) offer({ title: "Keep me running", sub: "Set battery to Unrestricted", label: "Open", onClick: () => NJ.openSetting({ which: "battery" }) });
    refreshSetup();
    return true;
  }
  $("#optWake").onchange = async e => {
    const box = e.target;
    if (box.checked) {
      box.checked = false; box.disabled = true;
      const ok = await enableWake();
      box.disabled = false; box.checked = ok; cfg.wake = ok; saveCfg(); setState(S.state);
    } else {
      cfg.wake = false; saveCfg(); await NJ.stopWake().catch(() => {}); setState(S.state); caption("Wake word is off.");
    }
  };

  /* ---------- confirmation sheet: real actions instead of links ---------- */
  const go0 = $("#confirmGo"), go = go0.cloneNode(true);
  go0.replaceWith(go);
  let confirmAction = null;
  go.addEventListener("click", async e => {
    e.preventDefault();
    const fn = confirmAction, href = go.dataset.href; confirmAction = null; closeSheets();
    if (fn) await fn();
    else if (href) { const r = await NJ.openUrl({ url: href }).catch(() => ({})); if (r.opened) caption("Opened. Finish it there."); }
  });
  window.confirmSheet = function (title, html, href, label, action) {
    $("#confirmTitle").textContent = title; $("#confirmText").innerHTML = html;
    go.textContent = label; go.setAttribute("href", "#"); go.removeAttribute("target");
    go.dataset.href = href || ""; confirmAction = action || null; openSheet("#sheetConfirm");
  };
  // Every other link (maps, calendar, search results, apps) opens in the right app on the phone.
  document.addEventListener("click", e => {
    const a = e.target.closest && e.target.closest("a[href]");
    if (!a || a === go || a.id === "btnSaveImg") return;
    const href = a.getAttribute("href");
    if (!href || href.startsWith("#") || href.startsWith("blob:")) return;
    e.preventDefault();
    NJ.openUrl({ url: href }).catch(() => {});
  }, true);

  async function lockedGuard() {
    const r = await NJ.isLocked().catch(() => ({}));
    if (r.locked) { say("Unlock your phone first."); NJ.requestUnlock().catch(() => {}); return true; }
    return false;
  }

  /* ---------- contacts ---------- */
  async function resolveContact(raw) {
    const digits = String(raw).replace(/[^\d+]/g, "");
    if (digits.length >= 5) return { list: [{ name: raw, number: digits }] };
    const p = await NJ.requestPerms({ aliases: ["contacts"] }).catch(() => ({}));
    if (p.contacts !== "granted") return { error: { say: "I need contacts permission to find people. Allow it in Settings, Phone setup.", result: { error: "no contacts permission" } } };
    const r = await NJ.findContacts({ name: raw }).catch(() => ({ contacts: [] }));
    return { list: r.contacts || [] };
  }
  function pickOrAsk(list, raw, onPick, verb) {
    const names = [...new Set(list.map(x => x.name))];
    if (names.length > 1) {
      list.slice(0, 3).forEach(x => offer({ title: x.name, sub: x.number, label: verb, onClick: () => onPick(x) }));
      return { say: `I found ${names.length} people called ${raw}. Pick one.`, result: { status: "awaiting_user_choice" } };
    }
    return null;
  }
  const waNumber = n => { let d = String(n).replace(/[^\d]/g, ""); if (d.startsWith("0")) d = d.replace(/^0+/, ""); if (d.length === 10) d = "91" + d; return d; };

  /* ---------- tools that become real on the phone ---------- */
  const W = Object.assign({}, T); // browser versions, used as fallbacks

  T.set_timer = async ({ seconds, label }) => {
    seconds = Math.round(+seconds);
    if (!seconds || seconds < 1) return { say: "How long should the timer be?", result: { error: "missing duration" } };
    setState("executing", "Setting timer");
    const r = await NJ.setTimer({ seconds, label: label || `${cfg.name} timer` }).catch(() => ({}));
    if (r.ok) return { say: `${fmtDur(seconds)}, starting now.`, result: { status: "timer running in the Clock app" } };
    return W.set_timer({ seconds, label });
  };

  T.set_alarm = async ({ hour, minute, label }) => {
    hour = Math.round(+hour); minute = Math.round(+(minute || 0));
    if (isNaN(hour)) return { say: "For what time?", result: { error: "missing time" } };
    const d = new Date(); d.setHours(hour, minute, 0, 0); if (d <= new Date()) d.setDate(d.getDate() + 1);
    setState("executing", "Setting alarm");
    const r = await NJ.setAlarm({ hour, minute, label: label || cfg.name }).catch(() => ({}));
    if (r.ok) return { say: `Alarm set for ${fmtTime(d)}.`, result: { status: "alarm set in the Clock app", at: fmtTime(d) } };
    return W.set_alarm({ hour, minute, label });
  };

  T.create_reminder = async ({ text, when_iso }) => {
    const at = new Date(when_iso);
    if (!text) return { say: "What should I remind you about?", result: { error: "missing text" } };
    if (isNaN(at)) return { say: "When should I remind you?", result: { error: "missing or invalid time" } };
    const r = { id: Math.random().toString(36).slice(2), text, at: +at };
    reminders.push(r); store.set("reminders", reminders); scheduleReminder(r);
    await NJ.requestPerms({ aliases: ["notifications"] }).catch(() => {});
    try { await NJ.scheduleReminder({ id: r.id, at: r.at, title: `${cfg.name} reminder`, body: text }); }
    catch (e) { return W.create_reminder({ text, when_iso }); }
    return { say: `I'll remind you at ${fmtTime(at)}.`, result: { status: "scheduled; notifies even when the app is closed", at: fmtTime(at) } };
  };

  T.open_app = async ({ app }) => {
    if (/camera/i.test(app || "")) return W.open_app({ app });
    const name = String(app || "").replace(/\b(the|app|application|my)\b/gi, "").trim();
    setState("executing", "Opening");
    const r = await NJ.openApp({ name }).catch(() => ({}));
    if (r.opened) return { say: `Opening ${r.label}.`, result: { status: "opened", app: r.label } };
    return { say: `I couldn't find ${name} on this phone.`, result: { error: "app not installed" } };
  };

  T.play_media = async ({ query, service }) => {
    service = (service || "spotify").toLowerCase();
    setState("executing", "Opening");
    let url, name;
    if (/youtube music/.test(service)) { url = `https://music.youtube.com/search?q=${enc(query)}`; name = "YouTube Music"; }
    else if (/youtube/.test(service)) { url = `https://www.youtube.com/results?search_query=${enc(query)}`; name = "YouTube"; }
    else { url = `spotify:search:${enc(query)}`; name = "Spotify"; }
    let r = await NJ.openUrl({ url }).catch(() => ({}));
    if (!r.opened && name === "Spotify") r = await NJ.openUrl({ url: `https://open.spotify.com/search/${enc(query)}` }).catch(() => ({}));
    if (r.opened) return { say: `${name} is open with “${query}”. Tap play.`, result: { status: "search opened in the app; the user starts playback" } };
    return { say: `${name} didn't open. Is it installed?`, result: { error: "app did not open" } };
  };

  T.call_contact = async ({ name_or_number }) => {
    const raw = String(name_or_number || "").trim();
    const c = await resolveContact(raw);
    if (c.error) return c.error;
    if (!c.list.length) return { say: `I couldn't find ${raw} in your contacts.`, result: { error: "not found" } };
    const doCall = x => confirmSheet("Call", `Call <q>${esc(x.name)}</q> on ${esc(x.number)}?`, null, "Call", async () => {
      if (await lockedGuard()) return;
      await NJ.requestPerms({ aliases: ["phone"] }).catch(() => {});
      const r = await NJ.callNumber({ number: x.number }).catch(() => ({}));
      say(r.calling ? `Calling ${x.name}.` : `The dialer is open for ${x.name}. Tap call.`);
    });
    const ask = pickOrAsk(c.list, raw, doCall, "Call");
    if (ask) return ask;
    doCall(c.list[0]);
    return { say: `Call ${c.list[0].name}? Tap call to confirm.`, result: { status: "awaiting_user_confirmation" } };
  };

  T.send_message = async ({ recipient, body, channel }) => {
    recipient = String(recipient || "").trim(); body = String(body || "").trim();
    const wa = /whatsapp/i.test(channel || "");
    if (!body) return { say: "What should the message say?", result: { error: "missing body" } };
    if (!recipient) return W.send_message({ recipient, body, channel });
    const c = await resolveContact(recipient);
    if (c.error) return c.error;
    if (!c.list.length) return { say: `I couldn't find ${recipient} in your contacts.`, result: { error: "not found" } };
    const doSend = x => {
      if (wa) return confirmSheet("WhatsApp", `To <q>${esc(x.name)}</q><br><br>“${esc(body)}”`, null, "Open WhatsApp", async () => {
        const r = await NJ.openUrl({ url: `https://wa.me/${waNumber(x.number)}?text=${enc(body)}` }).catch(() => ({}));
        say(r.opened ? "WhatsApp is open with your message. Tap send." : "WhatsApp didn't open.");
      });
      confirmSheet("Send text", `To <q>${esc(x.name)}</q> · ${esc(x.number)}<br><br>“${esc(body)}”`, null, "Send", async () => {
        if (await lockedGuard()) return;
        const p = await NJ.requestPerms({ aliases: ["sms"] }).catch(() => ({}));
        if (p.sms !== "granted") {
          await NJ.openUrl({ url: `sms:${x.number}?body=${enc(body)}` }).catch(() => {});
          say("Messages is open with your text. Tap send."); return;
        }
        setState("executing", "Sending");
        try {
          const r = await NJ.sendSms({ number: x.number, body });
          say(r.sent ? `Sent to ${x.name}.` : r.pending ? `Still sending to ${x.name}. Check Messages in a moment.` : `That didn't go through (${r.error}).`);
        } catch (e) { fail(e.message || "Couldn't send."); }
      });
    };
    const ask = pickOrAsk(c.list, recipient, doSend, "Text");
    if (ask) return ask;
    doSend(c.list[0]);
    const x = c.list[0];
    return { say: wa ? `WhatsApp ${x.name}: ${body}. Tap to open it, then send.` : `Text ${x.name}: ${body}. Tap send to confirm.`, result: { status: "awaiting_user_confirmation" } };
  };

  T.get_schedule = async () => {
    const p = await NJ.requestPerms({ aliases: ["calendar"] }).catch(() => ({}));
    let events = [];
    if (p.calendar === "granted") { try { events = (await NJ.todayEvents({ days: 1 })).events || []; } catch (e) {} }
    const now = Date.now(), end = new Date(); end.setHours(23, 59, 59, 999);
    const items = events.filter(e => e.allDay || e.end > now).map(e => e.allDay ? `${e.title} (all day)` : `${e.title} at ${fmtTime(new Date(e.begin))}`);
    reminders.filter(r => r.at >= now && r.at <= +end).forEach(r => items.push(`reminder: ${r.text} at ${fmtTime(new Date(r.at))}`));
    const result = { items, calendar_access: p.calendar === "granted" };
    if (!items.length) return { say: p.calendar === "granted" ? "Nothing else on your calendar today." : "Nothing from me today, and I can't see your calendar without permission.", result };
    return { say: `Still today: ${items.join("; ")}.`, result };
  };

  /* ---------- saving graded photos to the gallery ---------- */
  $("#btnSaveImg").addEventListener("click", async e => {
    e.preventDefault();
    try {
      const blob = await (await fetch($("#btnSaveImg").href)).blob();
      const b64 = await new Promise(r => { const fr = new FileReader(); fr.onload = () => r(String(fr.result).split(",")[1]); fr.readAsDataURL(blob); });
      await NJ.saveImage({ base64: b64, name: `jarvis-cinematic-${Date.now()}.jpg` });
      caption("Saved to Pictures › Jarvis.");
    } catch (err) { caption(err.message || "Couldn't save the photo."); }
  });
  $("#btnShareImg").hidden = true;

  /* ---------- Phone setup panel in Settings ---------- */
  const lim = document.querySelector(".limits");
  if (lim) lim.innerHTML = `<div><b>Running as a phone app.</b> The wake word works with the screen off, alarms and timers go into your Clock app, and reminders arrive even when I'm closed.</div><div>Calls and texts always show you exactly what will happen. One tap from you and I do it.</div>`;
  const wakeHelp = $("#optWake") && $("#optWake").closest(".row").querySelector("small");
  if (wakeHelp) wakeHelp.innerHTML = 'Listens for “Hey <span class="nm"></span>” even with the screen off. Runs offline on your phone; a small notification shows while it listens.';
  document.querySelectorAll(".nm").forEach(n => n.textContent = cfg.name);
  const setup = document.createElement("div");
  setup.className = "field"; setup.id = "nativeSetup";
  setup.innerHTML = `<label>Phone setup</label>
    <div class="setup" id="setupRows"></div>
    <small>Long-press the power button (or swipe up from a corner) to call me once I'm your default assistant. Some phones (Xiaomi, Realme, Vivo, Oppo) also need “Autostart” turned on for Jarvis in their own settings.</small>`;
  const sheetBody = $("#sheetSettings .body");
  sheetBody.insertBefore(setup, sheetBody.firstChild);
  const st = document.createElement("style");
  st.textContent = `.setup{display:flex;flex-direction:column;gap:2px}
    .srow{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:8px 0;border-bottom:1px solid var(--line)}
    .srow span{min-width:0;font-size:14px}.srow .ok{font:500 11px/1 var(--f-display);letter-spacing:.16em;color:var(--ok);text-transform:uppercase}
    .srow .btn{padding:8px 12px}`;
  document.head.appendChild(st);

  async function refreshSetup() {
    const s = await NJ.checkPerms().catch(() => ({}));
    const rows = [
      ["Microphone, contacts, calendar, calls, texts, alerts",
        ["microphone", "contacts", "calendar", "phone", "sms", "notifications"].every(k => s[k] === "granted"),
        "Allow", async () => { await NJ.requestPerms({ aliases: ["microphone", "contacts", "calendar", "phone", "sms", "notifications"] }).catch(() => {}); refreshSetup(); }],
      ["Offline wake-word model (40 MB)", !!s.wakeModel, "Download", async () => {
        try { caption("Downloading the wake-word model…"); await NJ.prepareWakeModel(); caption("Wake-word model ready."); } catch (e) { caption(e.message); } refreshSetup(); }],
      ["Open when called from the lock screen", !!s.overlay, "Allow", () => NJ.openSetting({ which: "overlay" })],
      ["Keep running in the background", !!s.battery, "Allow", () => NJ.openSetting({ which: "battery" })],
      ["Default assistant (long-press power)", null, "Set", () => NJ.openSetting({ which: "assistant" })]
    ];
    const box = $("#setupRows"); box.innerHTML = "";
    rows.forEach(([label, done, btn, fn]) => {
      const r = document.createElement("div"); r.className = "srow";
      const t = document.createElement("span"); t.textContent = label; r.appendChild(t);
      if (done) { const ok = document.createElement("span"); ok.className = "ok"; ok.textContent = "Done"; r.appendChild(ok); }
      else { const b = document.createElement("button"); b.className = "btn"; b.type = "button"; b.textContent = btn; b.onclick = fn; r.appendChild(b); }
      box.appendChild(r);
    });
  }
  window.refreshSetup = refreshSetup;
  $("#btnSettings").addEventListener("click", refreshSetup);
  document.addEventListener("visibilitychange", () => { if (!document.hidden && !$("#sheetSettings").hidden) refreshSetup(); });

  /* ---------- boot ---------- */
  (async () => {
    loadVoices();
    if (cfg.wake) {
      const s = await NJ.wakeStatus().catch(() => ({}));
      if (s.modelReady && !s.running) NJ.startWake({ word: cfg.name }).catch(() => {});
      else if (!s.modelReady) { cfg.wake = false; saveCfg(); }
    }
    setState(S.state);
    const first = store.get("nativeSetupShown", false);
    if (!first) {
      store.set("nativeSetupShown", true);
      caption(`${cfg.name} is installed. Open Settings to finish phone setup, then turn on the wake word.`);
    }
  })();
})();
