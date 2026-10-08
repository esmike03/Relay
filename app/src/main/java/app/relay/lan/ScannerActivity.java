package app.relay.lan;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

// The platform compatibility camera API keeps the scanner small and fully offline.
@SuppressWarnings("deprecation")
public class ScannerActivity extends Activity implements SurfaceHolder.Callback {
  SurfaceView preview;
  TextView hint;
  Button torchButton;
  volatile Camera camera;
  boolean resumed, ready, found, torch, permissionAsked;
  final AtomicBoolean decoding = new AtomicBoolean();
  final ExecutorService decoder = Executors.newSingleThreadExecutor();
  long lastInvalid;

  int dp(int value) {
    return (int) (value * getResources().getDisplayMetrics().density + .5f);
  }

  public void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setBackground(new CosmicDrawable());
    body.setPadding(dp(22), dp(34), dp(22), dp(24));
    TextView title = new TextView(this);
    title.setText("Scan QR code");
    title.setTextSize(25);
    title.setCompoundDrawablesRelativeWithIntrinsicBounds(
        RelayStyle.icon(this, "qr", android.graphics.Color.WHITE, 26), null, null, null);
    title.setCompoundDrawablePadding(dp(12));
    title.setTextColor(-1);
    body.addView(title);
    hint = new TextView(this);
    hint.setText(
        "Point your camera at the other device’s Relay code. Use the same Wi-Fi on both devices.");
    hint.setTextSize(15);
    hint.setTextColor(0xffc4d5ce);
    hint.setPadding(0, dp(16), 0, dp(20));
    body.addView(hint);
    preview = new SurfaceView(this);
    FrameLayout cameraFrame = new FrameLayout(this);
    cameraFrame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
    cameraFrame.addView(
        new View(this) {
          final android.graphics.Paint paint =
              new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

          protected void onDraw(android.graphics.Canvas canvas) {
            float size = Math.min(getWidth(), getHeight()) * .72f;
            float left = (getWidth() - size) / 2, top = (getHeight() - size) / 2;
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(0x55000000);
            canvas.drawRect(0, 0, getWidth(), top, paint);
            canvas.drawRect(0, top + size, getWidth(), getHeight(), paint);
            canvas.drawRect(0, top, left, top + size, paint);
            canvas.drawRect(left + size, top, getWidth(), top + size, paint);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(MainActivity.LIME);
            canvas.drawRoundRect(left, top, left + size, top + size, dp(20), dp(20), paint);
          }
        },
        new FrameLayout.LayoutParams(-1, -1));
    body.addView(cameraFrame, new LinearLayout.LayoutParams(-1, 0, 1));
    preview.getHolder().addCallback(this);
    torchButton = RelayDialog.button(this, "Flashlight", 0xff31544e, android.graphics.Color.WHITE);
    torchButton.setText("Flashlight");
    torchButton.setOnClickListener(
        v -> {
          Camera c = camera;
          if (c == null) return;
          try {
            Camera.Parameters p = c.getParameters();
            List<String> modes = p.getSupportedFlashModes();
            if (modes == null || !modes.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
              hint.setText("This camera has no flashlight.");
              return;
            }
            torch = !torch;
            p.setFlashMode(
                torch ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
            c.setParameters(p);
            torchButton.setText(torch ? "Flashlight off" : "Flashlight");
          } catch (Exception e) {
            hint.setText("Flashlight is unavailable.");
          }
        });
    LinearLayout.LayoutParams torchLayout = new LinearLayout.LayoutParams(-1, -2);
    torchLayout.topMargin = dp(20);
    body.addView(torchButton, torchLayout);
    Button cancel =
        RelayDialog.button(this, "Cancel scanning", MainActivity.LIME, MainActivity.INK);
    cancel.setText("Cancel scanning");
    cancel.setOnClickListener(v -> finish());
    LinearLayout.LayoutParams cancelLayout = new LinearLayout.LayoutParams(-1, -2);
    cancelLayout.topMargin = dp(12);
    body.addView(cancel, cancelLayout);
    if (Build.VERSION.SDK_INT >= 35)
      body.setOnApplyWindowInsetsListener(
          (v, i) -> {
            android.graphics.Insets bars = i.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(dp(22), bars.top + dp(20), dp(22), bars.bottom + dp(16));
            return i;
          });
    setContentView(body);
    body.requestApplyInsets();
  }

