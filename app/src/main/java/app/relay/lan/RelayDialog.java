package app.relay.lan;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

/** Shared dialog design, with a scrollable body and a footer that stays visible. */
final class RelayDialog extends AlertDialog {
  final Builder spec;
  Button positive, negative;
  LinearLayout shell, body, footer;
  ScrollView scroll;
  boolean compactFooter;

  RelayDialog(Builder spec) {
    super(spec.context);
    this.spec = spec;
  }

  int dp(int n) {
    return (int) (n * getContext().getResources().getDisplayMetrics().density + .5f);
  }

  static Drawable shape(Context c, int color, int radius) {
    return RelayStyle.surface(c, color, radius);
  }

  static Button button(Context c, String label, int bg, int fg) {
    if (fg == MainActivity.INK && bg != MainActivity.LIME) fg = MainActivity.TEXT;
    if (bg == MainActivity.RED) fg = MainActivity.INK;
    Button b = new Button(c);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(15);
    b.setTextColor(fg);
    b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    b.setGravity(Gravity.CENTER);
    b.setIncludeFontPadding(false);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setMinHeight(RelayStyle.dp(c, 48));
    b.setStateListAnimator(null);
    b.setElevation(0);
    b.setPadding(
        (int) (14 * c.getResources().getDisplayMetrics().density),
        (int) (12 * c.getResources().getDisplayMetrics().density),
        (int) (14 * c.getResources().getDisplayMetrics().density),
        (int) (12 * c.getResources().getDisplayMetrics().density));
    b.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(0x33d1c3f7), shape(c, bg, 15), shape(c, Color.WHITE, 15)));
    if (!label.equals("×")) {
      b.setCompoundDrawablesRelativeWithIntrinsicBounds(
          RelayStyle.icon(c, RelayStyle.iconFor(label), fg, 20), null, null, null);
      b.setCompoundDrawablePadding(RelayStyle.dp(c, 10));
    }
    return b;
  }

  TextView text(CharSequence value, int size, int color, boolean bold) {
    TextView t = new TextView(getContext());
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color == MainActivity.INK ? MainActivity.TEXT : color);
    t.setLineSpacing(dp(4), 1);
    if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    return t;
  }

  void gap(LinearLayout into, int height) {
    Space s = new Space(getContext());
    into.addView(s, new LinearLayout.LayoutParams(1, dp(height)));
  }

  boolean hasTextEditor(View view) {
    if (view == null) return false;
    if (view.onCheckIsTextEditor()) return true;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++)
        if (hasTextEditor(group.getChildAt(i))) return true;
    }
    return false;
  }

  protected void onCreate(Bundle b) {
    super.onCreate(b);
    Context c = getContext();
    shell = new LinearLayout(c);
    shell.setOrientation(LinearLayout.VERTICAL);
    shell.setPadding(dp(20), dp(18), dp(20), dp(18));
    shell.setBackground(new CosmicDrawable.Glass(dp(26), false, true));
    RelayStyle.shadow(shell, 8);
    LinearLayout brand = new LinearLayout(c);
    brand.setGravity(Gravity.CENTER_VERTICAL);
    TextView tag = text("RELAY", 11, MainActivity.MUTED, true);
    tag.setCompoundDrawablesRelativeWithIntrinsicBounds(
        RelayStyle.icon(c, "radio", MainActivity.TEXT, 18), null, null, null);
    tag.setCompoundDrawablePadding(dp(8));
    brand.addView(tag, new LinearLayout.LayoutParams(0, -2, 1));
    ImageButton close = new ImageButton(c);
    close.setImageDrawable(RelayStyle.icon(c, "close", MainActivity.MUTED, 20));
    close.setScaleType(ImageView.ScaleType.CENTER);
    close.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(0x223c5f49), shape(c, MainActivity.BG, 14), null));
    close.setPadding(0, 0, 0, 0);
    close.setContentDescription("Close dialog");
    close.setOnClickListener(v -> cancel());
    brand.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
    shell.addView(brand);
    gap(shell, 10);
    if (spec.title != null) {
      shell.addView(text(spec.title, 22, MainActivity.INK, true));
      gap(shell, 14);
    }
    scroll = new ScrollView(c);
    scroll.setFillViewport(false);
    scroll.setClipToPadding(false);
    body = new LinearLayout(c);
    body.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(body);
    shell.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
    if (spec.message != null) {
      body.addView(text(spec.message, 15, MainActivity.MUTED, false));
      gap(body, 8);
    }
    if (spec.view != null) {
      if (spec.view.getParent() instanceof ViewGroup)
        ((ViewGroup) spec.view.getParent()).removeView(spec.view);
      if (spec.view instanceof LinearLayout) spec.view.setPadding(0, 0, 0, 0);
      body.addView(spec.view, new LinearLayout.LayoutParams(-1, -2));
    }
    if (spec.items != null)
      for (int i = 0; i < spec.items.length; i++) {
        final int index = i;
        Button item = button(c, spec.items[i], MainActivity.BG, MainActivity.INK);
        item.setCompoundDrawablesRelativeWithIntrinsicBounds(
            RelayStyle.icon(c, RelayStyle.iconFor(spec.items[i]), MainActivity.TEXT, 20), null,
            RelayStyle.icon(c, "chevron", MainActivity.MUTED, 16), null);
        item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        item.setOnClickListener(
            v -> {
              dismiss();
              if (spec.itemClick != null) spec.itemClick.onClick(this, index);
            });
        body.addView(item, new LinearLayout.LayoutParams(-1, -2));
        gap(body, 10);
      }
    footer = new LinearLayout(c);
    footer.setOrientation(LinearLayout.VERTICAL);
    if (spec.positive != null) {
      gap(footer, 18);
      boolean danger = spec.positive.toString().matches("(?i)Delete|Forget|Decline");
      positive =
          button(
              c,
              spec.positive.toString(),
              danger ? MainActivity.RED : MainActivity.LIME,
              danger ? Color.WHITE : MainActivity.INK);
      positive.setOnClickListener(
          v -> {
            if (spec.positiveClick != null) spec.positiveClick.onClick(this, BUTTON_POSITIVE);
            dismiss();
          });
      footer.addView(positive, new LinearLayout.LayoutParams(-1, -2));
    }
    if (spec.negative != null) {
      gap(footer, 9);
      negative = button(c, spec.negative.toString(), 0xffe6ebe3, MainActivity.INK);
      negative.setOnClickListener(
          v -> {
            if (spec.negativeClick != null) spec.negativeClick.onClick(this, BUTTON_NEGATIVE);
            dismiss();
          });
      footer.addView(negative, new LinearLayout.LayoutParams(-1, -2));
    }
    shell.addView(footer);
    layoutFooter(false);
    setContentView(shell);
    // AlertDialog's controller cannot see the editors in our custom content.
    // Its default flag otherwise prevents every form from connecting to the IME.
    if (hasTextEditor(spec.view)) {
      getWindow().clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
      getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    getWindow()
        .setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    WindowManager.LayoutParams w = getWindow().getAttributes();
    w.dimAmount = .48f;
    w.gravity = Gravity.CENTER;
    getWindow().setAttributes(w);
  }

  public void show() {
    super.show();
    int width = getContext().getResources().getDisplayMetrics().widthPixels - dp(32);
    getWindow().setLayout(Math.min(width, dp(430)), WindowManager.LayoutParams.WRAP_CONTENT);
    shell.getViewTreeObserver().addOnGlobalLayoutListener(this::fitToVisibleWindow);
    shell.post(this::fitToVisibleWindow);
  }

  void fitToVisibleWindow() {
    android.graphics.Rect visible = new android.graphics.Rect();
    getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
    boolean compact = hasTextEditor(spec.view) && visible.height() < dp(450);
    if (compact != compactFooter) {
      compactFooter = compact;
      layoutFooter(compact);
      return;
    }
    int max = compact ? visible.height() - dp(16) : (int) (visible.height() * .85f);
    if (max <= 0 || shell.getMeasuredHeight() == 0) return;
    int fixed = shell.getMeasuredHeight() - scroll.getMeasuredHeight();
    int natural = body.getMeasuredHeight();
    int height = natural + fixed > max ? Math.max(1, max - fixed) : -2;
    if (scroll.getLayoutParams().height != height)
      scroll.setLayoutParams(new LinearLayout.LayoutParams(-1, height));
  }

  void layoutFooter(boolean compact) {
    footer.removeAllViews();
    footer.setOrientation(compact ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
    footer.setPadding(0, compact ? dp(12) : 0, 0, 0);
    if (compact) {
      if (negative != null) {
        negative.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
        footer.addView(negative, new LinearLayout.LayoutParams(0, -2, 1));
      }
      if (positive != null) {
        positive.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        if (negative != null) params.leftMargin = dp(8);
        footer.addView(positive, params);
      }
    } else {
      if (positive != null) {
        gap(footer, 18);
        positive.setCompoundDrawablesRelativeWithIntrinsicBounds(
            RelayStyle.icon(
                getContext(),
                RelayStyle.iconFor(spec.positive.toString()),
                positive.getCurrentTextColor(),
                20),
            null,
            null,
            null);
        footer.addView(positive, new LinearLayout.LayoutParams(-1, -2));
      }
      if (negative != null) {
        gap(footer, 9);
        negative.setCompoundDrawablesRelativeWithIntrinsicBounds(
            RelayStyle.icon(
                getContext(),
                RelayStyle.iconFor(spec.negative.toString()),
                negative.getCurrentTextColor(),
                20),
            null,
            null,
            null);
        footer.addView(negative, new LinearLayout.LayoutParams(-1, -2));
      }
    }
  }

  public Button getButton(int which) {
    return which == BUTTON_POSITIVE ? positive : which == BUTTON_NEGATIVE ? negative : null;
  }

  static final class Builder extends AlertDialog.Builder {
    final Context context;
    CharSequence title, message, positive, negative;
    View view;
    String[] items;
    DialogInterface.OnClickListener positiveClick, negativeClick, itemClick;

    Builder(Context c) {
      super(c);
      context = c;
    }

    public Builder setTitle(CharSequence t) {
      title = t;
      return this;
    }

    public Builder setMessage(CharSequence m) {
      message = m;
      return this;
    }

    public Builder setView(View v) {
      view = v;
      return this;
    }

    public Builder setPositiveButton(CharSequence t, DialogInterface.OnClickListener l) {
      positive = t;
      positiveClick = l;
      return this;
    }

    public Builder setNegativeButton(CharSequence t, DialogInterface.OnClickListener l) {
      negative = t;
      negativeClick = l;
      return this;
    }

    public Builder setItems(CharSequence[] choices, DialogInterface.OnClickListener l) {
      items = new String[choices.length];
      for (int i = 0; i < choices.length; i++) items[i] = choices[i].toString();
      itemClick = l;
      return this;
    }

    public AlertDialog create() {
      return new RelayDialog(this);
    }

    public AlertDialog show() {
      AlertDialog d = create();
      d.show();
      return d;
    }
  }
}
