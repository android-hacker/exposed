package me.weishu.exposed;

import android.os.Bundle;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Created by weishu on 17/11/30.
 */

public class MethodHookParamDx2X extends XC_MethodHook.MethodHookParam {

    private com.taobao.android.dexposed.XC_MethodHook.MethodHookParam beWrapped;

    public MethodHookParamDx2X(com.taobao.android.dexposed.XC_MethodHook.MethodHookParam beWrapped) {
        this.beWrapped = beWrapped;

        this.method = beWrapped.method;
        this.args = beWrapped.args; // reference, lalala
        this.thisObject = beWrapped.thisObject;
    }

    @Override
    public synchronized Bundle getExtra() {
        return beWrapped.getExtra();
    }

    @Override
    public Object getObjectExtra(String key) {
        return beWrapped.getObjectExtra(key);
    }

    @Override
    public void setObjectExtra(String key, Object o) {
        beWrapped.setObjectExtra(key, o);
    }

    @Override
    public Object getResult() {
        return beWrapped.getResult();
    }

    @Override
    public void setResult(Object result) {
        beWrapped.setResult(result);
    }

    @Override
    public Throwable getThrowable() {
        return beWrapped.getThrowable();
    }

    @Override
    public boolean hasThrowable() {
        return beWrapped.hasThrowable();
    }

    @Override
    public void setThrowable(Throwable throwable) {
        beWrapped.setThrowable(throwable);
    }

    @Override
    public Object getResultOrThrowable() throws Throwable {
        return beWrapped.getResultOrThrowable();
    }
}
