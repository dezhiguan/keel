/* Miniprogram helper. wx.request enableChunked, not EventSource. */
function keelMiniprogramStream(agent, credential, text, onChunk) {
  return new Promise(function (resolve, reject) {
    wx.request({
      url: "/api/v1/agents/" + agent + "/channels/stream",
      method: "POST",
      enableChunked: true,
      data: { origin: "miniprogram", credential: credential, text: text },
      success: function (response) {
        if (response.data && response.data.code === "CHANNEL_ORIGIN_DENIED") {
          reject(response.data);
          return;
        }
        resolve(response);
      },
      fail: reject
    }).onChunkReceived(function (result) {
      onChunk(result.data);
    });
  });
}
