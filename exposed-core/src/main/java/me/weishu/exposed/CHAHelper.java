package me.weishu.exposed;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.Log;

import java.lang.reflect.Member;
import java.lang.reflect.Method;

import de.robv.android.xposed.DexposedBridge;
import de.robv.android.xposed.ExposedHelper;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * @author weishu
 * @date 2018/6/19.
 */
public final class CHAHelper {
    private static final String TAG = "CHAHelper";

    static class ApplicationHookProxy extends XC_MethodHook {

        XC_MethodHook original;

        ApplicationHookProxy(XC_MethodHook original) {
            this.original = original;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            super.beforeHookedMethod(param);
            if (param.thisObject == null) {
                throw new IllegalArgumentException("can not use static method!!");
            }

            if (param.thisObject instanceof Application) {
                ExposedHelper.beforeHookedMethod(this.original, param);
            } else {
                Log.d(TAG, "ignore non-application of ContextWrapper: " + param.thisObject);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            super.afterHookedMethod(param);
            if (param.thisObject == null) {
                throw new IllegalArgumentException("can not use static method!!");
            }

            if (param.thisObject instanceof Application) {
                ExposedHelper.afterHookedMethod(this.original, param);
            } else {
                Log.d(TAG, "ignore non-application of ContextWrapper: " + param.thisObject);
            }
        }
    }

    static XC_MethodHook.Unhook replaceForCHA(Member member, final XC_MethodHook callback) {

        if (member.getDeclaringClass() == Application.class && "attach".equals(member.getName())) {
            XposedBridge.log("replace Application.attach with ContextWrapper.attachBaseContext for CHA");
            Method m = XposedHelpers.findMethodExact(ContextWrapper.class, "attachBaseContext", Context.class);
            return DexposedBridge.hookMethod(m, new ApplicationHookProxy(callback));
        }

        if (member.getDeclaringClass() == Application.class && "onCreate".equals(member.getName())) {
            XposedBridge.log("replace Application.onCreate with ContextWrapper.attachBaseContext for CHA");
            Method m = XposedHelpers.findMethodExact(ContextWrapper.class, "attachBaseContext", Context.class);
            return DexposedBridge.hookMethod(m, new ApplicationHookProxy(callback));
        }

        return null;
    }
}
