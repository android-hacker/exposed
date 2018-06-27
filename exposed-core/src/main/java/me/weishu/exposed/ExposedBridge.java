package me.weishu.exposed;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;
import android.view.AbsSavedState;
import android.view.View;
import android.widget.Toast;

import com.getkeepsafe.relinker.ReLinker;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import dalvik.system.DexClassLoader;
import de.robv.android.xposed.DexposedBridge;
import de.robv.android.xposed.ExposedHelper;
import de.robv.android.xposed.IXposedHookInitPackageResources;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedBridge.log;


public class ExposedBridge {

    private static final String TAG = "ExposedBridge";

    private static final String XPOSED_INSTALL_PACKAGE = "de.robv.android.xposed.installer";

    @SuppressLint("SdCardPath")
    private static final String BASE_DIR_LEGACY = "/data/data/de.robv.android.xposed.installer/";

    public static final String BASE_DIR = Build.VERSION.SDK_INT >= 24
            ? "/data/user_de/0/de.robv.android.xposed.installer/" : BASE_DIR_LEGACY;

    private static final String WECHAT = decodeFromBase64("Y29tLnRlbmNlbnQubW0=");
    private static final String QQ = decodeFromBase64("Y29tLnRlbmNlbnQubW9iaWxlcXE=");

    private static final int FAKE_XPOSED_VERSION = 91;
    private static final String VERSION_KEY = "version";
    private static boolean SYSTEM_CLASSLOADER_INJECT = false;

    private static Pair<String, Set<String>> lastModuleList = Pair.create(null, null);
    private static Map<ClassLoader, ClassLoader> exposedClassLoaderMap = new HashMap<>();
    private static ClassLoader xposedClassLoader;

    private static Context appContext;
    private static String currentPackage;

    private volatile static boolean wcdbLoaded = false;
    private static ModuleLoadListener sModuleLoadListener = new ModuleLoadListener() {
        @Override
        public void onLoadingModule(String moduleClassName, ApplicationInfo applicationInfo, ClassLoader appClassLoader) {
        }

        @Override
        public void onModuleLoaded(String moduleName, ApplicationInfo applicationInfo, ClassLoader appClassLoader) {
            initForWeChatTranslate(moduleName, applicationInfo, appClassLoader);
        }
    };

    /**
     * Module load result
     */
    enum ModuleLoadResult {
        DISABLED,
        NOT_EXIST,
        INVALID,
        SUCCESS,
        FAILED,
        IGNORED
    }

    public static void initOnce(Context context, ApplicationInfo applicationInfo, ClassLoader appClassLoader) {
        // SYSTEM_CLASSLOADER_INJECT = patchSystemClassLoader();
        XposedBridge.XPOSED_BRIDGE_VERSION = FAKE_XPOSED_VERSION;
        appContext = context;
        initForPackage(context, applicationInfo);

        ReLinker.loadLibrary(context, "epic");
        ExposedHelper.initSeLinux(applicationInfo.processName);
        XSharedPreferences.setPackageBaseDirectory(new File(applicationInfo.dataDir).getParentFile());

        initForXposedModule(context, applicationInfo, appClassLoader);
        initForXposedInstaller(context, applicationInfo, appClassLoader);
        initForWechat(context, applicationInfo, appClassLoader);
        initForQQ(context, applicationInfo, appClassLoader);
    }

    private static void initForPackage(Context context, ApplicationInfo applicationInfo) {
        currentPackage = applicationInfo.packageName;

        if (currentPackage == null) {
            currentPackage = context.getPackageName();
        }

        System.setProperty("vxp", "1");
        System.setProperty("vxp_user_dir", new File(applicationInfo.dataDir).getParent());
    }

