package dev.ujhhgtg.wekit.utils.reflection

val Class<*>.isBuiltin get() = this.isPrimitive || this.name.startsWith("java.") || this.name.startsWith("kotlin.") || this.name.startsWith("android.")
