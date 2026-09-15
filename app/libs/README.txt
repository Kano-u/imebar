libxposed-api.jar
libxposed-service.jar
libxposed-interface.jar

来源：官方 Maven Central 制品
  io.github.libxposed:api:102.0.0        -> api-102.0.0.aar        -> classes.jar
  io.github.libxposed:service:102.0.0    -> service-102.0.0.aar    -> classes.jar
  io.github.libxposed:interface:102.0.0  -> interface-102.0.0.aar  -> classes.jar

项目主页：https://github.com/libxposed
许可：Apache License 2.0

为什么不直接用 AAR：
三个 AAR 的元数据里都写了 minCompileSdk=37（Android 17），而 AGP 8.7.3 的
推荐上限是 compileSdk 35，CI 运行器上也未必装得到 android-37。
改成普通 jar 依赖即可绕开这项检查，用 compileSdk 35 正常编译。

其中 api 必须在运行时由 LSPosed 框架提供，所以只能 compileOnly，
绝对不要打进 APK（否则会和框架自带的类冲突）。
