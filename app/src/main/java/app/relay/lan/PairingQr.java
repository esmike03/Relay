package app.relay.lan;

import android.graphics.Bitmap;
import android.net.Uri;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.util.*;
import org.json.JSONObject;

final class PairingQr {
  static String payload(String host, int port, String fingerprint, String name) {
    return new Uri.Builder()
        .scheme("relay")
        .authority("pair")
        .appendQueryParameter("v", "1")
        .appendQueryParameter("host", host)
        .appendQueryParameter("port", String.valueOf(port))
        .appendQueryParameter("fp", fingerprint)
        .appendQueryParameter("name", name)
        .build()
        .toString();
  }

  static JSONObject parse(String value) throws Exception {
    if (value == null || value.length() > 1024)
      throw new Exception("This is not a Relay pairing QR code");
    Uri u = Uri.parse(value);
    if (!"relay".equals(u.getScheme())
        || !"pair".equals(u.getAuthority())
        || !"1".equals(u.getQueryParameter("v")))
      throw new Exception("Scan a Relay pairing QR code");
    String host = u.getQueryParameter("host"), fp = u.getQueryParameter("fp");
    if (host == null
        || !host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")
        || fp == null
        || !fp.matches("[A-F0-9]{64}")) throw new Exception("Incomplete or invalid pairing code");
    int port = Integer.parseInt(u.getQueryParameter("port"));
    if (port < 1024 || port > 65535) throw new Exception("Invalid pairing port");
    return Store.json(
        "host",
        host,
        "port",
        port,
        "fingerprint",
        fp,
        "name",
        RelayService.safeName(
            u.getQueryParameter("name") == null ? "Local device" : u.getQueryParameter("name")));
  }

  static Bitmap bitmap(String value, int size) throws Exception {
    Map<EncodeHintType, Object> hints = new HashMap<>();
    hints.put(EncodeHintType.MARGIN, 4);
    hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
    BitMatrix m = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size, hints);
    int[] pixels = new int[size * size];
    for (int y = 0; y < size; y++)
      for (int x = 0; x < size; x++) pixels[y * size + x] = m.get(x, y) ? 0xff173b37 : 0xffffffff;
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
  }
}
