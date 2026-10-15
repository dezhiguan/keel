/* Keel web widget. Publish credential only; no console cookie and no vendor key. */
(function () {
  var script = document.currentScript || {};
  var agent = script.dataset && script.dataset.agent;
  var credential = script.dataset && script.dataset.credential;
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

  async function entry() {
    var response = await fetch("/api/v1/agents/" + agent + "/channels/entry", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ origin: location.origin, credential: credential || "" })
    });
    var body = await response.json();
    if (body.code === "CHANNEL_ORIGIN_DENIED") {
      show(body.message || "这个域名没有登记");
      return null;
    }
    return body.data;
  }

  async function talk() {
    var gate = await entry();
    if (!gate) return;
    var response = await fetch(gate.invokeUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json", "Accept": "text/event-stream" },
      body: JSON.stringify({ input: input.value, channel: credential || "" })
    });
    var text = await response.text();
    if (text.indexOf("event: suspend") >= 0) show("等待审批");
    show(text);
  }

  async function feedback(value) {
    await fetch("/api/v1/agents/" + agent + "/channels/feedback", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ value: value, credential: credential || "" })
    });
    show(value < 0 ? "已点踩" : "已点赞");
  }

  send.onclick = talk;
  up.onclick = function () { feedback(1); };
  down.onclick = function () { feedback(-1); };
})();
