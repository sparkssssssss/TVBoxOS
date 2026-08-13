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
 * 1. 实机诊断日志（DEBUG_LOG，默认关闭，避免诊断代码改变问题发生概率）；
 *    用于确认异常到底是单次超大 dy、焦点跨多项，还是短时间内连续多次正常请求；
 * 2. 一个可开关的 A/B 加固（STOP_IN_FLIGHT）：在"新焦点滚动请求打断进行中的
 *    平滑滚动"场景下先 stopScroll()，观察是否改善顿挫/跳变。
 *
 * 静态开关说明（每次修改需重新构建）：
 * - DEBUG_LOG=true 时每触发一次焦点滚动请求打一条 INFO 日志，标签 FocusScroll；
 *   实机复现后抓 logcat 回传。确认问题后应置回 false。
 * - STOP_IN_FLIGHT=true 启用"打断进行中平滑滚动"的加固；false 为纯观察模式，
 *   用于 A/B 对比。
 *
 * 注意：即使收窄到 SETTLING + child.hasFocus() + !immediate，仍无法完全区分
 * DPAD 焦点导航与程序化 SmoothScroller 期间触发的 rectangle 请求，stopScroll()
 * 仍会停止 ViewFlinger 与 LayoutManager SmoothScroller。该条件只是缩小了误伤面
 * （排除 immediate、触摸 DRAGGING、明显非焦点请求），电视端需回归
 * scrollToPosition/setSelectionWithSmooth/返回页面焦点恢复/子分类切换/load-more。
 */
public class TvGridView extends TvRecyclerView {

    private static final String TAG = "FocusScroll";

    /** 每请求打印诊断日志；默认关闭，诊断时改为 true 重新构建 */
    public static boolean DEBUG_LOG = false;

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
            // 与库内 getChildRectangleOnScreenScrollAmount2 相同的取界方式：
            // 用 decorated bounds + padding 计算相对视口的真实越界量（未过 computeScrollOffset）。
            int overhang = 0;
            boolean vertical = getLayoutManager() != null && getLayoutManager().canScrollVertically();
            if (child != null) {
                Rect decorated = new Rect();
                getDecoratedBoundsWithMargins(child, decorated);
                overhang = vertical
                        ? decorated.bottom + getPaddingBottom() - getHeight()
                        : decorated.right + getPaddingRight() - getWidth();
            }
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
        // 收窄条件：仅打断"进行中的平滑滚动被新焦点请求替换"的场景，缩小误伤面；
        // 无法完全区分 DPAD 与程序化 SmoothScroller，见类注释的回归清单。
        if (STOP_IN_FLIGHT
                && !immediate
                && child != null
                && child.hasFocus()
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
