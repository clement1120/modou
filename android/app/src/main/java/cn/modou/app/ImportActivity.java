package cn.modou.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 「导入本地书籍」页 —— 只做一件事：**选一个放书的文件夹**。
 *
 * v0.6.127：以前这页还带着「扫描本机书籍 / 重新扫描 / 全不选 / 导入 N 本」那一整套
 * 批量导入，选完文件夹之后这些东西全是用不上的旧流程残留（书已经按文件夹列在下一页了）。
 * 现在这里只剩：选文件夹（选完直接进文件夹页），以及一个兜底的文件选择入口
 * ——微信/QQ 存在 Android/data 里的书，系统的文件夹列表里根本看不到。
 *
 * 以后从书架点「导入」会直接进 {@link LocalBooksActivity}，只有还没选过文件夹时才落到这页。
 */
public class ImportActivity extends Activity {

    private static final int REQ_PICK = 4711;
    private static final long MAX_BYTES = 60L * 1024 * 1024;

    private ModouUi ui;
    private LinearLayout body;
    private TextView folderValue;
    private TextView progress;
    private boolean busy;

    static void launch(Activity from) {
        from.startActivity(new Intent(from, ImportActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ModouUi.applyTheme(this);
        super.onCreate(savedInstanceState);
        // 选过文件夹就直接进那个文件夹的页面
        if (!getIntent().hasExtra("manual") && LocalBooksActivity.hasFolder(this)) {
            LocalBooksActivity.launch(this);
            finish();
            return;
        }
        ui = ModouUi.of(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.bg);

        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = ModouUi.dp(this, 14);
        body.setPadding(p, p + statusBarPx(), p, p);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        paint();
    }

    private int statusBarPx() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : ModouUi.dp(this, 24);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!busy && ModouUi.syncTheme(this)) return;
        if (folderValue != null) folderValue.setText(currentPlace());
    }

    /* ---------------- 页面 ---------------- */

    private void paint() {
        body.removeAllViews();

        TextView back = ModouUi.tv(this, "‹ 返回", 14, ui.accent);
        back.setPadding(0, ModouUi.dp(this, 2), 0, ModouUi.dp(this, 8));
        back.setOnClickListener((v) -> finish());
        body.addView(back);

        TextView title = ModouUi.tv(this, "导入本地书籍", 26, ui.text);
        title.setTypeface(Typeface.SERIF, Typeface.BOLD);
        title.setPadding(0, 0, 0, ModouUi.dp(this, 10));
        body.addView(title);

        TextView intro = ModouUi.tv(this,
                "选一个专门放书的文件夹。以后从书架点「导入」就直接列出这个文件夹里的 .txt / .epub"
                        + "（单本不超过 60MB），点一下就进书架；导入过的会标出来，不会重复加。",
                13, ui.sub);
        intro.setLineSpacing(ModouUi.dp(this, 3), 1f);
        intro.setPadding(0, 0, 0, ModouUi.dp(this, 16));
        body.addView(intro);

        body.addView(buildFolderCard());
        body.addView(buildFileCard());

        progress = ModouUi.tv(this, "", 13, ui.sub);
        progress.setLineSpacing(ModouUi.dp(this, 3), 1f);
        progress.setPadding(ModouUi.dp(this, 2), ModouUi.dp(this, 14), 0, 0);
        progress.setVisibility(TextUtils.isEmpty(progress.getText()) ? View.GONE : View.VISIBLE);
        body.addView(progress);
    }

