package app.relay.lan;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.View;

/** Lightweight native styling and a consistent set of scalable line icons. */
final class RelayStyle {
  static int dp(Context c, int n) {
    return Math.round(n * c.getResources().getDisplayMetrics().density);
  }

  static Drawable surface(Context c, int color, int radius) {
    if (color == Color.WHITE
        || color == MainActivity.BG
        || color == MainActivity.INK
        || color == 0xff31544e
        || color == 0xffe6ebe3) {
      return new CosmicDrawable.Glass(dp(c, radius), color == MainActivity.INK, false);
    }
    int end =
        color == MainActivity.INK ? 0xff493969 : color == MainActivity.LIME ? 0xffb5e6d9 : color;
    GradientDrawable g =
        new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {color, end});
    g.setCornerRadius(dp(c, radius));
    g.setStroke(dp(c, 1), 0x40f5eafa);
    return g;
  }

  static void shadow(View v, int depth) {
    v.setElevation(dp(v.getContext(), depth));
    v.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
    if (android.os.Build.VERSION.SDK_INT >= 28) {
      v.setOutlineAmbientShadowColor(0xff0b0922);
      v.setOutlineSpotShadowColor(0xff0b0922);
    }
  }

  static String iconFor(String label) {
    String s = label.toLowerCase(java.util.Locale.ROOT);
    if (s.equals("×") || s.contains("cancel") || s.equals("close")) return "close";
    if (s.contains("delete") || s.contains("forget")) return "delete";
    if (s.contains("end call") || s.contains("decline")) return "end";
    if (s.contains("record")) return "record";
    if (s.contains("speaker") || s.contains("earpiece") || s.contains("volume")) return "speaker";
    if (s.contains("talk") || s.contains("transmitting")) return "mic";
    if (s.contains("flashlight")) return "flash";
    if (s.contains("qr") || s.contains("scan")) return "qr";
    if (s.contains("pair") || s.contains("agree") || s.contains("automatic")) return "link";
    if (s.contains("call") || s.contains("answer")) return "phone";
    if (s.contains("copy")) return "copy";
    if (s.contains("rename") || s.contains("address") || s.contains("manually")) return "edit";
    if (s.contains("battery")) return "battery";
    if (s.contains("notification")) return "bell";
    if (s.contains("offline") || s.contains("available")) return "power";
    if (s.contains("history")) return "history";
    if (s.contains("device")) return "users";
    if (s.contains("settings") || s.contains("permission") || s.contains("shortcut"))
      return "settings";
    if (s.contains("identity") || s.contains("certificate")) return "shield";
    if (s.equals("save") || s.equals("done") || s.contains("approve")) return "check";
    if (s.contains("share")) return "share";
    return "chevron";
  }

  static Drawable icon(Context c, String name, int color, int size) {
    LineIcon d = new LineIcon(name, color, dp(c, size));
    d.setBounds(0, 0, dp(c, size), dp(c, size));
    return d;
  }

  static final class LineIcon extends Drawable {
    final String name;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final int size;

    LineIcon(String name, int color, int size) {
      this.name = name;
      this.size = size;
      p.setColor(color);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(1.8f);
      p.setStrokeCap(Paint.Cap.ROUND);
      p.setStrokeJoin(Paint.Join.ROUND);
    }

    void path(Canvas c, float... xy) {
      Path a = new Path();
      a.moveTo(xy[0], xy[1]);
      for (int i = 2; i < xy.length; i += 2) a.lineTo(xy[i], xy[i + 1]);
      c.drawPath(a, p);
    }

    void rect(Canvas c, float l, float t, float r, float b, float radius) {
      c.drawRoundRect(l, t, r, b, radius, radius, p);
    }

    public void draw(Canvas c) {
      c.save();
      c.translate(getBounds().left, getBounds().top);
      c.scale(getBounds().width() / 24f, getBounds().height() / 24f);
      switch (name) {
        case "radio":
          rect(c, 6, 8, 18, 21, 3);
          path(c, 9, 8, 9, 3, 16, 3);
          path(c, 10, 12, 14, 12);
          path(c, 10, 16, 14, 16);
          break;
        case "users":
          c.drawCircle(9, 8, 3, p);
          c.drawArc(3, 13, 15, 23, 180, 180, false, p);
          c.drawArc(13, 5, 20, 11, -90, 180, false, p);
          c.drawArc(14, 13, 22, 22, -90, 90, false, p);
          break;
        case "history":
          c.drawArc(4, 4, 21, 21, -135, 310, false, p);
          path(c, 3, 4, 3, 10, 8, 10);
          path(c, 12, 7, 12, 12, 15, 14);
          break;
        case "settings":
          c.drawCircle(12, 12, 7, p);
          c.drawCircle(12, 12, 2.5f, p);
          for (int i = 0; i < 8; i++) {
            c.save();
            c.rotate(i * 45, 12, 12);
            path(c, 12, 3, 12, 5);
            c.restore();
          }
          break;
        case "phone":
        case "end":
          Path ph = new Path();
          ph.moveTo(5, 3);
          ph.lineTo(9, 3);
          ph.lineTo(10, 8);
          ph.lineTo(7, 10);
          ph.cubicTo(8, 13, 11, 16, 14, 17);
          ph.lineTo(16, 14);
          ph.lineTo(21, 15);
          ph.lineTo(21, 19);
          ph.quadTo(21, 22, 17, 21);
          ph.cubicTo(9, 19, 5, 15, 3, 7);
          ph.quadTo(2, 3, 5, 3);
          c.drawPath(ph, p);
          break;
        case "mic":
          rect(c, 9, 3, 15, 14, 3);
          c.drawArc(6, 7, 18, 18, 0, 180, false, p);
          path(c, 12, 18, 12, 21);
          path(c, 9, 21, 15, 21);
          break;
        case "speaker":
          path(c, 3, 9, 7, 9, 12, 5, 12, 19, 7, 15, 3, 15, 3, 9);
          c.drawArc(11, 7, 19, 17, -65, 130, false, p);
          c.drawArc(10, 3, 23, 21, -55, 110, false, p);
          break;
        case "record":
          c.drawCircle(12, 12, 9, p);
          c.drawCircle(12, 12, 4, p);
          break;
        case "qr":
          rect(c, 3, 3, 9, 9, 1);
          rect(c, 15, 3, 21, 9, 1);
          rect(c, 3, 15, 9, 21, 1);
          path(c, 15, 15, 18, 15, 18, 18, 21, 18, 21, 21, 15, 21, 15, 18);
          break;
        case "link":
          c.drawArc(3, 7, 14, 18, 45, 270, false, p);
          c.drawArc(10, 6, 21, 17, 225, 270, false, p);
          path(c, 9, 15, 15, 9);
          break;
        case "wifi":
          c.drawArc(2, 5, 22, 24, -145, 110, false, p);
          c.drawArc(6, 10, 18, 23, -140, 100, false, p);
          c.drawCircle(12, 19, 1, p);
          break;
        case "shield":
          path(c, 12, 3, 20, 6, 19, 14, 16, 18, 12, 21, 8, 18, 5, 14, 4, 6, 12, 3);
          path(c, 8, 12, 11, 15, 16, 10);
          break;
        case "close":
          path(c, 6, 6, 18, 18);
          path(c, 18, 6, 6, 18);
          break;
        case "more":
          for (int i = 0; i < 3; i++) c.drawCircle(5 + i * 7, 12, 1, p);
          break;
        case "check":
          path(c, 5, 12, 10, 17, 19, 7);
          break;
        case "chevron":
          path(c, 9, 6, 15, 12, 9, 18);
          break;
        case "edit":
          path(c, 4, 20, 5, 15, 16, 4, 20, 8, 9, 19, 4, 20);
          path(c, 13, 7, 17, 11);
          break;
        case "copy":
          rect(c, 8, 7, 21, 21, 2);
          path(c, 15, 7, 15, 3, 3, 3, 3, 16, 8, 16);
          break;
        case "battery":
          rect(c, 3, 6, 19, 18, 2);
          path(c, 22, 10, 22, 14);
          path(c, 7, 10, 7, 14);
          break;
        case "bell":
          path(c, 5, 17, 7, 14, 7, 9, 9, 5, 15, 5, 17, 9, 17, 14, 19, 17, 5, 17);
          c.drawArc(9, 17, 15, 22, 0, 180, false, p);
          break;
        case "power":
          c.drawArc(4, 4, 20, 20, -50, 280, false, p);
          path(c, 12, 2, 12, 11);
          break;
        case "flash":
          path(c, 13, 2, 5, 13, 11, 13, 10, 22, 19, 10, 13, 10, 13, 2);
          break;
        case "delete":
          path(c, 4, 6, 20, 6);
          path(c, 9, 6, 9, 3, 15, 3, 15, 6);
          path(c, 6, 6, 7, 21, 17, 21, 18, 6);
          path(c, 10, 10, 10, 17);
          path(c, 14, 10, 14, 17);
          break;
        case "share":
          c.drawCircle(5, 12, 2, p);
          c.drawCircle(19, 5, 2, p);
          c.drawCircle(19, 19, 2, p);
          path(c, 7, 11, 17, 6);
          path(c, 7, 13, 17, 18);
          break;
      }
      c.restore();
    }

    public int getIntrinsicWidth() {
      return size;
    }

    public int getIntrinsicHeight() {
      return size;
    }

    public void setAlpha(int a) {
      p.setAlpha(a);
    }

    public void setColorFilter(ColorFilter f) {
      p.setColorFilter(f);
    }

    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }
}
