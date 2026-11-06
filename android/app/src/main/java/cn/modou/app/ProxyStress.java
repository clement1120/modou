package cn.modou.app;

import android.app.Activity;
import android.os.SystemClock;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 代理层并发自测（开发者工具，入口藏在「设置 → 关于 → 版本号连点 5 次」）。
 *
 * <p>为什么要这个：NanoHTTPD 每个连接起一条线程，句柄漏没漏只有真机压一下才知道。
 * 静态审查看不出「Response 流没关」「异常路径没 close」这类问题 —— 它们在正常路径上
 * 完全正常，只有并发 + 大量短连接才会把 fd 堆到 limit。
 *
 * <p>打的是 `/__ping`：不碰网络、不读 assets、不查数据库，只回 4 个字节。
 * 这样测的纯粹是「本地服务能不能同时收 200 个连接并干净地放掉」，
 * 不会被 /proxy 的外网耗时污染。
 *
 * <p>看三个数：
 * <ul>
 *   <li>成功率：200 个里回来了几个；</li>
 *   <li>句柄峰值：基线 + 200 左右是正常的（每个连接至少 1 个 fd）；</li>
 *   <li>句柄残留：等 3 秒后基线之上还剩几个。稳定在个位数说明连接都关了；
 *       几十上百就是泄漏（或还在 TIME_WAIT，可以再等 30 秒复测一次）。</li>
 * </ul>
 */
final class ProxyStress {

    private static final String TAG = "ProxyStress";
    private static final int CONCURRENCY = 200;
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 6000;

    private ProxyStress() {
    }

    static void run(final Activity a) {
        if (a == null || a.isFinishing()) return;
        ModouUi.confirm(a, "代理层压测",
                "向本地服务（127.0.0.1:" + MainActivity.PORT + "）同时发 " + CONCURRENCY
                        + " 个请求，看句柄和内存会不会失控。\n\n"
                        + "全程约 5~10 秒，期间别切后台。结果只用于自测，不影响任何数据。",
                () -> start(a));
    }

    private static void start(final Activity a) {
        ModouUi.toast(a, "压测中…（" + CONCURRENCY + " 并发）");
        final int port = MainActivity.PORT;
        new Thread(() -> {
            int fd0 = fdCount();
            long mem0 = usedMem();
            final AtomicInteger ok = new AtomicInteger();
            final AtomicInteger bad = new AtomicInteger();
            final AtomicInteger peak = new AtomicInteger(fd0);
            final CountDownLatch done = new CountDownLatch(CONCURRENCY);
            final long t0 = SystemClock.elapsedRealtime();

            // 采样线程：压测只有几百毫秒，结束后再量就量不到峰值了
            Thread sampler = new Thread(() -> {
                while (done.getCount() > 0) {
                    int n = fdCount();
                    if (n > peak.get()) peak.set(n);
                    try {
                        Thread.sleep(15);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "stress-sampler");
            sampler.setDaemon(true);
            sampler.start();

            ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
            for (int i = 0; i < CONCURRENCY; i++) {
                pool.execute(() -> {
                    try {
                        if (ping(port)) ok.incrementAndGet();
                        else bad.incrementAndGet();
                    } catch (Throwable t) {
                        bad.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            boolean finished;
            try {
                finished = done.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                finished = false;
            }
            pool.shutdownNow();
            long cost = SystemClock.elapsedRealtime() - t0;

            // 等 3 秒再量：让 finally / close 走完，TIME_WAIT 也会先退一批
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignore) {
                /* 无所谓 */
            }
            int fd1 = fdCount();
            long mem1 = usedMem();

            final String report = build(finished, ok.get(), bad.get(), cost,
                    fd0, peak.get(), fd1, mem0, mem1);
            Log.i(TAG, report.replace('\n', ' '));
            if (a.isFinishing()) return;
            a.runOnUiThread(() -> ModouUi.alert(a, "代理层压测结果", report));
        }, "proxy-stress").start();
    }

    /** 一次原始 HTTP 请求：单独开 socket，读完立刻 close，不经连接池 */
    private static boolean ping(int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            OutputStream os = s.getOutputStream();
            os.write(("GET /__ping HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Connection: close\r\n\r\n").getBytes("ISO-8859-1"));
            os.flush();
            InputStream is = s.getInputStream();
            byte[] buf = new byte[64];
            int n = is.read(buf);
            if (n <= 0) return false;
            String head = new String(buf, 0, n, "ISO-8859-1");
            return head.startsWith("HTTP/1.1 200") || head.startsWith("HTTP/1.0 200");
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Throwable ignore) {
                /* 已经断了 */
            }
        }
    }

    /** 当前进程打开的 fd 数；取不到返回 -1（某些 ROM 隐藏了 /proc/self/fd） */
    private static int fdCount() {
        try {
            String[] f = new java.io.File("/proc/self/fd").list();
            return f == null ? -1 : f.length;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static long usedMem() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static String build(boolean finished, int ok, int bad, long cost,
                                int fd0, int peakFd, int fd1, long m0, long m1) {
        StringBuilder sb = new StringBuilder();
        sb.append("并发 ").append(CONCURRENCY)
                .append(" · 成功 ").append(ok)
                .append(" · 失败 ").append(bad).append('\n');
        sb.append("耗时 ").append(cost).append("ms")
                .append(ok > 0 ? "（单次平均 " + Math.max(0, cost / Math.max(1, ok)) + "ms）" : "")
                .append('\n');
        if (!finished) sb.append("⚠ 有请求在 60 秒内没回来，已强制收尾\n");

        sb.append('\n');
        if (fd0 < 0 || fd1 < 0) {
            sb.append("句柄：本机不开放 /proc，读不到\n");
        } else {
            int leak = fd1 - fd0;
            sb.append("句柄：压测前 ").append(fd0)
                    .append(" → 峰值 ").append(peakFd)
                    .append(" → 3 秒后 ").append(fd1).append('\n');
            sb.append("残留：").append(leak >= 0 ? "+" + leak : String.valueOf(leak)).append('\n');
            if (leak <= 8) sb.append("结论：连接都放掉了，没有泄漏迹象\n");
            else sb.append("结论：残留偏多，可能是 TIME_WAIT 还没退，等 30 秒再压一次；"
                    + "若仍偏高就是句柄没关\n");
        }

        sb.append('\n').append("内存：")
                .append(m0 / 1024 / 1024).append("MB → ").append(m1 / 1024 / 1024).append("MB");
        long d = (m1 - m0) / 1024;
        if (d > 4096) sb.append("（涨了 ").append(d / 1024).append("MB，注意）");
        else if (d < -512) sb.append("（回收后回落正常）");
        return sb.toString();
    }
}
