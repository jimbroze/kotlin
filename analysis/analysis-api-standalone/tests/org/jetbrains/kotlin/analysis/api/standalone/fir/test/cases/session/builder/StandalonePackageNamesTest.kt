/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.standalone.fir.test.cases.session.builder

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.platform.declarations.createDeclarationProvider
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProvider
import org.jetbrains.kotlin.analysis.api.platform.packages.createPackageProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.fir.test.AbstractStandaloneTest
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.codegen.forTestCompile.ForTestCompileRuntime
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.test.MockLibraryUtil
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.js.JsPlatforms
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StandalonePackageNamesTest : AbstractStandaloneTest() {
    override val suiteName: String
        get() = "behavior"

    @Test
    fun testJvmPackageProvider() {
        val sharedPlatform = JvmPlatforms.defaultJvmPlatform

        lateinit var sourceModule: KaSourceModule
        lateinit var stdlibModule: KaLibraryModule
        lateinit var kotlinTestModule: KaLibraryModule
        buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                val sdkModule = addModule(
                    buildKtSdkModule {
                        addBinaryRootsFromJdkHome(Paths.get(System.getProperty("java.home")), isJre = true)
                        addBinaryRootsFromJdkHome(Paths.get(System.getProperty("java.home")), isJre = false)
                        platform = sharedPlatform
                        libraryName = "JDK"
                    }
                )

                stdlibModule = addModule(
                    buildKtLibraryModule {
                        addBinaryRoot(ForTestCompileRuntime.runtimeJarForTests().toPath())
                        platform = sharedPlatform
                        libraryName = "stdlib"
                    }
                )

                kotlinTestModule = addModule(
                    buildKtLibraryModule {
                        addBinaryRoot(ForTestCompileRuntime.kotlinTestJarForTests().toPath())
                        platform = sharedPlatform
                        libraryName = "kotlin-test"
                    }
                )

                platform = sharedPlatform
                sourceModule = addModule(
                    buildKtSourceModule {
                        addSourceRoot(testDataPath("packageProvider"))
                        addSourceRoot(testDataPath("declarationlessPackage"))
                        addRegularDependency(sdkModule)
                        addRegularDependency(stdlibModule)
                        addRegularDependency(kotlinTestModule)
                        platform = sharedPlatform
                        moduleName = "source"
                    }
                )
            }
        }

        testPackageProvider(sourceModule) {
            checkPackageExistence("foo", isKotlinOnly = true, isPlatform = false, declarationProviderModule = sourceModule)
            checkPackageExistence("bar", isKotlinOnly = false, isPlatform = false, declarationProviderModule = sourceModule)
            // The package of a file without declarations must still exist (KT-83760).
            checkPackageExistence("declarationless", isKotlinOnly = true, isPlatform = false, declarationProviderModule = sourceModule)
            checkPackageExistence("kotlin", isKotlinOnly = true, isPlatform = true, declarationProviderModule = stdlibModule)
            checkPackageExistence("kotlin.collections", isKotlinOnly = true, isPlatform = true, declarationProviderModule = stdlibModule)
            // `kotlin.jvm.functions` contains only compiled Kotlin classes and no top-level members, so it is only found by scanning the
            // stdlib JAR's class files for Kotlin metadata (KT-83760).
            checkPackageExistence("kotlin.jvm.functions", isKotlinOnly = true, isPlatform = true, declarationProviderModule = stdlibModule)
            // `kotlin.test` comes from a non-stdlib JAR, which is not indexed: the package names are computed from the JAR contents
            // (KT-83760).
            checkPackageExistence("kotlin.test", isKotlinOnly = true, isPlatform = true, declarationProviderModule = kotlinTestModule)
            checkPackageExistence("java.lang", isKotlinOnly = false, isPlatform = true)
            checkPackageExistence("java.io", isKotlinOnly = false, isPlatform = true)

            checkSubpackages("foo", emptyList())
            checkSubpackages("bar", emptyList())
            checkSubpackages("kotlin", listOf("collections", "jvm", "js"))
            checkSubpackages("kotlin.collections", listOf("unsigned", "jdk8"))
            checkSubpackages("java", listOf("lang", "io"))
        }
    }

    @Test
    fun testJsPackageProvider() {
        val sharedPlatform = JsPlatforms.defaultJsPlatform

        lateinit var sourceModule: KaSourceModule
        lateinit var stdlibModule: KaLibraryModule
        buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                stdlibModule = addModule(
                    buildKtLibraryModule {
                        addBinaryRoot(ForTestCompileRuntime.stdlibJsForTests().toPath())
                        platform = sharedPlatform
                        libraryName = "stdlib"
                    }
                )

                platform = sharedPlatform
                sourceModule = addModule(
                    buildKtSourceModule {
                        addSourceRoot(testDataPath("packageProvider"))
                        addSourceRoot(testDataPath("declarationlessPackage"))
                        addRegularDependency(stdlibModule)
                        platform = sharedPlatform
                        moduleName = "source"
                    }
                )
            }
        }

        testPackageProvider(sourceModule) {
            checkPackageExistence("foo", isKotlinOnly = true, isPlatform = false, declarationProviderModule = sourceModule)
            checkPackageExistence("bar", isKotlinOnly = false, isPlatform = false, declarationProviderModule = sourceModule)
            // The package of a file without declarations must still exist (KT-83760).
            checkPackageExistence("declarationless", isKotlinOnly = true, isPlatform = false, declarationProviderModule = sourceModule)
            checkPackageExistence("kotlin", isKotlinOnly = true, isPlatform = false, declarationProviderModule = stdlibModule)
            checkPackageExistence("kotlin.collections", isKotlinOnly = true, isPlatform = false, declarationProviderModule = stdlibModule)
            checkPackageExistence(
                "kotlin.jvm.functions",
                isKotlinOnly = false,
                isPlatform = false,
                declarationProviderModule = stdlibModule,
            )
            checkPackageExistence("java.lang", isKotlinOnly = false, isPlatform = false)
            checkPackageExistence("java.io", isKotlinOnly = false, isPlatform = false)

            checkSubpackages("foo", emptyList())
            checkSubpackages("bar", emptyList())
            checkSubpackages("kotlin", listOf("collections", "jvm", "js"))
        }
    }

    @Test
    fun testUnpackedKlibPackageNames() {
        withUnpackedJsStdlib { tempKlibFolder ->
            val sharedPlatform = JsPlatforms.defaultJsPlatform

            lateinit var sourceModule: KaSourceModule
            lateinit var stdlibModule: KaLibraryModule
            buildStandaloneAnalysisAPISession(disposable) {
                buildKtModuleProvider {
                    stdlibModule = addModule(
                        buildKtLibraryModule {
                            addBinaryRoot(tempKlibFolder)
                            platform = sharedPlatform
                            libraryName = "stdlib"
                        }
                    )

                    platform = sharedPlatform
                    sourceModule = addModule(
                        buildKtSourceModule {
                            addSourceRoot(testDataPath("packageProvider"))
                            addRegularDependency(stdlibModule)
                            platform = sharedPlatform
                            moduleName = "source"
                        }
                    )
                }
            }

            testPackageProvider(sourceModule) {
                checkPackageExistence("foo", isKotlinOnly = true, isPlatform = false, declarationProviderModule = sourceModule)
                checkPackageExistence("bar", isKotlinOnly = false, isPlatform = false, declarationProviderModule = sourceModule)
                checkPackageExistence("kotlin", isKotlinOnly = true, isPlatform = false, declarationProviderModule = stdlibModule)
                checkPackageExistence(
                    "kotlin.collections",
                    isKotlinOnly = true,
                    isPlatform = false,
                    declarationProviderModule = stdlibModule,
                )
                checkPackageExistence(
                    "kotlin.jvm.functions",
                    isKotlinOnly = false,
                    isPlatform = false,
                    declarationProviderModule = stdlibModule,
                )
                checkPackageExistence("java.lang", isKotlinOnly = false, isPlatform = false)
                checkPackageExistence("java.io", isKotlinOnly = false, isPlatform = false)

                checkSubpackages("foo", emptyList())
                checkSubpackages("bar", emptyList())
                checkSubpackages("kotlin", listOf("collections", "jvm", "js"))
            }
        }
    }

    /**
     * Tests that every package name reported by `KotlinDeclarationProvider.computePackageNames` for a KLib library module is also known
     * to `KotlinPackageProvider.doesKotlinOnlyPackageExist`, i.e. the declaration provider's package set is a subset of the package
     * provider's, consistent with the shared package name computation (KT-83760).
     */
    @Test
    fun testKlibDeclarationProviderPackageNamesAreKnownToPackageProvider() {
        val sharedPlatform = JsPlatforms.defaultJsPlatform

        lateinit var libraryModule: KaLibraryModule
        buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                libraryModule = addModule(
                    buildKtLibraryModule {
                        addBinaryRoot(ForTestCompileRuntime.stdlibJsForTests().toPath())
                        platform = sharedPlatform
                        libraryName = "stdlib-js"
                    }
                )

                platform = sharedPlatform
            }
        }

        val packageProvider = libraryModule.project.createPackageProvider(libraryModule.contentScope)
        val declarationProvider = libraryModule.project.createDeclarationProvider(libraryModule.contentScope, libraryModule)

        val packageNamesFromDeclarationProvider = declarationProvider.computePackageNames()
        assertNotNull(packageNamesFromDeclarationProvider, "computePackageNames() must return non-null for a KLib library module")

        // Every package reported by computePackageNames() must also be known to the package provider
        for (packageName in packageNamesFromDeclarationProvider) {
            val fqName = FqName(packageName)
            assertTrue(
                packageProvider.doesKotlinOnlyPackageExist(fqName),
                "Package '$packageName' is in computePackageNames() but doesKotlinOnlyPackageExist() returns false for it",
            )
        }

        // Spot-check: a known stdlib package must be in both providers
        val kotlinFqName = FqName("kotlin")
        assertTrue(packageProvider.doesKotlinOnlyPackageExist(kotlinFqName), "Package 'kotlin' must exist in the package provider")
        assertTrue("kotlin" in packageNamesFromDeclarationProvider, "Package 'kotlin' must be in computePackageNames()")
    }

    /**
     * Tests that every package name reported by `KotlinDeclarationProvider.computePackageNames` for a source module is also known to
     * `KotlinPackageProvider.doesKotlinOnlyPackageExist`, i.e. both providers are backed by the same centralized package name
     * computation (KT-83760).
     */
    @Test
    fun testSourceDeclarationProviderPackageNamesAreKnownToPackageProvider() {
        val sharedPlatform = JvmPlatforms.defaultJvmPlatform

        lateinit var sourceModule: KaSourceModule
        buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                platform = sharedPlatform
                sourceModule = addModule(
                    buildKtSourceModule {
                        addSourceRoot(testDataPath("packageProvider"))
                        platform = sharedPlatform
                        moduleName = "source"
                    }
                )
            }
        }

        val packageProvider = sourceModule.project.createPackageProvider(sourceModule.contentScope)
        val declarationProvider = sourceModule.project.createDeclarationProvider(sourceModule.contentScope, sourceModule)

        val packageNames = declarationProvider.computePackageNames()
        assertNotNull(packageNames, "computePackageNames() must return a non-null set for a source module")
        assertTrue("foo" in packageNames, "Package 'foo' must be in computePackageNames() for the test sources")

        for (packageName in packageNames) {
            val fqName = FqName(packageName)
            assertTrue(
                packageProvider.doesKotlinOnlyPackageExist(fqName),
                "Package '$packageName' is in computePackageNames() but doesKotlinOnlyPackageExist() returns false for it",
            )
        }
    }

    /**
     * Tests that every package name reported by `KotlinDeclarationProvider.computePackageNames` for a JAR library module is also known
     * to `KotlinPackageProvider.doesKotlinOnlyPackageExist`, i.e. both providers are backed by the same centralized package name
     * computation for non-indexed JARs (KT-83760).
     */
    @Test
    fun testJarDeclarationProviderPackageNamesAreKnownToPackageProvider() {
        val sharedPlatform = JvmPlatforms.defaultJvmPlatform

        lateinit var libraryModule: KaLibraryModule
        buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                libraryModule = addModule(
                    buildKtLibraryModule {
                        addBinaryRoot(ForTestCompileRuntime.runtimeJarForTests().toPath())
                        platform = sharedPlatform
                        libraryName = "stdlib"
                    }
                )

                platform = sharedPlatform
            }
        }

        val packageProvider = libraryModule.project.createPackageProvider(libraryModule.contentScope)
        val declarationProvider = libraryModule.project.createDeclarationProvider(libraryModule.contentScope, libraryModule)

        val packageNamesFromDeclarationProvider = declarationProvider.computePackageNames()
        assertNotNull(packageNamesFromDeclarationProvider, "computePackageNames() must return non-null for a JAR library module")

        // Every package reported by computePackageNames() must also be known to the package provider
        for (packageName in packageNamesFromDeclarationProvider) {
            val fqName = FqName(packageName)
            assertTrue(
                packageProvider.doesKotlinOnlyPackageExist(fqName),
                "Package '$packageName' is in computePackageNames() but doesKotlinOnlyPackageExist() returns false for it",
            )
        }

        // Spot-check: a package with top-level members (found through the JAR's `.kotlin_module` file) and a package with only compiled
        // classes (found by scanning class files for Kotlin metadata) must be in both providers
        for (packageName in listOf("kotlin", "kotlin.jvm.functions")) {
            assertTrue(
                packageProvider.doesKotlinOnlyPackageExist(FqName(packageName)),
                "Package '$packageName' must exist in the package provider",
            )
            assertTrue(packageName in packageNamesFromDeclarationProvider, "Package '$packageName' must be in computePackageNames()")
        }
    }

    /**
     * Tests that Kotlin package names of a non-indexed JAR are computed precisely (KT-83760): packages that contain only non-Kotlin class
     * files must not be reported as Kotlin packages, while Kotlin packages must be reported whether they are listed in the JAR's
     * `.kotlin_module` file (packages with top-level members) or only contain compiled Kotlin classes.
     */
    @Test
    fun testMixedJarLibraryKotlinOnlyPackages() {
        val sharedPlatform = JvmPlatforms.defaultJvmPlatform
        val libraryJar = MockLibraryUtil.compileJvmLibraryToJar(testDataPath("mixedJarLibrary").toString(), "mixedJarLibrary").toPath()

        lateinit var sourceModule: KaSourceModule
        lateinit var libraryModule: KaLibraryModule
        buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                libraryModule = addModule(
                    buildKtLibraryModule {
                        addBinaryRoot(libraryJar)
                        platform = sharedPlatform
                        libraryName = "mixedJarLibrary"
                    }
                )

                platform = sharedPlatform
                sourceModule = addModule(
                    buildKtSourceModule {
                        addSourceRoot(testDataPath("packageProvider"))
                        addRegularDependency(libraryModule)
                        platform = sharedPlatform
                        moduleName = "source"
                    }
                )
            }
        }

        testPackageProvider(sourceModule) {
            // A package with top-level members, listed in the JAR's `.kotlin_module` file.
            checkPackageExistence("mixedlib.facades", isKotlinOnly = true, isPlatform = true, declarationProviderModule = libraryModule)
            // A package with only a compiled Kotlin class, found by scanning class files for Kotlin metadata.
            checkPackageExistence("mixedlib.classes", isKotlinOnly = true, isPlatform = true, declarationProviderModule = libraryModule)
            // A package with only a Java class must not be reported as a Kotlin package.
            checkPackageExistence("mixedlib.javaonly", isKotlinOnly = false, isPlatform = true, declarationProviderModule = libraryModule)
        }
    }

    /**
     * Tests that a JAR without any Kotlin class files reports an empty — but known — Kotlin package set (KT-83760).
     */
    @Test
    fun testJavaOnlyJarLibraryHasNoKotlinPackages() {
        val sharedPlatform = JvmPlatforms.defaultJvmPlatform
        val libraryJar =
            MockLibraryUtil.compileJvmLibraryToJar(testDataPath("javaOnlyJarLibrary").toString(), "javaOnlyJarLibrary").toPath()

        lateinit var libraryModule: KaLibraryModule
        buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                libraryModule = addModule(
                    buildKtLibraryModule {
                        addBinaryRoot(libraryJar)
                        platform = sharedPlatform
                        libraryName = "javaOnlyJarLibrary"
                    }
                )

                platform = sharedPlatform
            }
        }

        val packageProvider = libraryModule.project.createPackageProvider(libraryModule.contentScope)
        val declarationProvider = libraryModule.project.createDeclarationProvider(libraryModule.contentScope, libraryModule)

        val packageNames = declarationProvider.computePackageNames()
        assertNotNull(packageNames, "computePackageNames() must return non-null for a JAR library module")
        assertEquals(emptySet(), packageNames, "A Java-only JAR must not contribute any Kotlin package names")

        assertFalse(
            packageProvider.doesKotlinOnlyPackageExist(FqName("javaonly")),
            "The package of a Java-only JAR must not exist as a Kotlin-only package",
        )
        assertTrue(
            packageProvider.doesPlatformSpecificPackageExist(FqName("javaonly"), sharedPlatform),
            "The package of a Java-only JAR must exist as a platform package",
        )
    }

    private class PackageProviderTestContext(
        private val session: KaSession,
        private val packageProvider: KotlinPackageProvider,
        private val targetPlatform: TargetPlatform,
    ) {
        fun checkPackageExistence(
            name: String,
            isKotlinOnly: Boolean,
            isPlatform: Boolean,
            declarationProviderModule: KaModule? = null,
        ) {
            fun check(expected: Boolean, message: String, block: () -> Boolean) {
                if (expected) {
                    assertTrue(block(), message)
                } else {
                    assertFalse(block(), message.replace("must", "must not"))
                }
            }

            val packageFqName = FqName(name)
            check(isKotlinOnly || isPlatform, "Package '$packageFqName' must exist") {
                packageProvider.doesPackageExist(packageFqName, targetPlatform)
            }
            check(isKotlinOnly || isPlatform, "Package '$packageFqName' must be visible through 'KaSession.findPackage()'") {
                with(session) { findPackage(packageFqName) != null }
            }
            check(isKotlinOnly, "Kotlin-only package '$packageFqName' must exist") {
                packageProvider.doesKotlinOnlyPackageExist(packageFqName)
            }
            check(isPlatform, "Platform-specific package '$packageFqName' must exist") {
                packageProvider.doesPlatformSpecificPackageExist(packageFqName, targetPlatform)
            }

            declarationProviderModule?.let { module ->
                val declarationProvider = module.project.createDeclarationProvider(module.contentScope, module)
                val packageNames = declarationProvider.computePackageNames()
                assertNotNull(packageNames, "computePackageNames() must return a non-null set for module '$module'")
                check(
                    isKotlinOnly,
                    "Kotlin-only package '$packageFqName' must be reported by computePackageNames() for module '$module'",
                ) {
                    name in packageNames
                }
            }
        }

        fun checkSubpackages(name: String, expectedInside: List<String>) {
            val packageFqName = FqName(name)
            val actualSubpackages = packageProvider.getSubpackageNames(packageFqName, targetPlatform)
                .mapTo(HashSet()) { it.asString() }

            if (expectedInside.isEmpty()) {
                assertEquals(emptySet(), actualSubpackages, "Subpackages of '$packageFqName' must be empty")
            } else {
                for (expectedSubpackage in expectedInside) {
                    val isInside = expectedSubpackage in actualSubpackages
                    assertTrue(isInside, "Subpackage '$name.$expectedSubpackage' must exist")
                }
            }
        }
    }

    private fun testPackageProvider(module: KaModule, block: context(KaSession) PackageProviderTestContext.() -> Unit) {
        val targetPlatform = module.targetPlatform

        analyze(module) {
            val packageProvider = module.project.createPackageProvider(analysisScope)
            val packageProviderTestContext = PackageProviderTestContext(useSiteSession, packageProvider, targetPlatform)
            block(packageProviderTestContext)
        }
    }
}
