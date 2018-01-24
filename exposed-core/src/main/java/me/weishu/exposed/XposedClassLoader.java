package me.weishu.exposed;

/**
 * XposedClassLoader: use to only load xposed api classes.
 */
public class XposedClassLoader extends ClassLoader {

    private ClassLoader mHostClassLoader;
    public XposedClassLoader(ClassLoader hostClassLoader) {
        super(ClassLoader.getSystemClassLoader()); // parent is BootClassLoader
        mHostClassLoader = hostClassLoader;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("de.robv.android.xposed")
                || name.startsWith("android")
                || name.startsWith("external")) {
            return mHostClassLoader.loadClass(name);
        }
        return super.loadClass(name, resolve);
    }
}
