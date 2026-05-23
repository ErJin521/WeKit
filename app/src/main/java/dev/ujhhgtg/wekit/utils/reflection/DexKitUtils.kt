package dev.ujhhgtg.wekit.utils.reflection

import dev.ujhhgtg.wekit.utils.HostInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData

inline val MethodData.asMethod get() = getMethodInstance(ClassLoaders.HOST)

inline val ClassData.asClass get() = getInstance(ClassLoaders.HOST)

inline val MethodData.asConstuctor get() = getConstructorInstance(ClassLoaders.HOST)

val dexKit by lazy {
    runBlocking {
        withContext(Dispatchers.IO) {
            DexKitBridge.create(HostInfo.appInfo.sourceDir)
        }
    }
}
