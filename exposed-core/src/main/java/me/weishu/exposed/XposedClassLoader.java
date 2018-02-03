package me.weishu.exposed;

/**
 * XposedClassLoader: use to only load xposed api classes.
 */
class XposedClassLoader extends ClassLoader {

    private ClassLoader mHostClassLoader;

    XposedClassLoader(ClassLoader hostClassLoader) {
        super(ClassLoader.getSystemClassLoader().getParent()); // BootstrapClassLoader
        mHostClassLoader = hostClassLoader;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("de.robv.android.xposed")
                || name.startsWith("android")
                || name.startsWith("external")
                || name.startsWith("me.weishu.epic.art")
                || name.startsWith("com.taobao.android.dexposed")) {
            return mHostClassLoader.loadClass(name);
        }
        return super.loadClass(name, resolve);
    }
}
