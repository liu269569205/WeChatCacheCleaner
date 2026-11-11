package com.lj.wxcache;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Minimal root shell helper (su -c ...). */
public class RootShell {

    public static class Result {
        public int code;
        public String out;
        public String err;

        public Result(int code, String out, String err) {
            this.code = code;
            this.out = out == null ? "" : out;
            this.err = err == null ? "" : err;
        }

        public boolean ok() {
            return code == 0;
        }

        public String all() {
            return (out + "\n" + err).trim();
        }
    }

    /** Quote a path safely for a POSIX shell. */
    public static String q(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static Boolean rootOk = null;
    private static Boolean mountMaster = null;
    /** 最近一次 su 诊断信息（供 UI 显示）。 */
    public static String LAST_ERROR = "";

    public static Result su(String cmd) {
        return su(cmd, null, 120000);
    }

    public static Result su(String cmd, String stdin, long timeoutMs) {
        if (rootOk == null) rootAvailable();
        boolean mm = Boolean.TRUE.equals(mountMaster);
        return exec(mm ? new String[]{"su", "-M", "-c", cmd}
                      : new String[]{"su", "-c", cmd}, stdin, timeoutMs);
    }

    /** 允许"重新请求"时重新发起 su（清除上次的探测结果）。 */
    public static synchronized void resetRoot() {
        rootOk = null;
        mountMaster = null;
    }

    /**
     * 打开 app 时的第一次调用：立即发起标准 su 请求，触发 Magisk / KernelSU 授权弹窗。
     * 注意先用最通用的 `su -c`，部分 root 管理器对 `su -M` 首次请求不会弹窗。
     * 拿到授权后再探测 -M / --mount-master（KernelSU、Magisk 均支持）：
     * 否则从 app 进程 fork 出的 su 会继承隔离 mount namespace，
     * 看不到 /data/user/999 等其它用户目录。
     * 超时给足 60 秒，留时间给用户点"允许"。
     */
    public static synchronized boolean rootAvailable() {
        if (rootOk != null) return rootOk;

        Result r = exec(new String[]{"su", "-c", "id -u"}, null, 60000);
        LAST_ERROR = "code=" + r.code + " out=[" + r.out.trim() + "] err=[" + r.err.trim() + "]";
        rootOk = r.code == 0 && "0".equals(r.out.trim());
        if (!rootOk) {
            mountMaster = false;
            return false;
        }

        // 已授权，再探测 mount-master 是否可用
        Result mmr = exec(new String[]{"su", "-M", "-c", "id -u"}, null, 15000);
        mountMaster = mmr.code == 0 && "0".equals(mmr.out.trim());
        return true;
    }

    private static Result exec(String[] argv, String stdin, long timeoutMs) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(false);
            p = pb.start();
            final InputStream is = p.getInputStream();
            final InputStream es = p.getErrorStream();
            Thread t1 = null, t2 = null;
            final StringBuilder so2 = new StringBuilder();
            final StringBuilder se2 = new StringBuilder();
            t1 = new Thread(() -> readAll(is, so2));
            t2 = new Thread(() -> readAll(es, se2));
            t1.start();
            t2.start();

            if (stdin != null) {
                OutputStream os = p.getOutputStream();
                os.write(stdin.getBytes("UTF-8"));
                os.flush();
                os.close();
            }

            boolean done = waitFor(p, timeoutMs);
            if (!done) {
                try {
                    p.destroy();
                } catch (Exception ignore) {
                }
            }
            try {
                t1.join(3000);
                t2.join(3000);
            } catch (InterruptedException ignore) {
            }
            int code = done ? p.exitValue() : -99;
            return new Result(code, so2.toString(), se2.toString());
        } catch (Exception e) {
            return new Result(-1, "", e.toString());
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception ignore) {
                }
            }
        }
    }

    private static boolean waitFor(Process p, long ms) {
        long end = System.currentTimeMillis() + Math.max(1000, ms);
        while (System.currentTimeMillis() < end) {
            try {
                p.exitValue();
                return true;
            } catch (IllegalThreadStateException e) {
                try {
                    Thread.sleep(60);
                } catch (InterruptedException ignore) {
                }
            }
        }
        return false;
    }

    private static void readAll(InputStream is, StringBuilder sb) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            r.close();
        } catch (Exception ignore) {
        }
    }
}
