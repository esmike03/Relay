import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.imageio.ImageIO;

/** Verifies that a rendered desktop enrollment code is readable by Android's ZXing core. */
public final class DecodeEnrollmentQr {
  public static void main(String[] args) throws Exception {
    BufferedImage image = ImageIO.read(new File(args[0]));
    int[] rgb = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(
        new RGBLuminanceSource(image.getWidth(), image.getHeight(), rgb)));
    Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
    hints.put(DecodeHintType.TRY_HARDER, true);
    hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
    String decoded = new MultiFormatReader().decode(bitmap, hints).getText();
    URI uri = new URI(decoded);
    if (!"relay".equals(uri.getScheme()) || !"hub".equals(uri.getAuthority()))
      throw new AssertionError("Wrong enrollment URI scheme/authority");
    Map<String, String> query = new HashMap<>();
    for (String pair : uri.getRawQuery().split("&")) {
      String[] fields = pair.split("=", 2);
      query.put(fields[0], URLDecoder.decode(fields[1], StandardCharsets.UTF_8.name()));
    }
    if (!"1".equals(query.get("v")) || !query.get("host").matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")
        || !query.get("fp").matches("[A-F0-9]{64}") || !query.get("token").matches("[a-fA-F0-9]{64}"))
      throw new AssertionError("Invalid enrollment QR contents");
    int port = Integer.parseInt(query.get("port"));
    if (port < 1024 || port > 65535) throw new AssertionError("Invalid enrollment port");
    System.out.println("PASS: Rendered desktop QR decoded with Android ZXing 3.5.3; version, endpoint, full fingerprint and token valid");
  }
}
