package com.lj.wxcache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信多实例 + 浏览器缓存/Cookie 清理。
 *
 * 清理策略：使用固定的缓存目录名（含通配符）直接 rm -rf，
 * 不再逐个 ls 扫描 WeChat dataDir 的子目录。
 *
 * 安全红线：只删网页/小程序缓存（Cookie / LocalStorage / IndexedDB / HTTP / JS），
 * 保留浏览器组件内核（XWalk / X5 / XWeb / Cronet）；
 * 不碰聊天记录、databases、shared_prefs、lib、小程序包本体。
 * MicroMsg 下仅删除明确的缓存子目录（jscache / CronetCache /
 * appbrand/web_renderingcache / mmslot/webcached）。
 */
public class WxClean {

    public static final String PKG = "com.tencent.mm";

    /**
     * 只清网页数据：Cookie、LocalStorage、IndexedDB、Session Storage、
     * HTTP Cache（JS/CSS/图片）、Code Cache 等。
     *
     * **保留浏览器组件内核**：
     *   app_xweb_data/xweb_*    XWeb 内核（约 250MB，删了会重新下载）
     *   app_xwalk*              XWalk 内核
     *   app_tbs / app_x5        X5 内核
     *   app_WxaCronet           Cronet 网络组件
     *   files/tbs, files/x5     组件资源
     *
     * 注意：`Profile*` 用通配符匹配 "Profile 1"（目录名带空格，
     * 直接写死会因未加引号被拆成两个参数）。
     */
    public static final String[] BROWSER_GLOBS = {
            // WebView 网页 profile 数据
            "app_webview*",
            "app_webviewcache",
            "app_appcache",
            "app_textures",

            // XWeb 网页 profile 数据（Cookie / LocalStorage / IndexedDB / Session Storage）
            // 保留 app_xweb_data/xweb_* 内核
            "app_xweb_data/Default",
            "app_xweb_data/Profile*",
            "app_xweb_data/pref_store",
            "app_xweb_data/last-exit-info",
            "app_xweb_data/textures",

            // 网页 HTTP 缓存（JS/CSS/图片）——打开网页仍用缓存 JS 的就是这里
            "cache/webview_com_tencent_mm*",
            "cache/xweb_cache",
            "cache/appbrand/jscache",
            "cache/appbrand/snapshotjscache",
            "cache/appbrand/wasmcache",
            "cache/appbrand/shadercache",
            "cache/skyline_cache",

            // 小程序网页/JS/渲染缓存（只删这些 cache 子目录，
            // 不碰 MicroMsg 下的聊天记录、小程序包本体等其它数据）
            "MicroMsg/jscache",
            "MicroMsg/CronetCache",
            "MicroMsg/appbrand/web_renderingcache",
            "MicroMsg/mmslot/webcached"
    };

    public static class Instance {
        public int userId;
        public String label;     // 主用户 / XSpace / 用户10 ...
        public String dataDir;   // /data/data/<pkg> 或 /data/user/<id>/<pkg>
        public long sizeMB;

        public Instance(int u, String l, String d, long s) {
            userId = u;
            label = l;
            dataDir = d;
            sizeMB = s;
        }
    }

    /**
     * 定位所有装有微信的用户（主用户 + 多用户 / XSpace 多开）。
     * 只做一次极轻量的目录存在性检查，不扫描缓存子目录。
     */
    public static List<Instance> detect() {
        return detect(true);
    }

    /**
     * @param withSize 是否同时计算目录占用（du 较慢）。传 false 可先快速返回实例，
     *                 再由调用方异步查询占用，避免界面卡在“正在请求 root”。
     */
    public static List<Instance> detect(boolean withSize) {
        List<Instance> out = new ArrayList<>();

        // 1) pm list users -> 用户 id + 名称
        Map<Integer, String> names = new HashMap<>();
        RootShell.Result r = RootShell.su("pm list users 2>/dev/null");
        Pattern p = Pattern.compile("UserInfo\\{(\\d+):([^:]*):");
        for (String line : r.out.split("\n")) {
            Matcher m = p.matcher(line);
            if (m.find()) {
                try {
                    names.put(Integer.parseInt(m.group(1)), m.group(2));
                } catch (Exception ignore) {
                }
            }
        }

        // 2) /data/user 下的数字目录 —— 兜底捕获 OEM 双开/隐藏用户
        TreeSet<Integer> ids = new TreeSet<>();
        RootShell.Result ls = RootShell.su("ls -1 /data/user 2>/dev/null");
        for (String line : ls.out.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                ids.add(Integer.parseInt(line));
            } catch (Exception ignore) {
            }
        }
        ids.addAll(names.keySet());
        ids.add(0);

        for (int uid : ids) {
            String dir = uid == 0 ? "/data/data/" + PKG : "/data/user/" + uid + "/" + PKG;
            RootShell.Result ex = RootShell.su("ls -d " + RootShell.q(dir) + " 2>/dev/null");
            if (!ex.out.trim().isEmpty()) {
                String nm = names.get(uid);
                if (nm == null) nm = uid == 0 ? "主用户" : "用户" + uid;
                out.add(new Instance(uid, nm.trim(), dir, withSize ? sizeMB(dir) : 0));
            }
        }
        return out;
    }

    /** du -sm 取得目录占用（MB）。 */
    public static long sizeMB(String dir) {
        RootShell.Result r = RootShell.su("du -sm " + RootShell.q(dir) + " 2>/dev/null");
        String s = r.out.trim();
        int t = s.indexOf('\t');
        if (t < 0) t = s.indexOf(' ');
        try {
            return Long.parseLong(s.substring(0, t < 0 ? s.length() : t).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 直接按固定目录删除，不扫描。返回可读日志。
     * 注意：所有目录合并成一条 rm -rf（只起一次 su），避免每个目录
     * 都启动一次 su 进程；容量统计由调用方异步执行，不阻塞删除。
     */
    public static String clean(Instance inst) {
        StringBuilder log = new StringBuilder();
        RootShell.su("am force-stop --user " + inst.userId + " " + PKG + " 2>/dev/null");

        // 不能对含 * 的路径加引号，否则通配符不会展开。
        StringBuilder cmd = new StringBuilder("rm -rf ");
        for (String g : BROWSER_GLOBS) {
            cmd.append(inst.dataDir).append('/').append(g).append(' ');
        }
        cmd.append("2>/dev/null; echo RC=$?");
        RootShell.Result r = RootShell.su(cmd.toString());

        RootShell.su("am force-stop --user " + inst.userId + " " + PKG + " 2>/dev/null");

        if (r.out.contains("RC=0")) {
            log.append("已删除全部网页/小程序缓存目录（容量后台统计中）\n");
        } else {
            log.append("部分删除失败：").append(r.out.trim()).append("\n");
        }
        return log.toString();
    }
}