    private static boolean patchSystemClassLoader() {
        // 1. first create XposedClassLoader -> BootstrapClassLoader
        ClassLoader xposedClassLoader = new XposedClassLoader(ExposedBridge.class.getClassLoader());

        // 2. replace the systemclassloader's parent.
        ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();

        try {
            Field parent = ClassLoader.class.getDeclaredField("parent");
            parent.setAccessible(true);
            parent.set(systemClassLoader, xposedClassLoader);

            log("XposedBridge's BootClassLoader: " + XposedBridge.BOOTCLASSLOADER + ", parent: " + XposedBridge.BOOTCLASSLOADER.getParent());
            // SystemClassLoader -> XposedClassLoader -> BootstrapClassLoader
            return systemClassLoader.getParent() == xposedClassLoader;
        } catch (NoSuchFieldException e) {
            // todo no such field ? use unsafe.
            log(e);
            return false;
        } catch (IllegalAccessException e) {
            log(e);
            return false;
        }
    }

    private static synchronized ClassLoader getAppClassLoaderWithXposed(ClassLoader appClassLoader) {
        if (exposedClassLoaderMap.containsKey(appClassLoader)) {
            return exposedClassLoaderMap.get(appClassLoader);
        } else {
            ClassLoader hostClassLoader = ExposedBridge.class.getClassLoader();
            ClassLoader xposedClassLoader = getXposedClassLoader(hostClassLoader);
            ClassLoader exposedClassLoader = new ComposeClassLoader(xposedClassLoader, appClassLoader);
            exposedClassLoaderMap.put(appClassLoader, exposedClassLoader);
            return exposedClassLoader;
        }
    }

    public static synchronized ClassLoader getXposedClassLoader(ClassLoader hostClassLoader) {
        if (xposedClassLoader == null) {
            xposedClassLoader = new XposedClassLoader(hostClassLoader);
        }
        return xposedClassLoader;
    }

    public static ModuleLoadResult loadModule(final String moduleApkPath, String moduleOdexDir, String moduleLibPath,
                                              final ApplicationInfo currentApplicationInfo, ClassLoader appClassLoader) {

        if (filterApplication(currentApplicationInfo)) {
            return ModuleLoadResult.IGNORED;
        }

        final String rootDir = new File(currentApplicationInfo.dataDir).getParent();
        loadModuleConfig(rootDir, currentApplicationInfo.processName);

        if (lastModuleList.second == null || !lastModuleList.second.contains(moduleApkPath)) {
            Log.i(TAG, "module:" + moduleApkPath + " is disabled, ignore");
            return ModuleLoadResult.DISABLED;
        }

        Log.i(TAG, "Loading modules from " + moduleApkPath + " for process: " + currentApplicationInfo.processName + " i s c: " + SYSTEM_CLASSLOADER_INJECT);

        if (!new File(moduleApkPath).exists()) {
            log(moduleApkPath + " does not exist");
            return ModuleLoadResult.NOT_EXIST;
        }

        ClassLoader appClassLoaderWithXposed;
        ClassLoader mcl;
        if (SYSTEM_CLASSLOADER_INJECT) {
            // we replace the systemclassloader's parent success, go with xposed's way
            appClassLoaderWithXposed = appClassLoader;
            mcl = new DexClassLoader(moduleApkPath, moduleOdexDir, moduleLibPath, XposedBridge.BOOTCLASSLOADER);
        } else {
            // replace failed, just wrap.
            ClassLoader hostClassLoader = ExposedBridge.class.getClassLoader();
            appClassLoaderWithXposed = getAppClassLoaderWithXposed(appClassLoader);
            mcl = new DexClassLoader(moduleApkPath, moduleOdexDir, moduleLibPath, getXposedClassLoader(hostClassLoader));
            // ClassLoader mcl = new DexClassLoader(moduleApkPath, moduleOdexDir, moduleLibPath, hostClassLoader);
        }

        InputStream is = mcl.getResourceAsStream("assets/xposed_init");
        if (is == null) {
            log("assets/xposed_init not found in the APK");
            return ModuleLoadResult.INVALID;
        }

        BufferedReader moduleClassesReader = new BufferedReader(new InputStreamReader(is));
        try {
            String moduleClassName;
            while ((moduleClassName = moduleClassesReader.readLine()) != null) {
                moduleClassName = moduleClassName.trim();
                if (moduleClassName.isEmpty() || moduleClassName.startsWith("#"))
                    continue;

                if (filterModuleForApp(currentApplicationInfo, moduleClassName)) {
                    XposedBridge.log("ignore module: " + moduleClassName + " for application: " + currentApplicationInfo.packageName);
                    continue;
                }
                try {
                    Log.i(TAG, "  Loading class " + moduleClassName);
                    Class<?> moduleClass = mcl.loadClass(moduleClassName);

                    sModuleLoadListener.onLoadingModule(moduleClassName, currentApplicationInfo, mcl);

                    if (!ExposedHelper.isIXposedMod(moduleClass)) {
                        log("    This class doesn't implement any sub-interface of IXposedMod, skipping it");
                        continue;
                    } else if (IXposedHookInitPackageResources.class.isAssignableFrom(moduleClass)) {
                        log("    This class requires resource-related hooks (which are disabled), skipping it.");
                        continue;
                    }

                    final Object moduleInstance = moduleClass.newInstance();
                    if (moduleInstance instanceof IXposedHookZygoteInit) {
                        ExposedHelper.callInitZygote(moduleApkPath, moduleInstance);
                    }

                    if (moduleInstance instanceof IXposedHookLoadPackage) {
                        // hookLoadPackage(new IXposedHookLoadPackage.Wrapper((IXposedHookLoadPackage) moduleInstance));
                        IXposedHookLoadPackage.Wrapper wrapper = new IXposedHookLoadPackage.Wrapper((IXposedHookLoadPackage) moduleInstance);
                        XposedBridge.CopyOnWriteSortedSet<XC_LoadPackage> xc_loadPackageCopyOnWriteSortedSet = new XposedBridge.CopyOnWriteSortedSet<>();
                        xc_loadPackageCopyOnWriteSortedSet.add(wrapper);
                        XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam(xc_loadPackageCopyOnWriteSortedSet);
                        lpparam.packageName = currentApplicationInfo.packageName;
                        lpparam.processName = currentApplicationInfo.processName;
                        lpparam.classLoader = appClassLoaderWithXposed;
                        lpparam.appInfo = currentApplicationInfo;
                        lpparam.isFirstApplication = true;
                        XC_LoadPackage.callAll(lpparam);
                    }

                    if (moduleInstance instanceof IXposedHookInitPackageResources) {
                        // hookInitPackageResources(new IXposedHookInitPackageResources.Wrapper((IXposedHookInitPackageResources) moduleInstance));
                        // TODO: 17/12/1 Support Resource hook
                    }

                    sModuleLoadListener.onModuleLoaded(moduleClassName, currentApplicationInfo, mcl);

                } catch (Throwable t) {
                    log(t);
                }
                return ModuleLoadResult.SUCCESS;
            }
        } catch (IOException e) {
            log(e);
        } finally {
            closeSliently(is);
        }
        return ModuleLoadResult.FAILED;
    }

