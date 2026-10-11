package app.relay.lan;

import android.animation.ValueAnimator;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/** Light backdrop and the animated call visuals. */
final class RelayArt {
  /** Off-white page with three soft pastel glows. */
  static final class Backdrop extends Drawable {
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public void draw(Canvas canvas) {
      Rect b = getBounds();
      float w = b.width(), h = b.height();
      canvas.save();
      canvas.translate(b.left, b.top);
      paint.setShader(null);
      paint.setColor(MainActivity.PAGE);
      canvas.drawRect(0, 0, w, h, paint);
      glow(canvas, w * .95f, h * .04f, w * .8f, 0x2b5b5fe0);
      glow(canvas, -w * .1f, h * .5f, w * .75f, 0x1c10a37f);
      glow(canvas, w * .95f, h * .96f, w * .7f, 0x1ce879b9);
      canvas.restore();
    }

    void glow(Canvas c, float x, float y, float radius, int color) {
      paint.setColor(Color.WHITE);
      paint.setShader(
          new RadialGradient(
              x, y, radius, new int[] {color, color & 0x00ffffff}, null, Shader.TileMode.CLAMP));
      c.drawCircle(x, y, radius, paint);
    }

    public void setAlpha(int alpha) {}

    public void setColorFilter(ColorFilter filter) {}

    public int getOpacity() {
      return PixelFormat.OPAQUE;
    }
  }

  /** Rings that expand and fade from the centre, for ringing and waiting states. */
  static final class Pulse extends Drawable implements Runnable {
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final int color;
    final float inner;

    Pulse(int color, float inner) {
      this.color = color;
      this.inner = inner;
    }

    public void draw(Canvas c) {
      Rect b = getBounds();
      float x = b.exactCenterX(), y = b.exactCenterY();
      float size = Math.min(b.width(), b.height()) / 2f;
      boolean animate = ValueAnimator.areAnimatorsEnabled();
      long now = SystemClock.uptimeMillis();
      p.setColor(color);
      for (int i = 0; i < 2; i++) {
        float t = animate ? ((now + i * 900L) % 1800) / 1800f : .35f + i * .4f;
        p.setAlpha((int) (64 * (1 - t)));
        c.drawCircle(x, y, size * (inner + (1 - inner) * t), p);
      }
      if (animate) {
        unscheduleSelf(this);
        scheduleSelf(this, now + 16);
      }
    }

    public void run() {
      invalidateSelf();
    }

    public void setAlpha(int alpha) {}

    public void setColorFilter(ColorFilter filter) {
      p.setColorFilter(filter);
    }

    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }

  /** The talk button: a breathing indigo disc that turns mint and ripples while transmitting. */
  static final class Orb extends Drawable implements Runnable {
    static final float CORE = .74f;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    Shader idle, live;
    boolean transmitting;

    void transmitting(boolean value) {
      if (transmitting != value) {
        transmitting = value;
        invalidateSelf();
      }
    }

    protected void onBoundsChange(Rect bounds) {
      idle = live = null;
    }

    public void draw(Canvas c) {
      Rect b = getBounds();
      float x = b.exactCenterX(), y = b.exactCenterY();
      float size = Math.min(b.width(), b.height()) / 2f, core = size * CORE;
      boolean animate = ValueAnimator.areAnimatorsEnabled();
      long now = SystemClock.uptimeMillis();
      p.setShader(null);
      p.setStyle(Paint.Style.FILL);
      p.setColor(transmitting ? MainActivity.MINT : MainActivity.ACCENT);
      if (transmitting && animate) {
        for (int i = 0; i < 2; i++) {
          float t = ((now + i * 700L) % 1400) / 1400f;
          p.setAlpha((int) (80 * (1 - t)));
          c.drawCircle(x, y, core + (size - core) * t, p);
        }
      } else {
        float breath = animate ? (float) Math.sin(now % 3600 / 3600.0 * Math.PI * 2) * .5f + .5f : .5f;
        p.setAlpha((int) (26 + 22 * breath));
        c.drawCircle(x, y, core + (size - core) * (.4f + .35f * breath), p);
      }
      p.setAlpha(255);
      if (idle == null) {
        idle =
            new LinearGradient(
                x - core, y - core, x + core, y + core, 0xff6366f1, 0xff8b5cf6,
                Shader.TileMode.CLAMP);
        live =
            new LinearGradient(
                x - core, y - core, x + core, y + core, 0xff10b981, 0xff0ea5a4,
                Shader.TileMode.CLAMP);
      }
      p.setShader(transmitting ? live : idle);
      c.drawCircle(x, y, core, p);
      p.setShader(null);
      if (animate) {
        unscheduleSelf(this);
        scheduleSelf(this, now + (transmitting ? 16 : 33));
      }
    }

    public void run() {
      invalidateSelf();
    }

    public void setAlpha(int alpha) {}

    public void setColorFilter(ColorFilter filter) {
      p.setColorFilter(filter);
    }

    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }
}
