package cn.modou.app;

import android.app.Activity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 从原生屏（书架 / 详情 / 目录）打开原生阅读页。
 *
 * 与网页版入口的区别：网页版那边正文已经渲染过，直接把段落交给原生即可；
 * 从原生屏进来时没人给过正文，所以这里自己去 chapters 表取，用 Paras 解析成
 * 段落。取不到（该章还没缓存）就留空，交给阅读页走桥向书源要——
 * 所以「点开未读章节」依然可用，只是首屏要等一次网络。
 */
final class ReaderLaunch {

    private ReaderLaunch() {
    }

    /**
     * @param index 起始章序号；小于 0 时按 0 处理
     * @param bridge 可为 null（MainActivity 已销毁）：此时未缓存的章节取不到，
     *               但已缓存的书照样能读，不该因此打不开
     */
    static void open(Activity from, ModouBridge bridge, JSONObject book, int index) {
        try {
            ModouStore store = ModouStore.get(from);
            String bookId = book.optString("id", "");
            if (bookId.isEmpty()) return;

            // 只带「在哪本书、第几章」这种小数据，正文和目录由阅读页自己去后台取。
            //
            // 以前这里把整份目录（几千章的 titles[]）和本章正文塞进 Intent 的 String
            // 里一起送过去：书籍一多，payload 能到几百 KB～几 MB，跨进程传输本身就要
            // 几百毫秒，超过 Binder 上限还会直接抛 TransactionTooLargeException ——
            // 表现就是「点了封面半天打不开，然后自己退回书架」。
            int total = book.optInt("chapterCount", 0);
            JSONArray chapters = book.optJSONArray("chapters");
            if (chapters != null && chapters.length() > 0) total = chapters.length();
            if (total <= 0) total = store.chapterCount(bookId);
            int idx = total > 0 ? Math.max(0, Math.min(index, total - 1)) : Math.max(0, index);

            JSONObject p = new JSONObject();
            p.put("bookId", bookId);
            p.put("bookTitle", book.optString("name", ""));
            p.put("fromNative", true); // 原生入口：没有网页会话，取章别去问网页桥
            p.put("index", idx);
            p.put("total", total);
            p.put("title", "");
            p.put("offset", nativeOffset(store, bookId, idx));
            p.put("fontSize", num(store, "readerFontSize", 18));
            p.put("lineHeight", num(store, "readerLineHeight", 2.0));
            p.put("paraGap", num(store, "readerParaGap", 1.0));
            p.put("margin", (int) num(store, "readerMargin", 16));
            p.put("font", store.settingStr("readerFontFamily", "song"));
            p.put("weight", (int) num(store, "readerFontWeight", 500));
            p.put("justify", num(store, "readerJustify", 1) >= 0.5);
            p.put("theme", store.settingStr("readerLightTheme", "paper"));
            p.put("brightness", num(store, "readerBrightness", 1.0));

            NativeReaderActivity.launch(from, bridge, p.toString());
        } catch (Exception e) {
            ModouUi.toast(from, "打开阅读页失败：" + e.getMessage());
        }
    }

    /**
     * 只在同一章时恢复上次的章内位置：换章后旧偏移没有意义。
     *
     * <p>{@code nativeOffset} 是原生页自己写的（排版后的字符偏移）。没有这个字段的是
     * 老网页版留下的进度 —— 它只记 {@code offset}，语义同样是「章内第几个字符」。
     * 直接用它可以接着上次的位置读，而不是每次都从章首重新开始。
     */
    private static int nativeOffset(ModouStore store, String bookId, int idx) {
        try {
            String s = store.get("progress", bookId);
            if (s == null) return 0;
            JSONObject o = new JSONObject(s);
            if (o.optInt("index", -1) != idx) return 0;
            if (o.has("nativeOffset")) return Math.max(0, o.optInt("nativeOffset", 0));
            return Math.max(0, o.optInt("offset", 0));   // 网页版老进度
        } catch (Exception e) {
            return 0;
        }
    }

    private static double num(ModouStore store, String key, double dft) {
        String raw = store.settingRaw(key);
        if (raw == null) return dft;
        try {
            return new JSONArray("[" + raw + "]").optDouble(0, dft);
        } catch (Exception e) {
            return dft;
        }
    }
}
