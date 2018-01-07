package me.weishu.exposed;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.view.AbsSavedState;
import android.view.View;
import android.widget.Toast;

import com.getkeepsafe.relinker.ReLinker;
import com.taobao.android.dexposed.DexposedBridge;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import dalvik.system.DexClassLoader;
import de.robv.android.xposed.ExposedHelper;
import de.robv.android.xposed.IXposedHookInitPackageResources;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static com.taobao.android.dexposed.DexposedBridge.log;


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

    /**
     * Module load result
     */
    enum ModuleLoadResult {
        DISABLED,
        NOT_EXIST,
        INVALID,
        SUCCESS,
        FAILED
    }

    public static void initOnce(Context context, ApplicationInfo applicationInfo, ClassLoader appClassLoader) {
        ReLinker.loadLibrary(context, "epic");
        ExposedHelper.initSeLinux(applicationInfo.processName);
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

        final String rootDir = new File(currentApplicationInfo.dataDir).getParent();
        loadModuleConfig(rootDir, currentApplicationInfo.processName);

        if (lastModuleList.second == null || !lastModuleList.second.contains(moduleApkPath)) {
            log("module:" + moduleApkPath + " is disabled, ignore");
            return ModuleLoadResult.DISABLED;
        }

        log("Loading modules from " + moduleApkPath);

        if (!new File(moduleApkPath).exists()) {
            log(moduleApkPath + " does not exist");
            return ModuleLoadResult.NOT_EXIST;
        }

        ClassLoader hostClassLoader = ExposedBridge.class.getClassLoader();
        ClassLoader appClassLoaderWithXposed = getAppClassLoaderWithXposed(appClassLoader);

        ClassLoader mcl = new DexClassLoader(moduleApkPath, moduleOdexDir, moduleLibPath, hostClassLoader);
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

    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
        final com.taobao.android.dexposed.XC_MethodHook.Unhook unhook = DexposedBridge.hookMethod(method, new XC_MethodHookX2Dx(callback));
        return ExposedHelper.newUnHook(callback, unhook.getHookedMethod());
    }


    private static void initForXposedInstaller(Context context, ApplicationInfo applicationInfo, ClassLoader appClassLoader) {
        if (!XPOSED_INSTALL_PACKAGE.equals(applicationInfo.packageName)) {
            return;
        }

        Log.i(TAG, "initForXposedInstaller");

        // XposedInstaller
        final int fakeXposedVersion = 89;
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

        DexposedBridge.findAndHookMethod(xposedApp, "getActiveXposedVersion", new com.taobao.android.dexposed.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                param.setResult(fakeXposedVersion);
            }
        });
        DexposedBridge.findAndHookMethod(xposedApp, "getInstalledXposedVersion", new com.taobao.android.dexposed.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                param.setResult(fakeXposedVersion);
            }
        });

        final Constructor<?> fileConstructor1 = XposedHelpers.findConstructorExact(File.class, String.class);
        final Constructor<?> fileConstructor2 = XposedHelpers.findConstructorExact(File.class, String.class, String.class);
        final String dataDir = applicationInfo.dataDir;
        DexposedBridge.hookMethod(fileConstructor1, new com.taobao.android.dexposed.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                final String path = (String)param.args[0];
                if (path.startsWith(BASE_DIR)) {
                    param.args[0] = path.replace(BASE_DIR, path.equals(BASE_DIR) ? dataDir : dataDir + "/exposed_");
                }
            }
        });
        DexposedBridge.hookMethod(fileConstructor2, new com.taobao.android.dexposed.XC_MethodHook() {
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
        DexposedBridge.findAndHookMethod(stickyListHeadersClass, "onSaveInstanceState", new com.taobao.android.dexposed.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                param.setResult(AbsSavedState.EMPTY_STATE);
                Field mPrivateFlags = XposedHelpers.findField(View.class, "mPrivateFlags");
                int flags = mPrivateFlags.getInt(param.thisObject);
                mPrivateFlags.set(param.thisObject, flags | 0x00020000);
            }
        });

        // make the module reload when enter ModuleFragment
        DexposedBridge.findAndHookMethod(Fragment.class, "onResume", new com.taobao.android.dexposed.XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                Fragment fragment = (Fragment) param.thisObject;
                if ("de.robv.android.xposed.installer.ModulesFragment".equals(fragment.getClass().getName())) {
                    // ModulesFragment, call reload
                    Class<?> moduleUtilClass = fragment.getClass().getClassLoader().loadClass("de.robv.android.xposed.installer.util.ModuleUtil");
                    Object moduleUtil = XposedHelpers.callStaticMethod(moduleUtilClass, "getInstance");
                    XposedHelpers.callMethod(moduleUtil, "reloadInstalledModules");
                    Activity activity = fragment.getActivity();
                    log("module fragment reload success, activity:" + activity);
                    if (activity != null) {
                        String tips = Locale.getDefault().toString().contains("zh") ? "勾选模块之后，需要在主界面右上角按钮 -> 重启 才能生效哦～" :
                                "module will take effect after Settings->Reboot!";
                        Toast.makeText(activity, tips, Toast.LENGTH_SHORT).show();
                    }
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