  public void onResume() {
    super.onResume();
    resumed = true;
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      if (!permissionAsked) {
        permissionAsked = true;
        requestPermissions(new String[] {Manifest.permission.CAMERA}, 20);
      }
    } else open();
  }

  public void onPause() {
    resumed = false;
    release();
    super.onPause();
  }

  public void onDestroy() {
    decoder.shutdownNow();
    super.onDestroy();
  }

  public void onRequestPermissionsResult(int code, String[] permissions, int[] granted) {
    super.onRequestPermissionsResult(code, permissions, granted);
    if (code == 20) {
      if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        open();
      else {
        hint.setText("Camera access is needed only for scanning. You can also pair manually.");
        new RelayDialog.Builder(this)
            .setTitle("Enable camera scanning")
            .setMessage("Allow camera permission in App info, or return to manual pairing.")
            .setPositiveButton(
                "App info",
                (d, w) ->
                    startActivity(
                        new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()))))
            .setNegativeButton("Back", (d, w) -> finish())
            .show();
      }
    }
  }

  public void surfaceCreated(SurfaceHolder h) {
    ready = true;
    open();
  }

  public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {}

  public void surfaceDestroyed(SurfaceHolder h) {
    ready = false;
    release();
  }

  void open() {
    if (!resumed || !ready || camera != null || found) return;
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
      return;
    try {
      int id = -1;
      Camera.CameraInfo info = new Camera.CameraInfo();
      for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
        Camera.getCameraInfo(i, info);
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
          id = i;
          break;
        }
      }
      if (id < 0) throw new Exception("No rear camera available");
      Camera c = Camera.open(id);
      camera = c;
      Camera.Parameters p = c.getParameters();
      Camera.Size choice = null;
      for (Camera.Size size : p.getSupportedPreviewSizes())
        if (size.width >= 640
            && size.width <= 1280
            && (choice == null || Math.abs(size.width - 1280) < Math.abs(choice.width - 1280)))
          choice = size;
      if (choice != null) p.setPreviewSize(choice.width, choice.height);
      p.setPreviewFormat(ImageFormat.NV21);
      if (p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
        p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
      c.setParameters(p);
      Camera.Size size = c.getParameters().getPreviewSize();
      int rotation = getWindowManager().getDefaultDisplay().getRotation();
      int degrees =
          rotation == Surface.ROTATION_90
              ? 90
              : rotation == Surface.ROTATION_180 ? 180 : rotation == Surface.ROTATION_270 ? 270 : 0;
      c.setDisplayOrientation((info.orientation - degrees + 360) % 360);
      c.setPreviewDisplay(preview.getHolder());
      c.setPreviewCallbackWithBuffer(
          (data, source) -> {
            if (found || !decoding.compareAndSet(false, true)) {
              if (camera == source) source.addCallbackBuffer(data);
              return;
            }
            try {
              decoder.execute(
                  () -> {
                    try {
                      Result result =
                          new QRCodeReader()
                              .decode(
                                  new BinaryBitmap(
                                      new HybridBinarizer(
                                          new PlanarYUVLuminanceSource(
                                              data,
                                              size.width,
                                              size.height,
                                              0,
                                              0,
                                              size.width,
                                              size.height,
                                              false))),
                                  Collections.singletonMap(DecodeHintType.TRY_HARDER, true));
                      String value = result.getText();
                      PairingQr.parse(value);
                      runOnUiThread(
                          () -> {
                            if (!resumed || found) return;
                            found = true;
                            release();
                            setResult(RESULT_OK, new Intent().putExtra("pairing", value));
                            finish();
                          });
                    } catch (NotFoundException ignored) {
                    } catch (Exception e) {
                      long now = SystemClock.elapsedRealtime();
                      if (now - lastInvalid > 2500) {
                        lastInvalid = now;
                        runOnUiThread(
                            () ->
                                hint.setText(
                                    e.getMessage() == null
                                        ? "Move closer and keep the QR code steady."
                                        : e.getMessage()));
                      }
                    } finally {
                      decoding.set(false);
                      runOnUiThread(
                          () -> {
                            if (camera == source && !found)
                              try {
                                source.addCallbackBuffer(data);
                              } catch (Exception ignored) {
                              }
                          });
                    }
                  });
            } catch (RejectedExecutionException e) {
              decoding.set(false);
            }
          });
      c.addCallbackBuffer(new byte[size.width * size.height * 3 / 2]);
      c.addCallbackBuffer(new byte[size.width * size.height * 3 / 2]);
      c.startPreview();
    } catch (Exception e) {
      release();
      hint.setText("Camera unavailable: " + e.getMessage() + ". Try manual pairing instead.");
    }
  }

  void release() {
    Camera c = camera;
    camera = null;
    torch = false;
    if (c != null) {
      try {
        c.setPreviewCallbackWithBuffer(null);
        c.stopPreview();
      } catch (Exception ignored) {
      }
      c.release();
    }
  }
}
