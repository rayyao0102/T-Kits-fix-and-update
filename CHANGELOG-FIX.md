# T-Kits Build Fix Log

> **Date:** May 3, 2026  
> **Fixed By:** Antigravity AI Assistant  
> **Plugin Version:** 1.0.6  
> **Result:** ✅ BUILD SUCCESS — `target/T-Kits-1.0.6.jar` (1.4 MB)

---

## Problem

The project **failed to compile** with hundreds of `cannot find symbol` errors across nearly every source file. The errors targeted Lombok-generated methods:

```
[ERROR] cannot find symbol: method getMessageUtil()
[ERROR] cannot find symbol: method getContents()
[ERROR] cannot find symbol: method getEnderChestContents()
[ERROR] cannot find symbol: method getKitNumber()
[ERROR] cannot find symbol: method getOwner()
[ERROR] cannot find symbol: method isGlobal()
```

These methods are **not written by hand** — they are auto-generated at compile time by **Project Lombok** via annotations like `@Data`, `@Getter`, `@Builder`, etc.

---

## Root Cause

**Lombok version 1.18.30 is incompatible with JDK 26.**

- Your system runs **JDK 26** (`Java version: 26, vendor: Oracle Corporation`)
- The `pom.xml` specified **Lombok 1.18.30**, which only supports up to JDK 22
- When the Java 26 compiler invoked Lombok's annotation processor, it silently failed to generate any code — meaning all `@Getter`, `@Setter`, `@Data`, and `@Builder` outputs were simply missing
- Every class that relied on Lombok-generated methods then failed to resolve those symbols

This is a **silent failure mode** — Lombok doesn't produce an error saying "unsupported JDK", it just fails to generate the code.

---

## Changes Made

### File: `pom.xml`

#### 1. Upgraded Lombok Version

```diff
- <lombok.version>1.18.30</lombok.version>
+ <lombok.version>1.18.46</lombok.version>
```

**Why:** Lombok 1.18.46 (released April 22, 2026) is the first version with official JDK 26 support.

#### 2. Switched Compiler Configuration

```diff
  <configuration>
-     <source>${java.version}</source>
-     <target>${java.version}</target>
+     <release>${java.version}</release>
+     <annotationProcessorPaths>
+         <path>
+             <groupId>org.projectlombok</groupId>
+             <artifactId>lombok</artifactId>
+             <version>${lombok.version}</version>
+         </path>
+     </annotationProcessorPaths>
  </configuration>
```

**Why:**
- `<release>` replaces `<source>/<target>` — it's the modern, correct way to cross-compile (outputs Java 21 bytecode even from a JDK 26 compiler, and enforces API compatibility)
- `<annotationProcessorPaths>` explicitly registers Lombok as an annotation processor, which is more reliable than relying on classpath discovery, especially with newer JDKs that restrict reflective access

---

## IDE Errors After Fix

Your IDE may still show red underlines even after the Maven build succeeds. This happens because:

1. **IDE uses its own annotation processor** — it may still have Lombok 1.18.30 cached
2. **Fix:** In your IDE:
   - **IntelliJ IDEA:** File → Invalidate Caches → Invalidate and Restart. Then reimport the Maven project (Ctrl+Shift+O or the Maven refresh button)
   - **VS Code:** Close and reopen the workspace, or run "Java: Clean Java Language Server Workspace" from the command palette
   - **Eclipse:** Project → Clean, then Maven → Update Project

---

## Build Warnings (Non-Critical)

| Warning | Severity | Notes |
|---------|----------|-------|
| `Sound.valueOf()` deprecated | Low | `MessageUtil.java:136` — `Sound.valueOf()` is marked for removal. Migrate to `Registry.SOUNDS.get(NamespacedKey)` when convenient |
| `mysql-connector-java` relocated | Low | Maven artifact ID changed from `mysql:mysql-connector-java` to `com.mysql:mysql-connector-j`. Consider updating |
| `sun.misc.Unsafe::objectFieldOffset` | Info | Lombok internal warning — harmless, will be fixed in future Lombok versions |
| `MANIFEST.MF` overlap | Info | Standard shade plugin warning when merging JARs — no action needed |
