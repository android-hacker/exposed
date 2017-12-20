# 简介

Exposed 致力于在**非Root**环境下实现Xposed的功能。基本思路是使用沙盒机制运行APP，在沙盒环境下HOOK本进程从而实现HOOK任意APP的功能。

Exposed本质上是一个Xposed与沙盒之间的兼容层，它给Xposed模块提供运行环境，但不强依赖于双开宿主。不过目前的唯一实现基于[VirtualApp](https://github.com/asLody/VirtualApp)，同时HOOK模块
使用 [epic](https://github.com/tiann/epic)

# 使用

Exposed 是一个library，是提供给沙盒开发者使用的；如果你需要在非ROOT环境下运行Xposed，请移步项目 [VAExposed](https://github.com/android-hacker/VAExposed)

如果需要在沙盒中提供Xposed运行环境，在给启动沙盒进程的时候，执行如下调用即可：

```java
ExposedBridge.initOnce(context, applicationInfo, appClassLoader);
```

同时，为了加载沙盒中的Xposed模块，需要在进程启动的时候执行模块加载：

```java
ExposedBridge.loadModule(moduleApk, apkOdexDir, moduleLibDir, applicationInfo, appClassLoader);
```

