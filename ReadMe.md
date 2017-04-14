[![JitPack](https://jitpack.io/v/lgzh1215/Ctrie.svg)](https://jitpack.io/#lgzh1215/Ctrie)
[![Kotlin 1.1.1](https://img.shields.io/badge/Kotlin-1.1.1-blue.svg)](http://kotlinlang.org)

# About

Ctrie is a concurrent thread-safe lock-free hash array mapped trie.

This is a Kotlin implementation of Ctrie, supports non-blocking, atomic constant-time snapshot
operation.

For details, see: http://lampwww.epfl.ch/~prokopec/ctries-snapshot.pdf

# Usage

### Add the JitPack repository and add the dependency
```groovy
// Gradle
repositories {
    ...
    maven { url 'https://jitpack.io' }
}
```
```groovy
dependencies {
    ...
    compile 'com.github.lgzh1215:Ctrie:1.1'
}
```

### Then you can do things with `TrieMap` and `TrieSet`
```kotlin
import org.lpj.some.collection.TrieMap

fun doSomething() {
    val map: Map<String, String> = TrieMap()
    map.put("too young", "too simple")
    
    val set: Set<String> = TrieSet()
    set.add("naive")
}
```
