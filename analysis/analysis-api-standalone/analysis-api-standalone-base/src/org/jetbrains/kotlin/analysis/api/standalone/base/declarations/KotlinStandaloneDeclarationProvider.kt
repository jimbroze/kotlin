/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.standalone.base.declarations

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.*
import org.jetbrains.kotlin.analysis.api.platform.KotlinDeserializedDeclarationsOrigin
import org.jetbrains.kotlin.analysis.api.platform.KotlinPlatformSettings
import org.jetbrains.kotlin.analysis.api.platform.declarations.*
import org.jetbrains.kotlin.analysis.api.platform.mergeSpecificProviders
import org.jetbrains.kotlin.analysis.api.projectStructure.*
import org.jetbrains.kotlin.analysis.api.standalone.base.packages.KotlinStandalonePackageNamesProvider
import org.jetbrains.kotlin.fileClasses.javaFileFacadeFqName
import org.jetbrains.kotlin.name.*
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.stubs.impl.*
import org.jetbrains.kotlin.utils.addToStdlib.flattenTo

class KotlinStandaloneDeclarationProvider internal constructor(
    private val index: KotlinStandaloneDeclarationIndex,
    val scope: GlobalSearchScope,
    private val contextualModule: KaModule?,
) : KotlinDeclarationProvider {
    private val KtElement.inScope: Boolean
        get() = containingKtFile.virtualFile in scope

    override fun getClassLikeDeclarationByClassId(classId: ClassId): KtClassLikeDeclaration? {
        return getAllClassesByClassId(classId).firstOrNull()
            ?: getAllTypeAliasesByClassId(classId).firstOrNull()
    }

    override fun getAllClassesByClassId(classId: ClassId): Collection<KtClassOrObject> =
        index.classesByClassId[classId]
            ?.filter { it.inScope }
            ?: emptyList()

    override fun getAllTypeAliasesByClassId(classId: ClassId): Collection<KtTypeAlias> =
        index.typeAliasesByClassId[classId]
            ?.filter { it.inScope }
            ?: emptyList()

    override fun getTopLevelKotlinClassLikeDeclarationNamesInPackage(packageFqName: FqName): Set<Name> {
        val classifiers = index.classLikeDeclarationsByPackage[packageFqName].orEmpty()
        return classifiers
            .filter { it.inScope }
            .mapNotNullTo(mutableSetOf()) { it.nameAsName }
    }

    override fun getTopLevelCallableNamesInPackage(packageFqName: FqName): Set<Name> {
        val callables = index.topLevelCallablesByPackage[packageFqName].orEmpty()
        return callables
            .filter { it.inScope }
            .mapNotNullTo(mutableSetOf()) { it.nameAsName }
    }

    override fun findFilesForFacadeByPackage(packageFqName: FqName): Collection<KtFile> {
        return index.facadeFileMap[packageFqName].orEmpty().filter { it.virtualFile in scope }
    }

    override fun findFilesForFacade(facadeFqName: FqName): Collection<KtFile> {
        if (facadeFqName.shortNameOrSpecial().isSpecial) return emptyList()
        return findFilesForFacadeByPackage(facadeFqName.parent()) //TODO Not work correctly for classes with JvmPackageName
            .filter { it.javaFileFacadeFqName == facadeFqName }
    }

    override fun findInternalFilesForFacade(facadeFqName: FqName): Collection<KtFile> {
        return index.multiFileClassPartMap[facadeFqName].orEmpty().filter { it.virtualFile in scope }
    }

    override fun findFilesForScript(scriptFqName: FqName): Collection<KtScript> {
        return index.scriptMap[scriptFqName].orEmpty().filter { it.containingKtFile.virtualFile in scope }
    }

    // It is generally an *advantage* to have non-specific package set computations, as some components only work with general package sets.
    override val hasSpecificClassifierPackageNamesComputation: Boolean get() = false
    override val hasSpecificCallablePackageNamesComputation: Boolean get() = false

    override fun computePackageNames(): Set<String>? =
        when (contextualModule) {
            is KaSourceModule, is KaScriptModule, is KaNotUnderContentRootModule ->
                computePackageSetFromIndex(contextualModule)

            is KaLibraryModule ->
                if (contextualModule.canComputePackageSetFromIndex) {
                    computePackageSetFromIndex(contextualModule)
                } else {
                    KotlinStandalonePackageNamesProvider.getInstance(contextualModule.project)
                        .computeLibraryPackageNames(scope)
                        ?.mapTo(mutableSetOf()) { it.asString() }
                }

            else -> null
        }

    /**
     * For library modules, we can only compute a package set from the index when we use stubs, as we don't index binary dependencies
     * otherwise.
     */
    private val KaLibraryModule.canComputePackageSetFromIndex: Boolean
        get() = KotlinPlatformSettings.getInstance(project).deserializedDeclarationsOrigin == KotlinDeserializedDeclarationsOrigin.STUBS

    private fun computePackageSetFromIndex(module: KaModule): Set<String> =
        KotlinStandalonePackageNamesProvider.getInstance(module.project)
            .computePackageNamesFromIndex(scope)
            .mapTo(mutableSetOf()) { it.asString() }

    override fun getTopLevelProperties(callableId: CallableId): Collection<KtProperty> =
        index.topLevelPropertiesByCallableId[callableId]
            ?.filter { it.inScope }
            ?: emptyList()

    override fun getTopLevelFunctions(callableId: CallableId): Collection<KtNamedFunction> =
        index.topLevelFunctionsByCallableId[callableId]
            ?.filter { it.inScope }
            ?: emptyList()

    override fun getTopLevelCallableFiles(callableId: CallableId): Collection<KtFile> = buildSet {
        getTopLevelProperties(callableId).mapTo(this) { it.containingKtFile }
        getTopLevelFunctions(callableId).mapTo(this) { it.containingKtFile }
    }
}