    private View buildFolderCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ModouUi.dp(this, 16), ModouUi.dp(this, 14),
                ModouUi.dp(this, 16), ModouUi.dp(this, 16));
        card.setBackground(ModouUi.round(ui.card, 16));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = ModouUi.tv(this, "书籍文件夹", 15, ui.text);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(h, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        folderValue = ModouUi.tv(this, currentPlace(), 12.5f,
                LocalBooksActivity.hasFolder(this) ? ui.text : ui.sub);
        folderValue.setSingleLine(true);
        folderValue.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        folderValue.setGravity(Gravity.END);
        row.addView(folderValue, new LinearLayout.LayoutParams(
                ModouUi.dp(this, 150), ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(row);

        TextView btn = ModouUi.tv(this, LocalBooksActivity.hasFolder(this) ? "重新选择文件夹" : "选择文件夹",
                15, 0xFFFFFFFF);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(0, ModouUi.dp(this, 13), 0, ModouUi.dp(this, 13));
        btn.setBackground(ModouUi.round(ui.accent, 12));
        btn.setOnClickListener((v) -> chooseFolder());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = ModouUi.dp(this, 12);
        card.addView(btn, blp);

        TextView tip = ModouUi.tv(this,
                "系统会记住这个授权，下次不用再选。原文件留在你的文件夹里，我们只读取、不复制，"
                        + "所以不会额外占空间。",
                12, ui.sub);
        tip.setLineSpacing(ModouUi.dp(this, 2), 1f);
        tip.setPadding(0, ModouUi.dp(this, 10), 0, 0);
        card.addView(tip);
        return card;
    }

    private View buildFileCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ModouUi.dp(this, 16), ModouUi.dp(this, 14),
                ModouUi.dp(this, 16), ModouUi.dp(this, 16));
        card.setBackground(ModouUi.round(ui.card, 16));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = ModouUi.dp(this, 12);
        card.setLayoutParams(clp);

        TextView h = ModouUi.tv(this, "不在文件夹里？直接选文件", 15, ui.text);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(h);

        TextView tip = ModouUi.tv(this,
                "微信、QQ 下载的书常放在 Android/data 里，系统的文件夹列表看不到，"
                        + "这里可以一次挑一本或多本直接导入。",
                12, ui.sub);
        tip.setLineSpacing(ModouUi.dp(this, 2), 1f);
        tip.setPadding(0, ModouUi.dp(this, 6), 0, ModouUi.dp(this, 10));
        card.addView(tip);

        TextView btn = ModouUi.tv(this, "选择文件导入", 14, ui.accent);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(0, ModouUi.dp(this, 11), 0, ModouUi.dp(this, 11));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x00000000);
        g.setCornerRadius(ModouUi.dp(this, 12));
        g.setStroke(Math.max(1, ModouUi.dp(this, 1.2f)), ui.accent);
        btn.setBackground(g);
        btn.setOnClickListener((v) -> pickFiles());
        card.addView(btn);
        return card;
    }

    private String currentPlace() {
        String p = LocalBooksActivity.prefs(this)
                .getString(LocalBooksActivity.PREF_PLACE, "");
        return TextUtils.isEmpty(p) ? "未选择" : p;
    }

    /* ---------------- 选文件夹 / 选文件 ---------------- */

    private void chooseFolder() {
        if (!LocalBooksActivity.pickFolder(this)) {
            // 少数 ROM 没有目录选择器：退回「选文件」，不留死在死路上
            pickFiles();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == LocalBooksActivity.REQ_TREE) {
            if (LocalBooksActivity.handleResult(this, req, res, data)) {
                LocalBooksActivity.launch(this);
                finish();
            }
            return;
        }
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        Uri one = data.getData();
        if (one != null) uris.add(one);
        android.content.ClipData cd = data.getClipData();
        if (cd != null) {
            for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        }
        if (uris.isEmpty()) return;
        importFiles(uris);
    }

    private void pickFiles() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            i.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"text/plain", "application/epub+zip"});
            startActivityForResult(Intent.createChooser(i, "选择 TXT / EPUB"), REQ_PICK);
        } catch (Exception e) {
            say("打不开文件选择器：" + e.getMessage());
        }
    }

    /** 兜底入口也走同一条串行队列：一次选十本也只是排队导，不会同时开十个解析 */
    private void importFiles(List<Uri> uris) {
        busy = true;
        say("正在导入 " + uris.size() + " 个文件…");
        int[] done = {0};
        int[] ok = {0};
        for (Uri u : uris) {
            String name = nameOf(u);
            long size = sizeOf(u);
            LocalImport.enqueue(this, u, name, size, new LocalImport.Callback() {
                @Override
                public void onStage(String text) {
                    if (progress != null) {
                        progress.setVisibility(View.VISIBLE);
                        progress.setText(text);
                    }
                }

                @Override
                public void onDone(LocalImport.Res r) {
                    done[0]++;
                    if (r.ok) ok[0]++;
                    if (done[0] >= uris.size()) {
                        busy = false;
                        if (progress != null) {
                            progress.setVisibility(View.VISIBLE);
                            progress.setText("导入完成：" + ok[0] + " 本成功"
                                    + (uris.size() - ok[0] > 0
                                    ? "，" + (uris.size() - ok[0]) + " 本失败（" + r.msg + "）" : ""));
                        }
                        ModouUi.toast(ImportActivity.this,
                                "导入完成：" + ok[0] + "/" + uris.size());
                    }
                }
            });
        }
    }

    private void say(String s) {
        if (progress == null) return;
        progress.setVisibility(View.VISIBLE);
        progress.setText(s);
    }

    private String nameOf(Uri u) {
        String n = "";
        try (android.database.Cursor c = getContentResolver().query(u,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) n = c.getString(0);
        } catch (Exception ignore) {
            /* 拿不到就用 Uri 最后一段 */
        }
        if (n == null) n = "";
        if (n.isEmpty()) {
            String s = String.valueOf(u.getLastPathSegment());
            int i = s.lastIndexOf('/');
            n = i >= 0 ? s.substring(i + 1) : s;
        }
        return n;
    }

    private long sizeOf(Uri u) {
        try (android.database.Cursor c = getContentResolver().query(u,
                new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignore) {
            /* 问不到就让 LocalImport 按实际读到的算 */
        }
        return 0;
    }
}
