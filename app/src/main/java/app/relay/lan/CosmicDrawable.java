package app.relay.lan;

import android.animation.ValueAnimator;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/** Soft native gradients keep the cosmic glass effect usable on older phones. */
final class CosmicDrawable extends Drawable {
  final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

  public void draw(Canvas canvas) {
    Rect b = getBounds();
    float w = b.width(), h = b.height();
    canvas.save();
    canvas.translate(b.left, b.top);
    paint.setColor(Color.WHITE);
    paint.setShader(
        new LinearGradient(
            0,
            0,
            w,
            h,
            new int[] {0xff171a38, 0xff352047, 0xff15172e},
            null,
            Shader.TileMode.CLAMP));
    canvas.drawRect(0, 0, w, h, paint);
    glow(canvas, w * .93f, h * .12f, w * .8f, 0x674f4caa);
    glow(canvas, -w * .1f, h * .57f, w * .88f, 0x37338891);
    glow(canvas, w * .8f, h * .88f, w * .65f, 0x40553884);
    paint.setShader(null);
    paint.setColor(0x0ec9b7ed);
    Path ribbon = new Path();
    ribbon.moveTo(-w * .1f, h * .28f);
    ribbon.cubicTo(w * .42f, h * .05f, w * .55f, h * .5f, w * 1.15f, h * .32f);
    ribbon.cubicTo(w * .5f, h * .57f, w * .35f, h * .16f, -w * .1f, h * .35f);
    ribbon.close();
    canvas.drawPath(ribbon, paint);
    paint.setColor(0x55f5e6c3);
    for (int i = 0; i < 12; i++) {
      float x = w * ((i * 37 + 11) % 100) / 100f, y = h * ((i * 23 + 7) % 100) / 100f;
      canvas.drawCircle(x, y, i % 3 == 0 ? 1.6f : 1f, paint);
    }
    canvas.restore();
  }

  void glow(Canvas c, float x, float y, float radius, int color) {
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

  static final class Glass extends Drawable {
    final float radius;
    final boolean featured, panel;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    Glass(float radius, boolean featured, boolean panel) {
      this.radius = radius;
      this.featured = featured;
      this.panel = panel;
    }

    public void draw(Canvas c) {
      Rect b = getBounds();
      RectF r = new RectF(b);
      Path clip = new Path();
      clip.addRoundRect(r, radius, radius, Path.Direction.CW);
      c.save();
      c.clipPath(clip);
      p.setStyle(Paint.Style.FILL);
      p.setColor(Color.WHITE);
      int[] colors =
          panel
              ? new int[] {0xff302b4e, 0xff232640}
              : featured ? new int[] {0x905d477e, 0xb52c294a} : new int[] {0x404b456f, 0x802a2b4c};
      p.setShader(
          new LinearGradient(
              r.left, r.top, r.right, r.bottom, colors, null, Shader.TileMode.CLAMP));
      c.drawRect(r, p);
      p.setShader(
          new RadialGradient(
              r.left + r.width() * .85f,
              r.top,
              r.width() * .85f,
              new int[] {featured ? 0x385be0bf : 0x259991de, 0x009991de},
              null,
              Shader.TileMode.CLAMP));
      c.drawRect(r, p);
      c.restore();
      p.setShader(null);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(1.3f);
      p.setColor(panel ? 0x557f719e : 0x46d9c9f7);
      r.inset(.8f, .8f);
      c.drawRoundRect(r, radius, radius, p);
    }

    public void getOutline(Outline outline) {
      outline.setRoundRect(getBounds(), radius);
    }

    public void setAlpha(int alpha) {}

    public void setColorFilter(ColorFilter filter) {}

    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }

  static final class Orb extends Drawable implements Runnable {
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    boolean transmitting;

    void transmitting(boolean value) {
      if (transmitting != value) {
        transmitting = value;
        invalidateSelf();
      }
    }

    public void draw(Canvas c) {
      Rect b = getBounds();
      float x = b.exactCenterX(), y = b.exactCenterY();
      float size = Math.min(b.width(), b.height()) / 2f;
      boolean animate = ValueAnimator.areAnimatorsEnabled();
      float t = animate ? (SystemClock.uptimeMillis() % 6500) / 6500f : 0;
      float breath = (float) Math.sin(t * Math.PI * 2) * .025f;
      p.setStyle(Paint.Style.FILL);
      p.setShader(
          new RadialGradient(
              x,
              y,
              size,
              new int[] {transmitting ? 0x867ee6c1 : 0x766e53ad, 0x00c5b4ff},
              null,
              Shader.TileMode.CLAMP));
      c.drawCircle(x, y, size, p);
      float ring = size * (.78f + breath);
      p.setShader(
          new LinearGradient(
              x - ring,
              y - ring,
              x + ring,
              y + ring,
              transmitting
                  ? new int[] {0xffd2ffe6, 0xffa2d8d7}
                  : new int[] {0xffded0ff, 0xffc2c6ed, 0xffb8e8d9},
              null,
              Shader.TileMode.CLAMP));
      c.drawCircle(x, y, ring, p);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(Math.max(1, size * .014f));
      p.setShader(
          new SweepGradient(
              x, y, new int[] {0x10f7eccb, 0xbdf8efd6, 0x6ab1f7da, 0x10f7eccb}, null));
      c.save();
      c.rotate(t * 35, x, y);
      c.drawCircle(x, y, size * .90f, p);
      c.restore();
      p.setShader(null);
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
