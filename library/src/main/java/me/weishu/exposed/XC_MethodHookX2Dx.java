package me.weishu.exposed;

import android.util.Log;

import java.util.Arrays;

import de.robv.android.xposed.ExposedHelper;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Xposed's XC_MethodHook 2 Dexposed's XC_Method.
 */
public class XC_MethodHookX2Dx extends com.taobao.android.dexposed.XC_MethodHook {

    private XC_MethodHook beWrapped;

    public XC_MethodHookX2Dx(XC_MethodHook methodHook) {
        beWrapped = methodHook;
    }

    @Override
    protected void beforeHookedMethod(com.taobao.android.dexposed.XC_MethodHook.MethodHookParam param) throws Throwable {
        ExposedHelper.beforeHookedMethod(beWrapped, new MethodHookParamDx2X(param));
    }

    @Override
    protected void afterHookedMethod(com.taobao.android.dexposed.XC_MethodHook.MethodHookParam param) throws Throwable {
        Log.i("mylog", "afterHookedMethod, dexposed:" + Arrays.toString(param.args));

        ExposedHelper.afterHookedMethod(beWrapped, new MethodHookParamDx2X(param));
    }

    @Override
    protected void call(Param param) throws Throwable {
        XposedHelpers.callMethod(beWrapped, "call", new ParamDx2X(param));
    }
}
