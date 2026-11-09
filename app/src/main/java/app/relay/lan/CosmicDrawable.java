package app.relay.lan;

import android.animation.ValueAnimator;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/** Flat drawables used by the main screens. */
final class CosmicDrawable {
  private CosmicDrawable() {}

  /** Rectangle fill with chosen corners cut at 45 degrees. */
  static final class Chamfer extends Drawable {
    static final int TOP_LEFT = 1, TOP_RIGHT = 2, BOTTOM_RIGHT = 4, BOTTOM_LEFT = 8;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final float cut;
    final int corners;

    Chamfer(int color, float cut, int corners) {
      this.cut = cut;
      this.corners = corners;
      p.setColor(color);
    }

    public void draw(Canvas c) {
      c.drawPath(chamferPath(getBounds(), cut, corners), p);
    }

    public void setAlpha(int alpha) {
      p.setAlpha(alpha);
    }

    public void setColorFilter(ColorFilter filter) {
      p.setColorFilter(filter);
    }

    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }

  /** Outline of a rectangle with the chosen corners cut at 45 degrees. */
  static Path chamferPath(Rect b, float cut, int corners) {
    float tl = (corners & Chamfer.TOP_LEFT) != 0 ? cut : 0;
    float tr = (corners & Chamfer.TOP_RIGHT) != 0 ? cut : 0;
    float br = (corners & Chamfer.BOTTOM_RIGHT) != 0 ? cut : 0;
    float bl = (corners & Chamfer.BOTTOM_LEFT) != 0 ? cut : 0;
    Path path = new Path();
    path.moveTo(b.left + tl, b.top);
    path.lineTo(b.right - tr, b.top);
    path.lineTo(b.right, b.top + tr);
    path.lineTo(b.right, b.bottom - br);
    path.lineTo(b.right - br, b.bottom);
    path.lineTo(b.left + bl, b.bottom);
    path.lineTo(b.left, b.bottom - bl);
    path.lineTo(b.left, b.top + tl);
    path.close();
    return path;
  }

  /** CRT screen glass: dark fill with cut corners and a soft phosphor glow in the middle. */
  static final class CrtGlass extends Drawable {
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final int color, glow, corners;
    final float cut;

    CrtGlass(int color, int glow, float cut, int corners) {
      this.color = color;
      this.glow = glow;
      this.cut = cut;
      this.corners = corners;
    }

    public void draw(Canvas c) {
      Rect b = getBounds();
      Path shape = chamferPath(b, cut, corners);
      p.setShader(null);
      p.setColor(color);
      c.drawPath(shape, p);
      p.setShader(
          new RadialGradient(
              b.exactCenterX(),
              b.exactCenterY(),
              Math.max(b.width(), b.height()) * .6f,
              new int[] {(glow & 0x00ffffff) | 0x1c000000, glow & 0x00ffffff},
              null,
              Shader.TileMode.CLAMP));
      c.drawPath(shape, p);
      p.setShader(null);
    }

    public void setAlpha(int alpha) {}

    public void setColorFilter(ColorFilter filter) {}

    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }

  /**
   * CRT overlay drawn above the screen content: scanlines, edge vignette, an optional rolling band
   * and a short glitch burst. Everything is clipped to the same cut-corner shape as the glass.
   */
  static final class CrtOverlay extends Drawable implements Runnable {
    final Paint lines = new Paint(), shade = new Paint(Paint.ANTI_ALIAS_FLAG), fx = new Paint();
    final java.util.Random random = new java.util.Random();
    final int glow, corners;
    final float density, cut;
    final boolean roll;
    long glitchUntil;

