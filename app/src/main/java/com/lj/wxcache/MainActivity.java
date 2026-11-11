package com.lj.wxcache;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    /** 桌面快捷方式使用的 action，也可用作判断依据。 */
    public static final String ACTION_QUICK_CLEAN = "com.lj.wxcache.action.QUICK_CLEAN";
    public static final String EXTRA_QUICK_CLEAN = "quick_clean";
    private static final String SHORTCUT_ID = "quick_clean_wx";

    private TextView tvRoot, tvSummary, tvLog;
    private Button btnClean, btnShortcut;
    private LinearLayout llList;
    private ScrollView logScroll;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private List<WxClean.Instance> instances = new ArrayList<>();
    private boolean quickClean = false;
    private boolean postClean = false;
    private volatile boolean cleaning = false;
    private volatile boolean rootRequesting = false;
    private boolean firstResume = true;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        tvRoot = findViewById(R.id.tvRoot);
        tvSummary = findViewById(R.id.tvSummary);
        tvLog = findViewById(R.id.tvLog);
        llList = findViewById(R.id.llList);
        logScroll = findViewById(R.id.logScroll);
        btnClean = findViewById(R.id.btnClean);
        btnShortcut = findViewById(R.id.btnShortcut);

        btnClean.setOnClickListener(v -> clean());
        btnShortcut.setOnClickListener(v -> createShortcut());

        quickClean = isQuickClean(getIntent());
        // 打开立即请求 root：马上发起 su，触发 Magisk / KernelSU 授权弹窗
        requestRoot();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (isQuickClean(intent)) {
            quickClean = true;
            requestRoot();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // onCreate 之后系统会立即回调一次 onResume，跳过这次
        if (firstResume) {
            firstResume = false;
            return;
        }
        // 从后台切回前台：再执行一次清理
        if (cleaning || rootRequesting) return;
        if (instances == null || instances.isEmpty()) {
            requestRoot();
        } else {
            tvLog.append("\n检测到切回前台，重新清理…\n");
            clean();
        }
    }

    private boolean isQuickClean(Intent i) {
        if (i == null) return false;
        return i.getBooleanExtra(EXTRA_QUICK_CLEAN, false)
                || ACTION_QUICK_CLEAN.equals(i.getAction());
    }

    /** 立刻发起 su 请求；未授权时弹窗提示，并支持重新请求。 */
    private void requestRoot() {
        rootRequesting = true;
        tvRoot.setText("正在请求 root 权限…（请在 Magisk / KernelSU 弹窗中允许）");
        tvSummary.setText("");
        postClean = false;
        btnClean.setEnabled(false);
        btnShortcut.setEnabled(false);
        RootShell.resetRoot();   // 重新请求时清掉上次结果，确保再次触发 su
        new Thread(() -> {
            boolean root = RootShell.rootAvailable();
            // 先快速定位实例（不算 du），界面立刻有内容；占用随后异步查询
            List<WxClean.Instance> list = root ? WxClean.detect(false) : new ArrayList<>();
            ui.post(() -> {
                rootRequesting = false;
                btnShortcut.setEnabled(true);
                if (root) {
                    tvRoot.setText("Root 权限：已获取 ✓");
                    instances = list;
                    render(list);
                    btnClean.setEnabled(!list.isEmpty());

                    // 打开 app 立即清理，不等占用查询
                    if (!list.isEmpty()) {
                        tvLog.setText(quickClean ? "桌面快捷方式启动\n" : "");
                        quickClean = false;
                        tvLog.append("立即开始清理…\n");
                        clean();
                    }
                } else {
                    tvRoot.setText("Root 权限：未获取 ✗");
                    tvSummary.setText("未获取 root 权限。请在本机的 Magisk / KernelSU 中允许本应用（"
                            + getPackageName() + "）。");
                    btnClean.setEnabled(false);
                    showRootTip();
                }
            });
        }).start();
    }

    /** 清理完成后异步刷新剩余占用，不阻塞清理。 */
    private void queryRemainingSizes(List<WxClean.Instance> list) {
        postClean = true;
        new Thread(() -> {
            for (WxClean.Instance i : list) {
                long sz = WxClean.sizeMB(i.dataDir);
                i.sizeMB = sz;
                ui.post(() -> render(list));
            }
        }).start();
    }

    /**
     * 创建“一键清理微信”的桌面快捷方式。
     * Android 8+ 用 ShortcutManager.requestPinShortcut（系统会弹添加确认）；
     * 更老版本退化为 INSTALL_SHORTCUT 广播。
     */
    private void createShortcut() {
        Intent target = new Intent(this, MainActivity.class);
        target.setAction(ACTION_QUICK_CLEAN);
        target.putExtra(EXTRA_QUICK_CLEAN, true);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ShortcutManager sm = getSystemService(ShortcutManager.class);
                if (sm == null || !sm.isRequestPinShortcutSupported()) {
                    Toast.makeText(this, "当前桌面不支持自动添加快捷方式", Toast.LENGTH_LONG).show();
                    return;
                }
                ShortcutInfo info = new ShortcutInfo.Builder(this, SHORTCUT_ID)
                        .setShortLabel("清理微信")
                        .setLongLabel("一键清理微信浏览器缓存")
                        .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                        .setIntent(target)
                        .build();
                boolean ok = sm.requestPinShortcut(info, null);
                Toast.makeText(this, ok ? "请在弹出的窗口中点「添加」" : "添加失败，请检查桌面设置",
                        Toast.LENGTH_LONG).show();
            } else {
                Intent legacy = new Intent("com.android.launcher.action.INSTALL_SHORTCUT");
                legacy.putExtra(Intent.EXTRA_SHORTCUT_NAME, "清理微信");
                legacy.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                        Intent.ShortcutIconResource.fromContext(this, R.mipmap.ic_launcher));
                legacy.putExtra(Intent.EXTRA_SHORTCUT_INTENT, target);
                sendBroadcast(legacy);
                Toast.makeText(this, "已请求创建快捷方式", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "创建快捷方式失败：" + e, Toast.LENGTH_LONG).show();
        }
    }

    private void showRootTip() {
        new AlertDialog.Builder(this)
                .setTitle("需要 Root 权限")
                .setMessage("本应用需要 root 才能访问微信的浏览器缓存目录。\n\n"
                        + "请在弹出的 root 授权窗口中点「允许」；\n"
                        + "如果没有弹窗，请到 Magisk / KernelSU 的超级用户列表里，把本应用设为允许。\n\n"
                        + "诊断：" + RootShell.LAST_ERROR)
                .setPositiveButton("重新请求", (d, w) -> requestRoot())
                .setNegativeButton("关闭", null)
                .show();
    }

    private void render(List<WxClean.Instance> list) {
        llList.removeAllViews();
        if (list.isEmpty()) {
            tvSummary.setText("未检测到已安装的微信（" + WxClean.PKG + "）。");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("检测到 ").append(list.size()).append(" 个微信实例")
                .append(list.size() > 1 ? "（多用户 / 多开）" : "（仅主用户）").append("\n");
        sb.append("只清网页/小程序缓存（Cookie / LocalStorage / JS / HTTP），保留内核组件：\n");
        sb.append("  app_webview* + app_xweb_data/{Default,Profile*}\n")
                .append("  cache/*web* + MicroMsg/{jscache,CronetCache,...}\n\n");
        for (WxClean.Instance i : list) {
            String sizeText = i.sizeMB > 0
                    ? ((postClean ? "清理后约 " : "约 ") + i.sizeMB + " MB")
                    : "占用查询中…";
            sb.append("· ").append(i.label).append("（用户").append(i.userId)
                    .append("）：").append(sizeText).append("\n   ")
                    .append(i.dataDir).append("\n");
            TextView t = new TextView(this);
            t.setText("▶ " + i.label + " / 用户" + i.userId + "  ·  " + sizeText);
            t.setPadding(0, 8, 0, 8);
            llList.addView(t);
        }
        tvSummary.setText(sb.toString());
    }

    private void scrollDown() {
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void clean() {
        cleaning = true;
        btnClean.setEnabled(false);
        tvLog.append("\n开始按固定目录清理…\n");
        scrollDown();
        final List<WxClean.Instance> targets = instances;
        new Thread(() -> {
            StringBuilder total = new StringBuilder();
            for (WxClean.Instance i : targets) {
                total.append("【").append(i.label).append(" / 用户").append(i.userId).append("】\n");
                total.append(WxClean.clean(i)).append("\n");
            }
            ui.post(() -> {
                tvLog.append(total.toString());
                tvLog.append("✅ 全部完成。\n");
                btnClean.setEnabled(true);
                cleaning = false;
                scrollDown();
                // 容量统计放异步线程，不阻塞删除
                queryRemainingSizes(targets);
            });
        }).start();
    }
}
