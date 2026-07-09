/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.standalone.base.packages

import com.github.benmanes.caffeine.cache.Caffeine
import com.intellij.ide.highlighter.JavaClassFileType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.standalone.base.declarations.KotlinStandaloneDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.decompiler.stub.file.ClsKotlinBinaryClassCache
import org.jetbrains.kotlin.library.KlibConstants.KLIB_FILE_EXTENSION
import org.jetbrains.kotlin.library.components.metadata
import org.jetbrains.kotlin.library.loader.KlibLoader
import org.jetbrains.kotlin.library.metadata.parseModuleHeader
import org.jetbrains.kotlin.metadata.jvm.deserialization.ModuleMapping
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtElement
import java.nio.file.Path
import java.util.Optional
import kotlin.io.path.extension

/**
 * A unified source of Kotlin package names shared between [KotlinStandalonePackageProvider] and
 * [org.jetbrains.kotlin.analysis.api.standalone.base.declarations.KotlinStandaloneDeclarationProvider], so that both providers report
 * consistent package sets (KT-83760).
 *
 * Package names are computed with three strategies:
 *
 * - From the declaration index of [declarationProviderFactory], which covers source files and binary libraries when they are indexed as
 *   stubs (see [org.jetbrains.kotlin.analysis.api.platform.KotlinDeserializedDeclarationsOrigin.STUBS]).
 * - From KLib metadata for KLib library roots.
 * - From JAR library roots, which are not indexed in production Standalone mode
 *   (see [org.jetbrains.kotlin.analysis.api.platform.KotlinDeserializedDeclarationsOrigin.BINARIES]). Because the resulting package names
 *   feed [KotlinStandalonePackageProvider.doesKotlinOnlyPackageExist], the computation must be precise: it has to distinguish Kotlin class
 *   files from other JVM class files instead of deriving a package superset from the directory structure. Reading every class file of a
 *   JAR to check for Kotlin metadata would be expensive, so the computation is lazy, cached per root, and layered (see [jarPackages]).
 *
 * Library roots that are not covered by these strategies (e.g. JDK/JRT roots or in-memory class files) have no Kotlin package name
 * computation. [computeLibraryPackageNames] returns `null` when such a root is in scope, while [getPackageNamesInScope] skips it and
 * returns the package names of all covered roots.
 *
 * The provider must be registered as a project service whenever [KotlinStandaloneDeclarationProviderFactory] or
 * [KotlinStandalonePackageProviderFactory] is registered, with [declarationProviderFactory] being the same instance that is registered as
 * the `KotlinDeclarationProviderFactory`.
 */
