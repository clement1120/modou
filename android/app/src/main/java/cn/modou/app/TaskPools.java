package cn.modou.app;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全局线程池：把「等网络」和「算东西」分开，再配一个专管超时的定时器。
 *
 * <p>以前全项目只有主线程 + WebView 里的 JS 线程：网络在 JS 里等、正则也在 JS 里算，
 * 一个写错的正则能把整条链路一起冻住。这里把三类活彻底分家：
 * <ul>
 *   <li>{@link #io()}  —— 网络、文件，8 条。阻塞式等待，不能被 CPU 活挤占；</li>
 *   <li>{@link #cpu()} —— 正则、解析、字符串处理，核数-1（2~4 条）。</li>
 *       daemon + 低半档优先级，抢不到核也不会拖慢绘制；</li>
 *   <li>{@link #timer()} —— 只跑 {@code postDelayed} 式的看门狗，1 条，永不阻塞。</li>
 * </ul>
 *
 * <p>为什么不给线程设名字就完事：出问题时 {@code adb shell top -H} 能直接看到
 * modou-io-3 / modou-cpu-1，知道自己卡在哪一类活上，比一屏 "Thread-47" 有用。
 */
final class TaskPools {

    /** 主线程 Handler：所有回调都从这里回 UI，绝不在工作线程碰 View */
    static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 8 条：与 JS 引擎内部的 worker 数一致，再多只会把手机网络栈和站点风控同时踩满 */
    private static final int IO_THREADS = 8;

    private static ExecutorService io;
    private static ExecutorService cpu;
    private static ScheduledExecutorService timer;

    private TaskPools() {
    }

    static synchronized ExecutorService io() {
        if (io == null) io = Executors.newFixedThreadPool(IO_THREADS, named("modou-io", false));
        return io;
    }

    static synchronized ExecutorService cpu() {
        if (cpu == null) {
            int n = Runtime.getRuntime().availableProcessors() - 1;
            if (n < 2) n = 2;
            if (n > 4) n = 4; // 手机上开更多只会互相抢缓存
            cpu = Executors.newFixedThreadPool(n, named("modou-cpu", true));
        }
        return cpu;
    }

    static synchronized ScheduledExecutorService timer() {
        if (timer == null) {
            timer = Executors.newSingleThreadScheduledExecutor(named("modou-timer", true));
        }
        return timer;
    }

    /**
     * 排一个看门狗。返回的 future 一定要在正常完成时 cancel ——
     * 不 cancel 的话它会一直攥着任务引用，直到超时才放手。
     */
    static ScheduledFuture<?> after(long ms, Runnable r) {
        return timer().schedule(r, Math.max(1L, ms), TimeUnit.MILLISECONDS);
    }

    /** 回主线程执行；已经是主线程也 post，保证调用点在哪个线程都一致 */
    static void ui(Runnable r) {
        MAIN.post(r);
    }

    private static ThreadFactory named(String prefix, boolean daemon) {
        final AtomicInteger n = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.getAndIncrement());
            t.setDaemon(daemon);
            if (daemon) t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        };
    }
}
