# 简介

exposed 致力于在**非Root**环境下实现Xposed的功能。基本思路是劫持APP进程启动的入口，加载Xposed 插件以及 非Root环境下的hook框架 [epic](https://github.com/tiann/epic) 。

exposed本质上是一个 Xposed 与 APP进程 之间的兼容层，它给Xposed模块提供运行环境（如插件加载、hook环境等）。

目前使用最广泛的实现是 VirtualXposed，它使用 [VirtualApp](https://github.com/asLody/VirtualApp) 来运行APP并提供进程入口劫持。但是，exposed 本身并不依赖双开宿主；甚至不需要双开。

另外，基于APP加固的思路，甚至直接修改APK，或者通过magisk注入进程的方式，都可以实现 免Root Xposed。

# 使用

Exposed 是一个library，是提供给开发者使用的；如果你需要在非ROOT环境下运行Xposed，请移步项目 [VAExposed](https://github.com/android-hacker/VAExposed)

如果需要在APP进程中中提供Xposed运行环境，在进程启动的入口，执行如下调用即可：

```java
ExposedBridge.initOnce(context, applicationInfo, appClassLoader);
```

同时，为了加载Xposed模块，需要在进程启动的时候执行模块加载：

```java
ExposedBridge.loadModule(moduleApk, apkOdexDir, moduleLibDir, applicationInfo, appClassLoader);
```

