(function(){
  var output = [];
  var maxCandidates = 16;
  var maxSide = 1024;
  var maxPayloadLength = 8192;
  var maxTotalPixels = 4 * 1024 * 1024;
  var decodedPixels = 0;
  var visited = 0;
  var pixelLimitReached = false;
  var crossOriginImage = false;
  var oversizedPayload = false;
  var seen = Object.create(null);
  function marker(value) {
    if (!seen[value] && output.length < maxCandidates) {
      seen[value] = true;
      output.push(value);
    }
  }
  function scan(element) {
    var width = element.tagName === "IMG" ? element.naturalWidth : element.width;
    var height = element.tagName === "IMG" ? element.naturalHeight : element.height;
    if (!width || !height || width < 1 || height < 1) return;
    var scale = Math.min(1, maxSide / width, maxSide / height);
    var targetWidth = Math.max(1, Math.floor(width * scale));
    var targetHeight = Math.max(1, Math.floor(height * scale));
    var pixels = targetWidth * targetHeight;
    if (decodedPixels + pixels > maxTotalPixels) {
      pixelLimitReached = true;
      return;
    }
    decodedPixels += pixels;
    var canvas = document.createElement("canvas");
    canvas.width = targetWidth;
    canvas.height = targetHeight;
    try {
      var context = canvas.getContext("2d", {willReadFrequently: true});
      if (!context) return;
      context.drawImage(element, 0, 0, targetWidth, targetHeight);
      var imageData = context.getImageData(0, 0, targetWidth, targetHeight);
      var result = globalThis.jsQR(imageData.data, targetWidth, targetHeight, {inversionAttempts: "attemptBoth"});
      if (result && typeof result.data === "string") {
        if (result.data.length > maxPayloadLength) oversizedPayload = true;
        else marker(result.data);
      }
    } catch (error) {
      if (error && error.name === "SecurityError") crossOriginImage = true;
      else marker("__BUCC_QR_SCAN_FAILED__");
    }
  }
  try {
    var candidates = document.querySelectorAll("img, canvas");
    for (var index = 0; index < candidates.length && visited < maxCandidates; index += 1) {
      visited += 1;
      var element = candidates[index];
      var rect = element.getBoundingClientRect();
      var style = window.getComputedStyle(element);
      if (rect.width > 0 && rect.height > 0 && style.display !== "none" && style.visibility !== "hidden" && Number(style.opacity) !== 0) {
        scan(element);
      }
    }
  } catch (_) {
    marker("__BUCC_QR_SCAN_FAILED__");
  }
  if (crossOriginImage) marker("__BUCC_QR_CROSS_ORIGIN__");
  if (pixelLimitReached) marker("__BUCC_QR_PIXEL_LIMIT__");
  if (oversizedPayload) marker("__BUCC_QR_PAYLOAD_LIMIT__");
  return JSON.stringify(output);
})()
