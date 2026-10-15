/* 微信小程序 SDK。token() 返回 auth-gateway 面向 keel-api 的用户换票令牌。 */
function createKeelClient(options) {
  var base = options.baseUrl.replace(/\/$/, "");
  var path = "/a/api/" + encodeURIComponent(options.agent);

  function request(url, data, token, chunk) {
    return new Promise(function (resolve, reject) {
      var task = wx.request({
        url: base + path + url,
        method: "POST",
        header: {
          "Content-Type": "application/json",
          "Authorization": "Bearer " + token,
          "X-Keel-Miniapp-Id": options.appid,
          "Accept": chunk ? "text/event-stream" : "application/json"
        },
        data: data,
        enableChunked: !!chunk,
        responseType: chunk ? "arraybuffer" : "text",
        success: function (response) {
          if (response.statusCode < 200 || response.statusCode >= 300) {
            reject(new Error("渠道请求失败：HTTP " + response.statusCode));
          } else {
            resolve(response.data);
          }
        },
        fail: reject
      });
      if (chunk) task.onChunkReceived(function (part) {
        try { chunk(part.data); }
        catch (error) { reject(error); task.abort(); }
      });
    });
  }

  function utf8() {
    var pending = [];
    return function (buffer) {
      var bytes = pending.concat(Array.prototype.slice.call(new Uint8Array(buffer)));
      var out = "";
      var i = 0;
      while (i < bytes.length) {
        var first = bytes[i];
        var size = first < 128 ? 1 : first >= 194 && first < 224 ? 2 :
          first >= 224 && first < 240 ? 3 : first >= 240 && first < 245 ? 4 : 0;
        if (!size) { out += "\ufffd"; i++; continue; }
        if (i + size > bytes.length) break;
        var point = first & (size === 1 ? 127 : size === 2 ? 31 : size === 3 ? 15 : 7);
        var valid = true;
        for (var j = 1; j < size; j++) {
          if ((bytes[i + j] & 192) !== 128) { valid = false; break; }
          point = (point << 6) | (bytes[i + j] & 63);
        }
        if (!valid || point > 0x10ffff || point >= 0xd800 && point <= 0xdfff) {
          out += "\ufffd"; i++; continue;
        }
        if (point <= 0xffff) out += String.fromCharCode(point);
        else { point -= 0x10000; out += String.fromCharCode(0xd800 + (point >> 10), 0xdc00 + (point & 1023)); }
        i += size;
      }
      pending = bytes.slice(i);
      return out;
    };
  }

  async function token() {
    var value = await options.token();
    if (!value) throw new Error("小程序用户令牌为空");
    return value;
  }

  return {
    stream: async function (text, onEvent) {
      var bearer = await token();
      var credential = options.credential;
      var gate = await request("/entry", { credential: credential }, bearer);
      if (!gate || !gate.data || !gate.data.invokeUrl) throw new Error("渠道准入失败");
      var decode = utf8();
      var pending = "";
      await request("/invoke", { credential: credential, text: text }, bearer, function (buffer) {
        pending += decode(buffer).replace(/\r\n/g, "\n");
        var boundary;
        while ((boundary = pending.indexOf("\n\n")) >= 0) {
          var frame = pending.slice(0, boundary);
          pending = pending.slice(boundary + 2);
          var type = (frame.match(/^event:\s*(.+)$/m) || [])[1];
          var raw = (frame.match(/^data:\s*(.+)$/m) || [])[1];
          if (type && raw) {
            var event;
            try { event = JSON.parse(raw); } catch (_) { continue; }
            onEvent(type, event);
          }
        }
      });
    },
    feedback: async function (traceId, value) {
      return request("/feedback", { credential: options.credential, traceId: traceId, value: value }, await token());
    }
  };
}

module.exports = { createKeelClient: createKeelClient };