/**
 * [binaryRoots] and [sharedBinaryRoots] are used to build stubs for symbols from binary libraries. They only need to be specified if
 * [shouldBuildStubsForBinaryLibraries] is true. In Standalone mode, binary roots don't need to be specified because library symbols are
 * provided via class-based deserialization, not stub-based deserialization.
 *
 * The created declaration providers compute their package names through
 * [KotlinStandalonePackageNamesProvider][org.jetbrains.kotlin.analysis.api.standalone.base.packages.KotlinStandalonePackageNamesProvider],
 * which must be registered as a project service with this factory instance (see its documentation).
 *
 * @param binaryRoots Binary roots of the binary libraries that are specific to [project].
 * @param sharedBinaryRoots Binary roots that are shared between multiple different projects. This allows Kotlin tests to cache stubs for
 *  shared libraries like the Kotlin stdlib.
 * @param postponeIndexing Whether to postpone indexing until the first access.
 *  This is useful for tests to reduce the startup time and potentially avoid redundant indexing (which might be heavy, especially if stubs are used).
 */
class KotlinStandaloneDeclarationProviderFactory(
    private val project: Project,
    sourceKtFiles: Collection<KtFile>,
    binaryRoots: List<VirtualFile> = emptyList(),
    sharedBinaryRoots: List<VirtualFile> = emptyList(),
    skipBuiltins: Boolean = false,
    shouldBuildStubsForBinaryLibraries: Boolean = false,
    postponeIndexing: Boolean = false,
) : KotlinDeclarationProviderFactory {
    private val indexData: KotlinStandaloneIndexBuilder.IndexData = KotlinStandaloneIndexBuilder(
        project = project,
        shouldBuildStubsForDecompiledFiles = shouldBuildStubsForBinaryLibraries,
        postponeIndexing = postponeIndexing,
    ) {
        collectSourceFiles(sourceKtFiles)

        if (!skipBuiltins) {
            collectDecompiledFilesFromBuiltins()
        }

        // We only need to index binary roots if we deserialize compiled symbols from stubs. When deserializing from class files, we don't
        // need these symbols in the declaration provider.
        if (shouldBuildStubsForBinaryLibraries) {
            for (root in sharedBinaryRoots) {
                collectDecompiledFilesFromBinaryRoot(root, isSharedRoot = true)
            }

            for (root in binaryRoots) {
                collectDecompiledFilesFromBinaryRoot(root, isSharedRoot = false)
            }
        }
    }

    internal val index: KotlinStandaloneDeclarationIndex
        get() = indexData.index

    override fun createDeclarationProvider(scope: GlobalSearchScope, contextualModule: KaModule?): KotlinDeclarationProvider {
        return KotlinStandaloneDeclarationProvider(index, scope, contextualModule)
    }

    fun getAllKtClasses(): List<KtClassOrObject> = index.classesByClassId.values.flattenTo(mutableListOf())

    fun getDirectInheritorCandidates(baseClassName: Name): Set<KtClassOrObject> =
        index.classesBySupertypeName[baseClassName].orEmpty()

    fun getInheritableTypeAliases(aliasedName: Name): Set<KtTypeAlias> =
        index.inheritableTypeAliasesByAliasedName[aliasedName].orEmpty()
}

class KotlinStandaloneDeclarationProviderMerger(private val project: Project) : KotlinDeclarationProviderMerger {
    override fun merge(providers: List<KotlinDeclarationProvider>): KotlinDeclarationProvider =
        providers.mergeSpecificProviders<_, KotlinStandaloneDeclarationProvider>(KotlinCompositeDeclarationProvider.factory) { targetProviders ->
            val combinedScope = GlobalSearchScope.union(targetProviders.map { it.scope })
            project.createDeclarationProvider(combinedScope, contextualModule = null).apply {
                check(this is KotlinStandaloneDeclarationProvider) {
                    "`KotlinStandaloneDeclarationProvider` can only be merged into a combined declaration provider of the same type."
                }
            }
        }
}