    private static boolean ignoreHooks(Member member) {
        if (member == null) {
            return false;
        }

        String name = member.getDeclaringClass().getName();
        if (QQ.equals(currentPackage)) {
            // TODO, we just ignore this hook to avoid crash, fix it when you figure out it. (getManager)
            if (name.contains("QQAppInterface")) {
                // Log.i("mylog", "ignore hook for: " + member);
                return true;
            }
        }

        return false;
    }

    private static void presetMethod(Member method) {
        if (method == null) {
            return;
        }

        if (WECHAT.equals(currentPackage)) {
            Class<?> declaringClass = method.getDeclaringClass();
            if (declaringClass.getName().contains("wcdb")) {
                if (!wcdbLoaded) {
                    ClassLoader loader = declaringClass.getClassLoader();
                    Class<?> sqliteDataBaseClass = null;
                    try {
                        sqliteDataBaseClass = loader.loadClass("com.tencent.wcdb.database.SQLiteDatabase");
                    } catch (ClassNotFoundException ignored) {
                        XposedBridge.log("preload sqlite class failed!!!");
                    }
                    if (sqliteDataBaseClass == null) {
                        return;
                    }

                    wcdbLoaded = true;
                }
            }
        }
    }

    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
        if (ignoreHooks(method)) {
            return null;
        }

        presetMethod(method);

        XC_MethodHook.Unhook replaceUnhook = CHAHelper.replaceForCHA(method, callback);
        if (replaceUnhook != null) {
            return ExposedHelper.newUnHook(callback, replaceUnhook.getHookedMethod());
        }

