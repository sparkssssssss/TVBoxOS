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
 * FrameLayout that creates a high-contrast, static focus state on top of its
 * children. The focused card receives a small white lift; every unfocused
 * sibling is darkened. The contrast is deliberately two-sided: a white-only
 * overlay on the focused poster was too subtle to recognize from TV distance.
 *
 * The overlay is painted directly after dispatchDraw(), based on this root
 * view's own focus state (the same proven state used by shape_user_focus).
 * It therefore does not depend on duplicateParentState or foreground API
 * behavior on older TV systems.
 *
 * No scaling / animation / layout changes are involved, so no text
 * re-sampling jitter is introduced (root scale stays fixed at 1.0).
 */
public class FocusHighlightFrameLayout extends FrameLayout {

    /** Focused card: a restrained white lift; white text remains crisp. */
    private static final int FOCUSED_OVERLAY_COLOR = 0x24FFFFFF; // 14% white
    /** Unfocused cards: visibly recede so the focused card is unmistakable. */
    private static final int UNFOCUSED_OVERLAY_COLOR = 0x3D000000; // 24% black

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
        mPaint.setColor(UNFOCUSED_OVERLAY_COLOR);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        // The focus border already invalidates through its selector. Do this
        // explicitly as well, so our post-child canvas overlay is redrawn on
        // every focus transition on every supported TV implementation.
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;

        mPaint.setColor(isFocused() ? FOCUSED_OVERLAY_COLOR : UNFOCUSED_OVERLAY_COLOR);
        mRect.set(0f, 0f, getWidth(), getHeight());
        mPath.reset();
        mPath.addRoundRect(mRect, mCornerRadius, mCornerRadius, Path.Direction.CW);
        canvas.drawPath(mPath, mPaint);
    }
}
