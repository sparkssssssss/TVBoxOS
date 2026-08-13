package com.github.tvbox.osc.ui.tv.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.widget.FrameLayout;

/**
 * FrameLayout that draws a translucent white "light-up" overlay on top of its
 * children while the view itself has focus.
 *
 * Why a custom view instead of a child overlay or android:foreground:
 * - A child View with android:duplicateParentState does not reliably refresh
 *   its drawable state when the parent's focus changes on all devices (the
 *   focus border selector still works because it lives on the focused root,
 *   but a sibling overlay child did not visibly update on TV).
 * - android:foreground only draws on top of children on API 23+; this app
 *   ships minSdk 19 flavors where it is drawn under the children.
 * - Here we read the view's own focus state directly (the same state that
 *   drives the focus border, which is proven to work on TV) and paint the
 *   overlay right after dispatchDraw(), so it always covers the content.
 *
 * No scaling / animation / layout changes are involved, so no text
 * re-sampling jitter is introduced (root scale stays fixed at 1.0).
 */
public class FocusHighlightFrameLayout extends FrameLayout {

    private static final int HIGHLIGHT_COLOR = 0x40FFFFFF; // 25% white

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final RectF mRect = new RectF();
    private final float mCornerRadius;

    public FocusHighlightFrameLayout(Context context) {
        this(context, null);
    }

    public FocusHighlightFrameLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FocusHighlightFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        // Same 10mm corner radius as the focus background drawable.
        mCornerRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_MM, 10f, getResources().getDisplayMetrics());
        mPaint.setColor(HIGHLIGHT_COLOR);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (isFocused() && getWidth() > 0 && getHeight() > 0) {
            mRect.set(0f, 0f, getWidth(), getHeight());
            mPath.reset();
            mPath.addRoundRect(mRect, mCornerRadius, mCornerRadius, Path.Direction.CW);
            canvas.drawPath(mPath, mPaint);
        }
    }
}
