package me.weishu.exposed;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Created by weishu on 18/1/5.
 */
public class LogcatService extends Service {

    private static final String TAG = "LogcatService";

    private volatile boolean mReading = false;

    private static final String PATH_KEY = "path";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String path = intent.getStringExtra(PATH_KEY);
            if (!TextUtils.isEmpty(path)) {
                startReadLogcat(path);
            }
        }
        return super.onStartCommand(intent, flags, startId);
    }

    private void startReadLogcat(final String path) {
        if (mReading) {
            return;
        }
        Thread logcatThread = new Thread("exposed-logcat") {
            @Override
            public void run() {
                super.run();
                mReading = true;
                try {
                    Log.i(TAG, "exposed logcat start..");
                    List<String> cmds = new ArrayList<String>();
                    cmds.add("sh");
                    cmds.add("-c");
                    cmds.add("logcat -v time -s XposedStartupMarker:D Xposed:I appproc:I XposedInstaller:I art:F DexposedBridge:I ExposedBridge:D " +
                            "Runtime:I EpicNative:D VClientImpl:D VApp:I " +
                            " >> " + path);
                    ProcessBuilder pb = new ProcessBuilder(cmds);
                    Process p = pb.start();
                    p.waitFor();
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        };
        logcatThread.setPriority(Thread.MIN_PRIORITY);
        logcatThread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                XposedBridge.log(e);
                // Do nothing else.
                mReading = false;
            }
        });
        logcatThread.start();
    }

    public static void start(Context context, File xposedInstallerDataDir) {
        Intent t = new Intent(context, LogcatService.class);
        File logDir = new File(xposedInstallerDataDir, "exposed_log");
        if (!(logDir.exists() && logDir.isDirectory())) {
            logDir.mkdir();
            setWorldReadable(logDir);
        }

        File logFile = new File(logDir, "error.log");
        if (!(logFile.exists() && logFile.isFile())) {
            try {
                logFile.createNewFile();
                setWorldReadable(logFile);
            } catch (IOException ignored) {
            }
        }

        t.putExtra(PATH_KEY, logFile.getPath());
        context.startService(t);
    }

    private static void setWorldReadable(File file) {
        XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.os.FileUtils", ClassLoader.getSystemClassLoader()),
                "setPermissions", file.getPath(),
                00400 | 00200 | 00100 | 00040 | 00010 | 00004 | 00001, -1, -1);
    }
}
