/* Keel web widget. Publish credential only; no console cookie and no vendor key. */
(function () {
  var script = document.currentScript || {};
  var agent = script.dataset && script.dataset.agent;
  var credential = script.dataset && script.dataset.credential;
  var serviceOrigin = new URL(script.src, location.href).origin;
  var traceId = "";
  var root = document.createElement("div");
  root.className = "keel-widget";
  var log = document.createElement("pre");
  var input = document.createElement("textarea");
  var send = document.createElement("button");
  send.textContent = "发送";
  var up = document.createElement("button");
  up.textContent = "赞";
  var down = document.createElement("button");
  down.textContent = "踩";
  root.append(log, input, send, up, down);
  document.body.append(root);

  function show(text) { log.textContent += text + "\n"; }

  async function headers() {
    var token = typeof window.keelChannelToken === "function" ? await window.keelChannelToken(agent) : "";
    var result = { "Content-Type": "application/json" };
    if (token) result.Authorization = "Bearer " + token;
    return result;
  }

  async function entry() {
    var response = await fetch(serviceOrigin + "/a/api/" + encodeURIComponent(agent) + "/entry", {
      method: "POST",
      headers: await headers(),
      body: JSON.stringify({ credential: credential || "" })
    });
    var body = await response.json();
    if (!response.ok) {
      show(body.message || "这个域名没有登记");
      return null;
    }
    return body.data;
  }

  async function talk() {
    var gate = await entry();
    if (!gate) return;
    var response = await fetch(serviceOrigin + gate.invokeUrl, {
      method: "POST",
      headers: Object.assign(await headers(), { "Accept": "text/event-stream" }),
      body: JSON.stringify({ text: input.value, credential: credential || "" })
    });
    if (!response.ok || !response.body) { show("调用失败：" + response.status); return; }
    var reader = response.body.getReader();
    var decoder = new TextDecoder();
    var pending = "";
    while (true) {
      var part = await reader.read();
      pending += decoder.decode(part.value || new Uint8Array(), { stream: !part.done });
      pending = pending.replace(/\r\n/g, "\n");
      var boundary;
      while ((boundary = pending.indexOf("\n\n")) >= 0) {
        var frame = pending.slice(0, boundary).replace(/\r/g, "");
        pending = pending.slice(boundary + 2);
        var type = (frame.match(/^event:\s*(.+)$/m) || [])[1];
        var raw = (frame.match(/^data:\s*(.+)$/m) || [])[1];
        if (!raw) continue;
        var event;
        try { event = JSON.parse(raw); } catch (_) { continue; }
        if (type === "token") log.textContent += event.text || "";
        if (type === "final") { traceId = event.trace_id || ""; show(event.answer || ""); }
        if (type === "suspend") show("等待审批：" + (event.prompt || ""));
        if (type === "error") show("调用失败：" + (event.message || event.code || ""));
      }
      if (part.done) break;
    }
  }

  async function feedback(value) {
    if (!traceId) { show("请先完成一次对话"); return; }
    var response = await fetch(serviceOrigin + "/a/api/" + encodeURIComponent(agent) + "/feedback", {
      method: "POST",
      headers: await headers(),
      body: JSON.stringify({ value: value, credential: credential || "", traceId: traceId })
    });
    show(response.ok ? (value < 0 ? "已点踩" : "已点赞") : "反馈提交失败");
  }

  send.onclick = talk;
  up.onclick = function () { feedback(1); };
  down.onclick = function () { feedback(-1); };
})();
