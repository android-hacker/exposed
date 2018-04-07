package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Created by weishu on 17/11/30.
 */
public class ExposedHelper {

    public static void initSeLinux(String processName) {
        SELinuxHelper.initOnce();
        SELinuxHelper.initForProcess(processName);
    }

    public static boolean isIXposedMod(Class<?> moduleClass) {
        XposedBridge.log("module's classLoader : " + moduleClass.getClassLoader() + ", super: " + moduleClass.getSuperclass());
        XposedBridge.log("IXposedMod's classLoader : " + IXposedMod.class.getClassLoader());

        return IXposedMod.class.isAssignableFrom(moduleClass);
    }


    public static XC_MethodHook.Unhook newUnHook(XC_MethodHook methodHook, Member member) {
        return methodHook.new Unhook(member);
    }

    public static void callInitZygote(String modulePath, Object moduleInstance) throws Throwable {
        IXposedHookZygoteInit.StartupParam param = new IXposedHookZygoteInit.StartupParam();
        param.modulePath = modulePath;
        param.startsSystemServer = false;
        ((IXposedHookZygoteInit) moduleInstance).initZygote(param);
    }
}
