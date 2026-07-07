/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.standalone.fir.test.cases.session.builder

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.fir.test.AbstractStandaloneTest
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.codegen.forTestCompile.ForTestCompileRuntime
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.platform.js.JsPlatforms
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.test.services.StandardLibrariesPathProviderForKotlinProject
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StandaloneBehaviorTest : AbstractStandaloneTest() {
    override val suiteName: String
        get() = "behavior"

    @Test
    fun testStubbedAnnotationArguments() {
        lateinit var sourceModule: KaSourceModule
        val session = buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                val stdlibModule = addModule(
                    buildKtLibraryModule {
                        addBinaryRoot(StandardLibrariesPathProviderForKotlinProject.runtimeJarForTests().toPath())
                        platform = JvmPlatforms.defaultJvmPlatform
                        libraryName = "stdlib"
                    }
                )

                platform = JvmPlatforms.defaultJvmPlatform
                sourceModule = addModule(
                    buildKtSourceModule {
                        addSourceRoot(testDataPath("stubbedAnnotationArguments"))
                        addRegularDependency(stdlibModule)
                        platform = JvmPlatforms.defaultJvmPlatform
                        moduleName = "source"
                    }
                )
            }
        }

        val ktFile = session.modulesWithFiles.getValue(sourceModule).single() as KtFile
        assert(ktFile.stub != null)

        val mainClass = ktFile.declarations.first { it is KtClass && it.name == "Main" } as KtClass
        val parameter = mainClass.primaryConstructor!!.valueParameters.first()

        analyze(parameter) {
            for (annotation in parameter.symbol.annotations) {
                for (argument in annotation.arguments) {
                    val argumentExpression = argument.expression
                    if (argumentExpression is KaAnnotationValue.ArrayValue) {
                        assertEquals(3, argumentExpression.values.size)
                        return
                    }
                }
            }
        }

        error("Annotation argument wasn't found")
    }

    @Test
    fun testNestedTypeAliasIndexing() {
        lateinit var sourceModule: KaSourceModule
        val session = buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                val stdlibModule = addModule(
                    buildKtLibraryModule {
                        addBinaryRoot(StandardLibrariesPathProviderForKotlinProject.runtimeJarForTests().toPath())
                        platform = JvmPlatforms.defaultJvmPlatform
                        libraryName = "stdlib"
                    }
                )

                platform = JvmPlatforms.defaultJvmPlatform
                sourceModule = addModule(
                    buildKtSourceModule {
                        addSourceRoot(testDataPath("nestedTypeAlias"))
                        addRegularDependency(stdlibModule)
                        platform = JvmPlatforms.defaultJvmPlatform
                        moduleName = "source"
                    }
                )
            }
        }

        val ktFile = session.modulesWithFiles.getValue(sourceModule).single() as KtFile
        assert(ktFile.stub != null)

        val topLevelTypeAlias = ktFile.declarations
            .filterIsInstance<KtTypeAlias>()
            .first { it.name == "AliasToTopLevelClassInsideNested" }

        analyze(topLevelTypeAlias) {
            val typeAliasSymbol = topLevelTypeAlias.symbol
            val expandedType = typeAliasSymbol.expandedType

            assertIs<KaClassType>(expandedType)
            assertEquals(
                ClassId(FqName("kotlin"), Name.identifier("String")),
                expandedType.classId
            )
        }
    }

    @Test
    fun testUnpackedKlibDependency() {
        val klibFile = ForTestCompileRuntime.stdlibJsForTests()
        val tempKlibFolder = Files.createTempDirectory(klibFile.name)

        try {
            ZipFile(klibFile).use { zipFile ->
                for (zipEntry in zipFile.entries()) {
                    val targetPath = tempKlibFolder.resolve(zipEntry.name)
                    if (zipEntry.isDirectory) {
                        Files.createDirectories(targetPath)
                    } else {
                        Files.createDirectories(targetPath.parent)
                        zipFile.getInputStream(zipEntry).use { input ->
                            Files.copy(input, targetPath)
                        }
                    }
                }
            }

            val sharedPlatform = JsPlatforms.defaultJsPlatform

            lateinit var sourceModule: KaSourceModule
            lateinit var stdlibModule: KaLibraryModule
            val standaloneSession = buildStandaloneAnalysisAPISession(disposable) {
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

            val ktFile = standaloneSession.modulesWithFiles.getValue(sourceModule).single() as KtFile
            val testFunction = ktFile.declarations.filterIsInstance<KtNamedFunction>().single()
            analyze(ktFile) {
                @OptIn(KaExperimentalApi::class)
                val listOfStringsType = typeCreator.classType(StandardClassIds.List) {
                    invariantTypeArgument(builtinTypes.string)
                }

                assertEquals(listOfStringsType, testFunction.returnType)
            }
        } finally {
            @OptIn(ExperimentalPathApi::class)
            tempKlibFolder.deleteRecursively()
        }
    }
}