        final XC_MethodHook.Unhook unhook = DexposedBridge.hookMethod(method, callback);
        return ExposedHelper.newUnHook(callback, unhook.getHookedMethod());
    }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
            throws NullPointerException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {

        return DexposedBridge.invokeOriginalMethod(method, thisObject, args);
    }



    private static void initForXposedModule(Context context, ApplicationInfo applicationInfo, ClassLoader appClassLoader) {
        InputStream inputStream = null;

        try {
            inputStream = context.getAssets().open("xposed_init");
            System.setProperty("epic.force", "true");
        } catch (IOException e) {
            Log.i(TAG, applicationInfo.packageName + " is not a Xposed module, do not init epic.force");
        } finally {
            closeSliently(inputStream);
        }
    }

    private static boolean isXposedInstaller(ApplicationInfo applicationInfo) {
        return XPOSED_INSTALL_PACKAGE.equals(applicationInfo.packageName);
    }

    private static boolean filterApplication(ApplicationInfo applicationInfo) {
        if (applicationInfo == null) {
            return true;
        }

        if (isXposedInstaller(applicationInfo)) {
            return true;
        }

        if (decodeFromBase64("Y29tLnRlbmNlbnQubW06cHVzaA==").equalsIgnoreCase(applicationInfo.processName)) {
            // com.tencent.mm:push
            XposedBridge.log("ignore process for wechat push.");
            return true;
        }

        return false;
    }

    private static boolean filterModuleForApp(ApplicationInfo applicationInfo, String moduleEntry) {
        if (applicationInfo == null || applicationInfo.packageName == null) {
            return false;
        }

        final String WECHAT_JUMP_HELPER = "com.emily.mmjumphelper.xposed.XposedMain";

        if (WECHAT.equals(applicationInfo.packageName)) {
            if (applicationInfo.processName.contains("appbrand")) {
                // wechat app brand
                if (WECHAT_JUMP_HELPER.equals(moduleEntry)) {
                    // now only load module for appbrand.
                    return false;
                } else {
                    return true;
                }
            } else {
                if (WECHAT_JUMP_HELPER.equals(moduleEntry)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static void initForXposedInstaller(Context context, ApplicationInfo applicationInfo, ClassLoader appClassLoader) {
        if (!isXposedInstaller(applicationInfo)) {
            return;
        }

        // XposedInstaller
        final String fakeVersionString = String.valueOf(FAKE_XPOSED_VERSION);
        final File xposedProp = context.getFileStreamPath("xposed_prop");
        if (!xposedProp.exists()) {
            writeXposedProperty(xposedProp, fakeVersionString, false);
        } else {
            log("xposed config file exists, check version");
            String oldVersion = getXposedVersionFromProperty(xposedProp);
            if (!fakeVersionString.equals(oldVersion)) {
                writeXposedProperty(xposedProp, fakeVersionString, true);
            } else {
                Log.i(TAG, "xposed version keep same, continue.");
            }
        }

        final Class<?> xposedApp = XposedHelpers.findClass("de.robv.android.xposed.installer.XposedApp", appClassLoader);
        try {
            final Object xposed_prop_files = XposedHelpers.getStaticObjectField(xposedApp, "XPOSED_PROP_FILES");
            final int length = Array.getLength(xposed_prop_files);
            String xposedPropPath = xposedProp.getPath();
            for (int i = 0; i < length; i++) {
                Array.set(xposed_prop_files, i, xposedPropPath);
            }


            XposedHelpers.findAndHookMethod(xposedApp, "getActiveXposedVersion", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    param.setResult(FAKE_XPOSED_VERSION);
                }
            });
            XposedHelpers.findAndHookMethod(xposedApp, "getInstalledXposedVersion", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    param.setResult(FAKE_XPOSED_VERSION);
                }
            });
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // fix bug on Android O: https://github.com/emilsjolander/StickyListHeaders/issues/477
                Class<?> stickyListHeadersClass = XposedHelpers.findClass("se.emilsjolander.stickylistheaders.StickyListHeadersListView", appClassLoader);
                XposedHelpers.findAndHookMethod(stickyListHeadersClass, "onSaveInstanceState", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        super.beforeHookedMethod(param);
                        param.setResult(AbsSavedState.EMPTY_STATE);
                        Field mPrivateFlags = XposedHelpers.findField(View.class, "mPrivateFlags");
                        int flags = mPrivateFlags.getInt(param.thisObject);
                        mPrivateFlags.set(param.thisObject, flags | 0x00020000);
                    }
                });
            }
        } catch (Throwable ignored) {
            // only support 3.1.5 and above.
            try {
                Toast.makeText(context, "The XposedInstaller you used is not supported.", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored2) {
            }
        }

        final Constructor<?> fileConstructor1 = XposedHelpers.findConstructorExact(File.class, String.class);
        final Constructor<?> fileConstructor2 = XposedHelpers.findConstructorExact(File.class, String.class, String.class);
        final String dataDir = applicationInfo.dataDir;
        XposedBridge.hookMethod(fileConstructor1, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                final String path = (String) param.args[0];
                if (path.startsWith(BASE_DIR)) {
                    param.args[0] = path.replace(BASE_DIR, path.equals(BASE_DIR) ? dataDir : dataDir + "/exposed_");
                }
            }
        });
        XposedBridge.hookMethod(fileConstructor2, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                final String path = (String) param.args[0];
                if (path.startsWith(BASE_DIR)) {
                    param.args[0] = path.replace(BASE_DIR, path.equals(BASE_DIR) ? dataDir : dataDir + "/exposed_");
                }
            }
        });


    }


    private static void initForWeChatTranslate(String moduleClassName, ApplicationInfo applicationInfo, ClassLoader appClassLoader) {
        if (!"com.hkdrjxy.wechart.xposed.XposedInit".equals(moduleClassName)) {
            return;
        }

        if (!("com.hiwechart.translate".equals(applicationInfo.processName) || "com.tencent.mm".equals(applicationInfo.processName))) {
            return;
        }

        final IBinder[] translateService = new IBinder[1];
        Intent intent = new Intent();
        intent.setAction("com.hiwechart.translate.aidl.TranslateService");
        ComponentName v2 = new ComponentName("com.hiwechart.translate", "com.hiwechart.translate.aidl.TranslateService");
        intent.setComponent(v2);
        appContext.bindService(intent, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                translateService[0] = service;
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {

            }
        }, Context.BIND_AUTO_CREATE);

        Class<?> serviceManager = XposedHelpers.findClass("android.os.ServiceManager", appClassLoader);
        final String serviceName = Build.VERSION.SDK_INT >= 21 ? "user.wechart.trans" : "wechart.trans";
        XposedHelpers.findAndHookMethod(serviceManager, "getService", String.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                if (serviceName.equals(param.args[0])) {
                    Log.i("mylog", "get service :" + translateService[0]);
                    param.setResult(translateService[0]);
                }
            }
        });
    }

    @SuppressLint("ApplySharedPref")
    private static void initForQQ(Context context, ApplicationInfo applicationInfo, ClassLoader appClassLoader) {
        if (applicationInfo == null) {
            return;
        }
        final String QQ = decodeFromBase64("Y29tLnRlbmNlbnQubW9iaWxlcXE=");
        if (!QQ.equals(applicationInfo.packageName)) {
            return;
        }
        if (QQ.equals(applicationInfo.processName)) {
            SharedPreferences sp = context.getSharedPreferences(decodeFromBase64("aG90cGF0Y2hfcHJlZmVyZW5jZQ=="), // hotpatch_preference
                    Context.MODE_PRIVATE);
            sp.edit().remove(decodeFromBase64("a2V5X2NvbmZpZ19wYXRjaF9kZXg=")).commit(); // key_config_patch_dex
        }
    }

    private static void initForWechat(Context context, ApplicationInfo applicationInfo, ClassLoader appClassLoader) {
        if (applicationInfo == null) {
            return;
        }

        if (!WECHAT.equals(applicationInfo.packageName)) {
            return;
        }
        if (WECHAT.equals(applicationInfo.processName)) {
            // for main process
            String dataDir = applicationInfo.dataDir;

            File tinker = new File(dataDir, decodeFromBase64("dGlua2Vy"));
            File tinker_temp = new File(dataDir, decodeFromBase64("dGlua2VyX3RlbXA="));
            File tinker_server = new File(dataDir, decodeFromBase64("dGlua2VyX3NlcnZlcg=="));

            deleteDir(tinker);
            deleteDir(tinker_temp);
            deleteDir(tinker_server);

            final int mainProcessId = Process.myPid();
            XposedHelpers.findAndHookMethod(Process.class, "killProcess", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    int pid = (int) param.args[0];
                    if (pid != mainProcessId) {
                        return;
                    }
                    // try kill main process, find stack
                    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                    if (stackTrace == null) {
                        return;
                    }

                    for (StackTraceElement stackTraceElement : stackTrace) {
                        if (stackTraceElement.getClassName().contains("com.tencent.mm.app")) {
                            XposedBridge.log("do not suicide..." + Arrays.toString(stackTrace));
                            param.setResult(null);
                            break;
                        }
                    }
                }
            });
        }
    }

    public static boolean deleteDir(File dir) {
        if (dir == null) {
            return false;
        }
        if (dir.isDirectory()) {
            String[] children = dir.list();
            for (String file : children) {
                boolean success = deleteDir(new File(dir, file));
                if (!success) {
                    return false;
                }
            }
        }
        return dir.delete();
    }

    /**
     * avoid from being searched by google.
     *
     * @param base64
     * @return
     */
    private static String decodeFromBase64(String base64) {
        return new String(Base64.decode(base64, 0));
    }

    /**
     * write xposed property file to fake xposedinstaller
     *
     * @param propertyFile the property file used by XposedInstaller
     * @param version      to fake version
     * @param retry        need retry, when retry, delete file and try again
     */
    private static void writeXposedProperty(File propertyFile, String version, boolean retry) {
        Properties properties = new Properties();
        properties.put(VERSION_KEY, version);
        properties.put("arch", Build.CPU_ABI);
        properties.put("minsdk", "52");
        properties.put("maxsdk", String.valueOf(Integer.MAX_VALUE));

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(propertyFile);
            properties.store(fos, null);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            propertyFile.delete();
            writeXposedProperty(propertyFile, version, false);
        } finally {
            closeSliently(fos);
        }
    }

    private static String getXposedVersionFromProperty(File propertyFile) {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(propertyFile);
            Properties properties = new Properties();
            properties.load(new FileInputStream(propertyFile));
            return properties.getProperty(VERSION_KEY);
        } catch (IOException e) {
            log("getXposedVersion from property failed");
            return null;
        } finally {
            closeSliently(fis);
        }
    }

    /**
     * try read module config fules.
     *
     * @return is need check module
     */
    private static boolean loadModuleConfig(String rootDir, String processName) {

        if (lastModuleList != null && TextUtils.equals(lastModuleList.first, processName) && lastModuleList.second != null) {
            // Log.d(TAG, "lastmodule valid, do not load config repeat");
            return true; // xposed installer has config file, and has already loaded for this process, return.
        }

        // load modules
        final File xposedInstallerDir = new File(rootDir, XPOSED_INSTALL_PACKAGE);
        // Log.d(TAG, "xposedInstaller Dir:" + xposedInstallerDir);
        if (!xposedInstallerDir.exists()) {
            // Log.d(TAG, "XposedInstaller not installed, ignore.");
            return false; // xposed installer not enabled, must load all.
        }

        final File modiles = new File(xposedInstallerDir, "exposed_conf/modules.list");
        // Log.d(TAG, "module file:" + modiles);
        if (!modiles.exists()) {
            Log.d(TAG, "xposed installer's modules not exist, ignore.");
            return false; // xposed installer config file not exist, load all.
        }
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(modiles));
            String line = null;
            Set<String> moduleSet = new HashSet<>();
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#")) {
                    continue;
                }
                if (line.isEmpty()) {
                    continue;
                }
                moduleSet.add(line);
            }

            lastModuleList = Pair.create(processName, moduleSet);
            // Log.d(TAG, "last moduleslist: " + lastModuleList);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static void closeSliently(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable e) {
            // IGNORE
        }
    }
}

