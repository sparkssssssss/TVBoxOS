package com.github.tvbox.osc.ui.tv.widget;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import com.owen.tvrecyclerview.widget.TvRecyclerView;

/**
 * 焦点导航滚动诊断与加固用 TvRecyclerView。
 *
 * 背景（经反编译 androidx RecyclerView 1.2.1/1.3.0 ViewFlinger 核实）：
 * - TvRecyclerView.requestChildRectangleOnScreen() 把 DPAD 焦点导航滚动转成
 *   smoothScrollBy(dx, dy)；
 * - ViewFlinger.smoothScrollBy() 每次执行的是
 *   mLastFlingX = mLastFlingY = 0; mOverScroller.startScroll(0, 0, dx, dy, duration)
 *   并 setScrollState(SETTLING)，即新请求会直接替换正在运行的动画，并不会把旧
 *   final 距离累加（"一次按键滚好几屏"不是 ViewFlinger 距离累加造成的）；
 * - OverScroller 是时间驱动，主线程卡顿或连续按键堆积时，单帧 delta 可能很大，
 *   表现为"哐哐哐"的跳变；但累计滚动距离始终等于目标居中位置，不会越界。
 *
 * 因此本类不再默认做 stopScroll()（见 STOP_IN_FLIGHT），而是提供：
 * 1. 实机诊断日志（DEBUG_LOG），用于确认异常到底是单次超大 dy、焦点跨多项，
 *    还是短时间内连续多次正常请求；
 * 2. 一个可开关的 A/B 加固（STOP_IN_FLIGHT）：在"新焦点滚动请求打断进行中的
 *    平滑滚动"场景下先 stopScroll()，观察是否改善顿挫/跳变。
 *
 * 静态开关说明（每次修改需重新构建）：
 * - DEBUG_LOG=true 时每触发一次焦点滚动请求打一条 INFO 日志，标签 FocusScroll；
 *   实机复现后抓 logcat 回传。确认问题后应置回 false。
 * - STOP_IN_FLIGHT=true 启用"打断进行中平滑滚动"的加固；false 为纯观察模式，
 *   用于 A/B 对比。
 */
public class TvGridView extends TvRecyclerView {

    private static final String TAG = "FocusScroll";

    /** 每请求打印诊断日志（实机抓 logcat 用；生产构建前置回 false） */
    public static boolean DEBUG_LOG = true;

    /** A/B 开关：发起新焦点滚动前，若处于 SETTLING 则先 stopScroll() */
    public static boolean STOP_IN_FLIGHT = true;

    private long mLastRequestAt;
    private int mLastPos = -1;

    public TvGridView(Context context) {
        super(context);
    }

    public TvGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public TvGridView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean requestChildRectangleOnScreen(View child, Rect rect, boolean immediate) {
        final long now = SystemClock.uptimeMillis();
        if (DEBUG_LOG) {
            int pos = child != null ? getChildAdapterPosition(child) : -1;
            int overhang = child != null
                    ? rect.bottom + getPaddingBottom() - getHeight()
                    : 0; // 近似请求的滚动量：child 底部相对视口底部的越界量
            Log.i(TAG, "req pos=" + pos
                    + " focused=" + getSelectedPosition()
                    + " state=" + scrollStateName(getScrollState())
                    + " first=" + getFirstVisiblePosition()
                    + " last=" + getLastVisiblePosition()
                    + " immediate=" + immediate
                    + " childFocused=" + (child != null && child.hasFocus())
                    + " overhang=" + overhang
                    + " dPos=" + (pos - mLastPos)
                    + " sinceLast=" + (now - mLastRequestAt) + "ms");
            mLastPos = pos;
        }
        if (STOP_IN_FLIGHT
                && !immediate
                && child != null
                && child.hasFocus() // 仅 DPAD 焦点导航路径（IME/无障碍/程序化请求通常不带焦点）
                && getScrollState() == RecyclerView.SCROLL_STATE_SETTLING) {
            stopScroll();
        }
        mLastRequestAt = now;
        return super.requestChildRectangleOnScreen(child, rect, immediate);
    }

    private static String scrollStateName(int state) {
        switch (state) {
            case RecyclerView.SCROLL_STATE_IDLE:
                return "IDLE";
            case RecyclerView.SCROLL_STATE_DRAGGING:
                return "DRAGGING";
            case RecyclerView.SCROLL_STATE_SETTLING:
                return "SETTLING";
            default:
                return String.valueOf(state);
        }
    }
}
