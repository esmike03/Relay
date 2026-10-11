package app.relay.lan;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/** Soundwave bars that follow a live audio level and rest as a row of dots. */
final class WaveView extends View {
  interface Source {
    /** Current loudness from 0 (silent) to 1. */
    float level();
  }

  final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  final int bars;
  Source source;
  float shown;
  int frameDelay = 16;

  WaveView(Context c, int bars, int color) {
    super(c);
    this.bars = bars;
    paint.setColor(color);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  void setColor(int color) {
    if (paint.getColor() != color) {
      paint.setColor(color);
      invalidate();
    }
  }

  /** A gentle decorative wave with no audio behind it. */
  WaveView ambient() {
    source = () -> .5f;
    frameDelay = 40;
    return this;
  }

  protected void onDraw(Canvas canvas) {
    float target = source == null ? 0 : Math.max(0, Math.min(1, source.level()));
    boolean animate = ValueAnimator.areAnimatorsEnabled();
    shown = animate ? shown + (target - shown) * (target > shown ? .45f : .1f) : target;
    double time = animate ? SystemClock.uptimeMillis() / 1000.0 : 0;
    float slot = getWidth() / (float) bars, width = Math.min(slot * .5f, getHeight() * .14f);
    float middle = getHeight() / 2f;
    int color = paint.getColor();
    paint.setAlpha((int) (80 + 175 * Math.min(1, shown * 3)));
    for (int i = 0; i < bars; i++) {
      float position = (i + .5f) / bars * 2 - 1;
      float envelope = (float) Math.exp(-position * position * 2.2);
      float wobble = .55f + .45f * (float) Math.sin(time * (frameDelay > 16 ? 2.2 : 6 + i % 5) + i * 1.7);
      float height = Math.max(width, getHeight() * shown * envelope * wobble);
      float x = slot * i + (slot - width) / 2;
      canvas.drawRoundRect(
          x, middle - height / 2, x + width, middle + height / 2, width / 2, width / 2, paint);
    }
    paint.setColor(color);
    // Poll slowly while silent so the bars wake up when a voice starts.
    postInvalidateDelayed(target > .01f || shown > .01f ? (animate ? frameDelay : 100) : 100);
  }
}
