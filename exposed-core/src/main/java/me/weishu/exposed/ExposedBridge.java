package me.weishu.exposed;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.view.AbsSavedState;
import android.view.View;

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
import java.lang.reflect.Member;
import java.lang.reflect.Method;
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

    private static final String VERSION_KEY = "version";

    private static Pair<String, Set<String>> lastModuleList = Pair.create(null, null);
    private static Map<ClassLoader, ClassLoader> exposedClassLoaderMap = new HashMap<>();
    private static ClassLoader xposedClassLoader;

    private static volatile boolean isWeChat = false;

    private static Context appContext;
    private static ModuleLoadListener sModuleLoadListener = new ModuleLoadListener() {
        @Override
        public void onLoadingModule(String moduleClassName, ApplicationInfo applicationInfo, ClassLoader appClassLoader) {
            if ("com.tencent.mm".equalsIgnoreCase(applicationInfo.packageName)) {
                isWeChat = true;
            }
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
        appContext = context;
        ReLinker.loadLibrary(context, "epic");
        ExposedHelper.initSeLinux(applicationInfo.processName);
        XSharedPreferences.setPackageBaseDirectory(new File(applicationInfo.dataDir).getParentFile());

        initForXposedModule(context, applicationInfo, appClassLoader);
        initForXposedInstaller(context, applicationInfo, appClassLoader);
    }

    public static void patchAppClassLoader(Context baseContext) {
        final ClassLoader originClassLoader = baseContext.getClassLoader();
        Object mPackageInfo = XposedHelpers.getObjectField(baseContext, "mPackageInfo");
        ClassLoader appClassLoaderWithXposed = getAppClassLoaderWithXposed(originClassLoader);
        XposedHelpers.setObjectField(mPackageInfo, "mClassLoader", appClassLoaderWithXposed);
        Thread.currentThread().setContextClassLoader(appClassLoaderWithXposed);
    }

    public static synchronized ClassLoader getAppClassLoaderWithXposed(ClassLoader appClassLoader) {
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

        if (isXposedInstaller(currentApplicationInfo)) {
            return ModuleLoadResult.IGNORED;
        }

        final String rootDir = new File(currentApplicationInfo.dataDir).getParent();
        loadModuleConfig(rootDir, currentApplicationInfo.processName);

        if (lastModuleList.second == null || !lastModuleList.second.contains(moduleApkPath)) {
            log("module:" + moduleApkPath + " is disabled, ignore");
            return ModuleLoadResult.DISABLED;
        }

        log("Loading modules from " + moduleApkPath + " for process: " + currentApplicationInfo.processName);

        if (!new File(moduleApkPath).exists()) {
            log(moduleApkPath + " does not exist");
            return ModuleLoadResult.NOT_EXIST;
        }

        ClassLoader hostClassLoader = ExposedBridge.class.getClassLoader();
        ClassLoader appClassLoaderWithXposed = getAppClassLoaderWithXposed(appClassLoader);

        ClassLoader mcl = new DexClassLoader(moduleApkPath, moduleOdexDir, moduleLibPath, getXposedClassLoader(hostClassLoader));
        // ClassLoader mcl = new DexClassLoader(moduleApkPath, moduleOdexDir, moduleLibPath, hostClassLoader);

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

                try {
                    log("  Loading class " + moduleClassName);
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

                    return ModuleLoadResult.SUCCESS;
                } catch (Throwable t) {
                    log(t);
                }
            }
        } catch (IOException e) {
            log(e);
        } finally {
            try {
                is.close();
            } catch (IOException ignored) {
            }
        }
        return ModuleLoadResult.FAILED;
    }

    private static boolean ignoreHooks(Member member) {
        if (isWeChat) {
            if (member instanceof Method) {
                if (((Method) member).getReturnType() == Bitmap.class) {
                    log("i h: " + ((Method) member).toGenericString());
                    return true;
                }
                if ("closeChatting".equalsIgnoreCase(member.getName())) {
                    log("i h: " + ((Method) member).toGenericString());
                    return true;
                }
                if (member.getDeclaringClass().getName().contains("notification")) {
                    log("i h: " + ((Method) member).toGenericString());
                    return true;
                }
            }
        }
        return false;
    }

    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
        if (ignoreHooks(method)) {
            return null;
        }
        final XC_MethodHook.Unhook unhook = DexposedBridge.hookMethod(method, callback);
        return ExposedHelper.newUnHook(callback, unhook.getHookedMethod());
    }

    private static void initForXposedModule(Context context, ApplicationInfo applicationInfo, ClassLoader appClassLoader) {
        InputStream inputStream = null;

        try {
            inputStream = context.getAssets().open("xposed_init");
            System.setProperty("epic.force", "true");
        } catch (IOException e) {
            log("initForXposedModule, ignore :" + applicationInfo.packageName);
        } finally {
            closeSliently(inputStream);
        }
    }

    private static boolean isXposedInstaller(ApplicationInfo applicationInfo) {
        return XPOSED_INSTALL_PACKAGE.equals(applicationInfo.packageName);
    }

    private static void initForXposedInstaller(Context context, ApplicationInfo applicationInfo, ClassLoader appClassLoader) {
        if (!isXposedInstaller(applicationInfo)) {
            return;
        }

        Log.i(TAG, "initForXposedInstaller");

        // XposedInstaller
        final int fakeXposedVersion = 91;
        final String fakeVersionString = String.valueOf(fakeXposedVersion);
        final File xposedProp = context.getFileStreamPath("xposed_prop");
        if (!xposedProp.exists()) {
            writeXposedProperty(xposedProp, fakeVersionString, false);
        } else {
            log("xposed config file exists, check version");
            String oldVersion = getXposedVersionFromProperty(xposedProp);
            if (!fakeVersionString.equals(oldVersion)) {
                writeXposedProperty(xposedProp, fakeVersionString, true);
            } else {
                log("xposed version keep same, continue.");
            }
        }

        final Class<?> xposedApp = XposedHelpers.findClass("de.robv.android.xposed.installer.XposedApp", appClassLoader);
        final Object xposed_prop_files = XposedHelpers.getStaticObjectField(xposedApp, "XPOSED_PROP_FILES");
        final int length = Array.getLength(xposed_prop_files);
        String xposedPropPath = xposedProp.getPath();
        for (int i = 0; i < length; i++) {
            Array.set(xposed_prop_files, i, xposedPropPath);
        }

        DexposedBridge.findAndHookMethod(xposedApp, "getActiveXposedVersion", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                param.setResult(fakeXposedVersion);
            }
        });
        DexposedBridge.findAndHookMethod(xposedApp, "getInstalledXposedVersion", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                param.setResult(fakeXposedVersion);
            }
        });

        final Constructor<?> fileConstructor1 = XposedHelpers.findConstructorExact(File.class, String.class);
        final Constructor<?> fileConstructor2 = XposedHelpers.findConstructorExact(File.class, String.class, String.class);
        final String dataDir = applicationInfo.dataDir;
        DexposedBridge.hookMethod(fileConstructor1, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                final String path = (String)param.args[0];
                if (path.startsWith(BASE_DIR)) {
                    param.args[0] = path.replace(BASE_DIR, path.equals(BASE_DIR) ? dataDir : dataDir + "/exposed_");
                }
            }
        });
        DexposedBridge.hookMethod(fileConstructor2, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                final String path = (String)param.args[0];
                if (path.startsWith(BASE_DIR)) {
                    param.args[0] = path.replace(BASE_DIR, path.equals(BASE_DIR) ? dataDir : dataDir + "/exposed_");
                }
            }
        });

        // fix bug on Android O: https://github.com/emilsjolander/StickyListHeaders/issues/477
        Class<?> stickyListHeadersClass = XposedHelpers.findClass("se.emilsjolander.stickylistheaders.StickyListHeadersListView", appClassLoader);
        DexposedBridge.findAndHookMethod(stickyListHeadersClass, "onSaveInstanceState", new XC_MethodHook() {
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


    private static void initForWeChatTranslate(String moduleClassName, ApplicationInfo applicationInfo, ClassLoader appClassLoader) {
        if (!"com.hkdrjxy.wechart.xposed.XposedInit".equals(moduleClassName)) {
            return;
        }

        if (!("com.hiwechart.translate".equals(applicationInfo.processName) || "com.tencent.mm".equals(applicationInfo.processName))){
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
        DexposedBridge.findAndHookMethod(serviceManager, "getService", String.class, new XC_MethodHook() {
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

    /**
     * write xposed property file to fake xposedinstaller
     * @param propertyFile the property file used by XposedInstaller
     * @param version to fake version
     * @param retry need retry, when retry, delete file and try again
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
     * @return is need check module
     */
    private static boolean loadModuleConfig(String rootDir, String processName) {

        if (lastModuleList != null && TextUtils.equals(lastModuleList.first, processName) && lastModuleList.second != null) {
            Log.d(TAG, "lastmodule valid, do not load config repeat");
            return true; // xposed installer has config file, and has already loaded for this process, return.
        }

        // load modules
        final File xposedInstallerDir = new File(rootDir, XPOSED_INSTALL_PACKAGE);
        Log.d(TAG, "xposedInstaller Dir:" + xposedInstallerDir);
        if (!xposedInstallerDir.exists()) {
            Log.d(TAG, "XposedInstaller not installed, ignore.");
            return false; // xposed installer not enabled, must load all.
        }

        final File modiles = new File(xposedInstallerDir, "exposed_conf/modules.list");
        Log.d(TAG, "module file:" + modiles);
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
            Log.d(TAG, "last moduleslist: " + lastModuleList);
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

