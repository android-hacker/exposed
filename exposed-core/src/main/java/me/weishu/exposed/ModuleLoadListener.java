package me.weishu.exposed;

import android.content.pm.ApplicationInfo;

/**
 * author: weishu on 18/1/14.
 */

public interface ModuleLoadListener {
    void onLoadingModule(String moduleClassName, ApplicationInfo applicationInfo, ClassLoader appClassLoader);
    void onModuleLoaded(String moduleClassName, ApplicationInfo applicationInfo, ClassLoader appClassLoader);
}