class KotlinStandalonePackageNamesProvider(
    private val declarationProviderFactory: KotlinStandaloneDeclarationProviderFactory,
    libraryRoots: List<VirtualFile>,
) {
    companion object {
        fun getInstance(project: Project): KotlinStandalonePackageNamesProvider = project.service()
    }

    /**
     * A mapping from a KLib library root [VirtualFile] to the [Path] of the `.klib` file or unpacked KLib directory that contains it.
     */
    private val klibFiles = mutableMapOf<VirtualFile, Path>()

    /**
     * JAR library roots on the JAR file system that are not KLibs, i.e. roots of JVM class file libraries.
     */
    private val jarRoots = mutableSetOf<VirtualFile>()

    /**
     * Library roots without a Kotlin package name computation (see the class documentation).
     */
    private val uncoveredRoots = mutableListOf<VirtualFile>()

    init {
        for (libraryRoot in libraryRoots) {
            if (libraryRoot.fileSystem.protocol == StandardFileSystems.JAR_PROTOCOL) {
                // Root entry in a JAR or packed KLib archive
                val libraryFile = runCatching { VfsUtilCore.getVirtualFileForJar(libraryRoot)?.toNioPath() }.getOrNull()
                when {
                    libraryFile == null -> uncoveredRoots.add(libraryRoot)
                    libraryFile.extension.lowercase() == KLIB_FILE_EXTENSION -> klibFiles[libraryRoot] = libraryFile
                    else -> jarRoots.add(libraryRoot)
                }
            } else if (libraryRoot.isDirectory) {
                // Unpacked Kotlin library (a tree of directories with individual '.knm' files). Directories that fail to load as KLibs
                // are treated as uncovered roots when the packages are computed (see `klibPackages`).
                val libraryFile = runCatching { libraryRoot.toNioPath() }.getOrNull()
                if (libraryFile != null) {
                    klibFiles[libraryRoot] = libraryFile
                } else {
                    uncoveredRoots.add(libraryRoot)
                }
            } else {
                uncoveredRoots.add(libraryRoot)
            }
        }
    }

    /**
     * Kotlin package names per KLib library file. An empty [Optional] means that the file could not be loaded as a KLib (e.g. a directory
     * root that is not an unpacked KLib), in which case the root counts as uncovered.
     */
    private val klibPackages = Caffeine
        .newBuilder()
        .maximumSize(1000)
        .build<Path, Optional<Set<FqName>>> { libraryFile ->
            val loaderResult = KlibLoader { libraryPaths(libraryFile) }.load()
            if (loaderResult.hasProblems) return@build Optional.empty()

            Optional.of(
                buildSet {
                    for (kotlinLibrary in loaderResult.librariesStdlibFirst) {
                        val moduleHeader = parseModuleHeader(kotlinLibrary.metadata.moduleHeaderData)
                        for (packageNameString in moduleHeader.packageFragmentNameList) {
                            add(FqName(packageNameString))
                        }
                    }
                }
            )
        }

    /**
     * Kotlin package names per JAR library root. The computation must be precise (see the class documentation), so it is layered to avoid
     * reading every class file of the JAR:
     *
     * 1. `.kotlin_module` files in `META-INF` enumerate all packages with top-level members (file facades and multi-file class parts),
     *    including packages remapped with `JvmPackageName`, without reading any class files.
     * 2. The remaining directories are scanned for Kotlin class files with a cached, header-only check for Kotlin metadata
     *    ([ClsKotlinBinaryClassCache]). Since all class files of a directory share the same package, scanning a directory stops at the
     *    first Kotlin class file. Directories whose package is already known from step 1 — for Kotlin JARs, usually most of them — are not
     *    scanned at all. Only packages that consist entirely of non-Kotlin class files must read every class file header to prove the
     *    absence of Kotlin metadata.
     */
    private val jarPackages = Caffeine
        .newBuilder()
        .maximumSize(1000)
        .build<VirtualFile, Set<FqName>> { jarRoot ->
            val packageNames = mutableSetOf<FqName>()
            collectPackageNamesFromModuleMappings(jarRoot, packageNames)
            collectPackageNamesFromClassFiles(jarRoot, jarRoot, ClsKotlinBinaryClassCache.getInstance(), packageNames)
            packageNames
        }

    private fun collectPackageNamesFromModuleMappings(jarRoot: VirtualFile, packageNames: MutableSet<FqName>) {
        val metaInf = jarRoot.findChild("META-INF") ?: return
        for (moduleFile in metaInf.children) {
            if (!moduleFile.name.endsWith(ModuleMapping.MAPPING_FILE_EXT)) continue

            // The metadata version check is skipped because package existence does not depend on the ABI compatibility of the library.
            val moduleMapping = runCatching {
                ModuleMapping.loadModuleMapping(
                    moduleFile.contentsToByteArray(),
                    debugName = moduleFile.toString(),
                    skipMetadataVersionCheck = true,
                    isJvmPackageNameSupported = true,
                ) {}
            }.getOrDefault(ModuleMapping.CORRUPTED)

            for (packageNameString in moduleMapping.packageFqName2Parts.keys) {
                packageNames.add(FqName(packageNameString))
            }
        }
    }

    private fun collectPackageNamesFromClassFiles(
        directory: VirtualFile,
        jarRoot: VirtualFile,
        binaryClassCache: ClsKotlinBinaryClassCache,
        packageNames: MutableSet<FqName>,
    ) {
        val directoryPackageName = directoryPackageName(directory, jarRoot)
        var isDirectoryPackageKnown = directoryPackageName != null && directoryPackageName in packageNames

        for (child in directory.children) {
            if (child.isDirectory) {
                collectPackageNamesFromClassFiles(child, jarRoot, binaryClassCache, packageNames)
            } else if (!isDirectoryPackageKnown && child.extension == JavaClassFileType.DEFAULT_EXTENSION) {
                val headerData = binaryClassCache.getKotlinBinaryClassHeaderData(child) ?: continue

                // The package name is taken from the class file header instead of the directory path so that classes remapped with
                // `JvmPackageName` (or unusually placed class files, e.g. in multi-release JARs) report their Kotlin package.
                val packageName = headerData.packageNameWithFallback
                packageNames.add(packageName)

                // A class remapped to another package does not prove that the directory's own package contains Kotlin declarations, so
                // scanning may only stop once a class of the directory's package is found.
                if (packageName == directoryPackageName) {
                    isDirectoryPackageKnown = true
                }
            }
        }
    }

    private fun directoryPackageName(directory: VirtualFile, jarRoot: VirtualFile): FqName? {
        val relativePath = VfsUtilCore.getRelativePath(directory, jarRoot, '/') ?: return null
        if (relativePath.isEmpty()) return FqName.ROOT
        return FqName(relativePath.replace('/', '.'))
    }

    /**
     * Computes the package names of all indexed files and declarations contained in [scope]. This covers source files and, when binary
     * libraries are indexed as stubs, library declarations.
     *
     * The packages of indexed files are included even when a file contains no declarations, so that every package mentioned in a package
     * directive exists (consistent with the IDE, where packages are backed by a file-based index).
     */
    fun computePackageNamesFromIndex(scope: GlobalSearchScope): Set<FqName> = buildSet {
        addPackageNamesInScope(declarationProviderFactory.index.filesByPackage, scope)
        addPackageNamesInScope(declarationProviderFactory.index.classLikeDeclarationsByPackage, scope)
        addPackageNamesInScope(declarationProviderFactory.index.topLevelCallablesByPackage, scope)
    }

    private fun <T : KtElement> MutableSet<FqName>.addPackageNamesInScope(map: Map<FqName, Set<T>>, scope: GlobalSearchScope) {
        map.forEach { [fqName, elements] ->
            if (elements.any { it.containingKtFile.virtualFile in scope }) {
                add(fqName)
            }
        }
    }

    /**
     * Computes the package names of all KLib and JAR library roots contained in [scope], or `null` if the package names cannot be
     * computed exhaustively: when no library root is contained in [scope] at all, or when [scope] contains an uncovered root (see the
     * class documentation).
     *
     * The `null` result allows callers such as
     * [KotlinStandaloneDeclarationProvider.computePackageNames][org.jetbrains.kotlin.analysis.api.standalone.base.declarations.KotlinStandaloneDeclarationProvider.computePackageNames]
     * to report that the package set of a library module is unknown rather than incomplete.
     */
    fun computeLibraryPackageNames(scope: GlobalSearchScope): Set<FqName>? = collectLibraryPackageNames(scope, isExhaustive = true)

    /**
     * Returns all Kotlin package names known to this provider in [scope]: the packages of indexed declarations and of KLib and JAR
     * library roots. Unlike [computeLibraryPackageNames], uncovered roots in [scope] are skipped rather than failing the computation.
     */
    fun getPackageNamesInScope(scope: GlobalSearchScope): Set<FqName> =
        computePackageNamesFromIndex(scope) + (collectLibraryPackageNames(scope, isExhaustive = false) ?: emptySet())

    /**
     * @param isExhaustive Whether the resulting package set must cover every library root in [scope]. If `true`, `null` is returned when
     *  a root without a package name computation is in [scope]. If `false`, such roots are skipped.
     */
    private fun collectLibraryPackageNames(scope: GlobalSearchScope, isExhaustive: Boolean): Set<FqName>? {
        if (isExhaustive && uncoveredRoots.any { scope.contains(it) }) return null

        var foundLibraryRoot = false
        val packages = mutableSetOf<FqName>()

        for ([libraryRoot, libraryFile] in klibFiles) {
            if (!scope.contains(libraryRoot)) continue

            val klibPackageNames = klibPackages[libraryFile]?.orElse(null)
            if (klibPackageNames == null) {
                // The root could not be loaded as a KLib, so it counts as uncovered.
                if (isExhaustive) return null
                continue
            }

            foundLibraryRoot = true
            packages.addAll(klibPackageNames)
        }

        for (jarRoot in jarRoots) {
            if (!scope.contains(jarRoot)) continue

            foundLibraryRoot = true
            packages.addAll(jarPackages[jarRoot] ?: emptyList())
        }

        return if (foundLibraryRoot) packages else null
    }
}
