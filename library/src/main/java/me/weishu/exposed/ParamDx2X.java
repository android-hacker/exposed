package me.weishu.exposed;

import android.os.Bundle;

import de.robv.android.xposed.callbacks.XCallback;

/**
 * Created by weishu on 17/11/30.
 */

public class ParamDx2X extends XCallback.Param {

    private com.taobao.android.dexposed.callbacks.XCallback.Param beWrapped;

    public ParamDx2X(com.taobao.android.dexposed.callbacks.XCallback.Param beWrapped) {
        this.beWrapped = beWrapped;
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
}