    CrtOverlay(int glow, float density, boolean roll, float cut, int corners) {
      this.glow = glow;
      this.density = density;
      this.roll = roll;
      this.cut = cut;
      this.corners = corners;
      int step = Math.max(3, Math.round(density * 3));
      int line = Math.max(1, Math.round(density));
      Bitmap pattern = Bitmap.createBitmap(1, step, Bitmap.Config.ARGB_8888);
      for (int y = 0; y < step; y++) pattern.setPixel(0, y, y < line ? 0x38000000 : 0);
      lines.setShader(new BitmapShader(pattern, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
    }

    /** Starts (or extends) a glitch burst lasting until the given uptime. */
    void glitch(long until) {
      glitchUntil = until;
      invalidateSelf();
    }

    public void draw(Canvas c) {
      Rect b = getBounds();
      float w = b.width(), h = b.height();
      boolean animate = ValueAnimator.areAnimatorsEnabled();
      long now = SystemClock.uptimeMillis();
      c.save();
      c.clipPath(chamferPath(b, cut, corners));
      c.translate(b.left, b.top);
      c.drawRect(0, 0, w, h, lines);
      shade.setShader(
          new RadialGradient(
              w / 2,
              h / 2,
              (float) Math.hypot(w, h) / 2,
              new int[] {0, 0, 0x73000000},
              new float[] {0, .55f, 1},
              Shader.TileMode.CLAMP));
      c.drawRect(0, 0, w, h, shade);
      shade.setShader(null);
      int tint = glow & 0x00ffffff;
      long next = -1;
      if (roll && animate) {
        float band = h * .22f;
        float y = (now % 7000) / 7000f * (h + band) - band;
        shade.setShader(
            new LinearGradient(
                0, y, 0, y + band, new int[] {tint, tint | 0x12000000, tint}, null,
                Shader.TileMode.CLAMP));
        c.drawRect(0, y, w, y + band, shade);
        shade.setShader(null);
        next = 50;
      }
      if (animate && now < glitchUntil) {
        // Tearing slices: bright phosphor streaks and dark dropouts, shifted sideways.
        for (int i = 0; i < 6; i++) {
          float y = random.nextFloat() * h;
          float sliceHeight = (2 + random.nextInt(14)) * density;
          float x = (random.nextFloat() - .5f) * w * .35f;
          fx.setColor(
              random.nextBoolean()
                  ? tint | ((0x28 + random.nextInt(0x50)) << 24)
                  : 0x80000000);
          c.drawRect(x, y, x + w, y + sliceHeight, fx);
        }
        if (random.nextInt(5) == 0) {
          fx.setColor(tint | 0x1e000000);
          c.drawRect(0, 0, w, h, fx);
        }
        next = 30;
      }
      c.restore();
      if (next > 0) {
        unscheduleSelf(this);
        scheduleSelf(this, now + next);
      }
    }

    public void run() {
      invalidateSelf();
    }

    public void setAlpha(int alpha) {}

    public void setColorFilter(ColorFilter filter) {}

    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }

  /** Talk pad with cut corners: dark gray when idle, green while transmitting, gently breathing. */
  static final class Orb extends Drawable implements Runnable {
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final float cut;
    boolean transmitting;

    Orb(float cut) {
      this.cut = cut;
    }

    void transmitting(boolean value) {
      if (transmitting != value) {
        transmitting = value;
        invalidateSelf();
      }
    }

    public void draw(Canvas c) {
      Rect b = getBounds();
      boolean animate = ValueAnimator.areAnimatorsEnabled();
      float t = animate ? (SystemClock.uptimeMillis() % 6500) / 6500f : 0;
      float breath = (float) Math.sin(t * Math.PI * 2) * .025f;
      p.setStyle(Paint.Style.FILL);
      p.setColor(transmitting ? MainActivity.HIGHLIGHT : MainActivity.SOFT);
      c.drawPath(chamferPath(b, cut, MainActivity.CUT), p);
      int inset = Math.round(Math.min(b.width(), b.height()) * (.09f - breath));
      Rect inner = new Rect(b);
      inner.inset(inset, inset);
      p.setColor(transmitting ? MainActivity.ACCENT : MainActivity.LIME);
      c.drawPath(chamferPath(inner, cut * .75f, MainActivity.CUT), p);
      if (animate) {
        unscheduleSelf(this);
        scheduleSelf(this, SystemClock.uptimeMillis() + 40);
      }
    }

    public void run() {
      invalidateSelf();
    }

    public void setAlpha(int alpha) {
      p.setAlpha(alpha);
    }

    public void setColorFilter(ColorFilter filter) {
      p.setColorFilter(filter);
    }

    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }
}
