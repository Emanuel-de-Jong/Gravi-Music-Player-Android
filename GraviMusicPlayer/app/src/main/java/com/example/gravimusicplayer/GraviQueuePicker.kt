package com.example.gravimusicplayer

import android.content.Context
import kotlin.random.Random

class GraviQueuePicker(
    context: Context,
    private val random: Random = Random.Default,
) {
    private val performanceProfiler = PerformanceProfiler.get(context)

    fun buildGraviQueue(
        items: List<AudioItem>,
        settings: GraviPickerSettings,
        scopeFolderPath: String = "",
    ): List<AudioItem> {
        return performanceProfiler.measure("GraviQueuePicker.buildGraviQueue") {
            val safeSettings = settings.sanitized()
            val categoryEntries = buildCategoryEntries(items, safeSettings, scopeFolderPath)
            if (categoryEntries.isEmpty()) return@measure emptyList()

            val allItems = categoryEntries
                .flatMap { it.items }
                .distinctBy { it.audioItem.uriString }
                .sortedBy { it.audioItem.folderPath + "/" + it.audioItem.title }
            val allUriStrings = allItems.map { it.audioItem.uriString }
            val remainingUriStrings = allUriStrings.toMutableSet()
            val selectedItems = mutableListOf<AudioItem>()

            while (selectedItems.size < safeSettings.queueEntries) {
                if (remainingUriStrings.isEmpty()) {
                    remainingUriStrings.addAll(allUriStrings)
                }

                val eligibleCategoryEntries = categoryEntries.filter { categoryEntry ->
                    categoryEntry.items.any { it.audioItem.uriString in remainingUriStrings }
                }
                if (eligibleCategoryEntries.isEmpty()) break

                val selectedCategory = pickWeighted(eligibleCategoryEntries) { it.weight }
                val selectedItem = pickWeighted(
                    selectedCategory.items.filter { it.audioItem.uriString in remainingUriStrings }
                ) { selectedCategory.itemWeightsByUri[it.audioItem.uriString] ?: 1.0 }
                remainingUriStrings.remove(selectedItem.audioItem.uriString)
                selectedItems.add(selectedItem.audioItem)
            }

            selectedItems
        }
    }

    fun shuffleQueue(items: List<AudioItem>): List<AudioItem> {
        return items.distinctBy { it.uriString }.shuffled(random)
    }

    private fun buildCategoryEntries(
        items: List<AudioItem>,
        settings: GraviPickerSettings,
        scopeFolderPath: String,
    ): List<GraviCategoryEntry> {
        val normalizedScopeFolderPath = scopeFolderPath.normalizedFolderPath()
        val pickerItems = items
            .distinctBy { it.uriString }
            .map {
                GraviPickerItem(
                    audioItem = it,
                    folderPath = it.folderPath.normalizedFolderPath().relativeToScopeFolder(
                        normalizedScopeFolderPath
                    ),
                )
            }
            .filterNot { isBlacklisted(it.folderPath, settings.blacklistFolders) }
        if (pickerItems.isEmpty()) return emptyList()

        val itemsByFolder = pickerItems.groupBy { it.folderPath }
        val folderPaths = buildFolderPaths(pickerItems)
        val descendantCountByFolder = buildDescendantCountByFolder(folderPaths, itemsByFolder)
        val categoryFolders = folderPaths.filter { folderPath ->
            val folderDepth = folderPath.folderParts().size
            val targetDepth = resolveTargetDepth(folderPath, settings)
            val hasDirectItems = itemsByFolder.containsKey(folderPath)
            val hasDescendantItems = descendantCountByFolder.getOrDefault(folderPath, 0) > 0

            when {
                hasDirectItems && folderDepth <= targetDepth -> true
                folderDepth == targetDepth && hasDescendantItems -> true
                else -> false
            }
        }

        val categoryFolderSet = categoryFolders.toSet()
        val categoryItemsByFolder = buildCategoryItemsByFolder(
            pickerItems,
            categoryFolderSet,
        )
        var categoryEntries = categoryFolders.mapNotNull { categoryFolder ->
            val categoryItems = categoryItemsByFolder.getOrDefault(categoryFolder, emptyList())
                .sortedBy { it.audioItem.folderPath + "/" + it.audioItem.title }

            if (categoryItems.isEmpty()) return@mapNotNull null

            val categoryWeight = if (settings.evenOddsMinFileCount != -1 &&
                categoryItems.size <= settings.evenOddsMinFileCount
            ) {
                1.0 / settings.lessLikelyDivisor
            } else {
                1.0
            }

            GraviCategoryEntry(
                folderPath = categoryFolder,
                items = categoryItems,
                itemWeightsByUri = buildItemWeights(categoryFolder, categoryItems, settings),
                weight = categoryWeight,
            )
        }

        if (settings.parentOdds) {
            categoryEntries = applyParentOdds(categoryEntries)
        }

        return categoryEntries
    }

    private fun buildFolderPaths(items: List<GraviPickerItem>): List<String> {
        return items
            .flatMap { pickerItem ->
                val parts = pickerItem.folderPath.folderParts()
                if (parts.isEmpty()) listOf("") else parts.indices.map { index ->
                    parts.take(index + 1).joinToString("/")
                }
            }
            .toSet()
            .sortedWith(compareBy<String> { it.folderParts().size }.thenBy { it })
    }

    private fun buildDescendantCountByFolder(
        folderPaths: List<String>,
        itemsByFolder: Map<String, List<GraviPickerItem>>,
    ): Map<String, Int> {
        val folderPathSet = folderPaths.toSet()
        val descendantCountByFolder = folderPaths.associateWith { 0 }.toMutableMap()
        itemsByFolder.forEach { (itemFolder, folderItems) ->
            foldersFromChildToParent(itemFolder, "").forEach { parentFolder ->
                if (parentFolder in folderPathSet) {
                    descendantCountByFolder[parentFolder] =
                        descendantCountByFolder.getOrDefault(parentFolder, 0) + folderItems.size
                }
            }
        }
        return descendantCountByFolder
    }

    private fun buildCategoryItemsByFolder(
        pickerItems: List<GraviPickerItem>,
        categoryFolderSet: Set<String>,
    ): Map<String, List<GraviPickerItem>> {
        val categoryItemsByFolder = mutableMapOf<String, MutableList<GraviPickerItem>>()
        pickerItems.forEach { pickerItem ->
            val categoryFolder = foldersFromChildToParent(pickerItem.folderPath, "")
                .firstOrNull { it in categoryFolderSet } ?: return@forEach
            categoryItemsByFolder.getOrPut(categoryFolder) { mutableListOf() } += pickerItem
        }
        return categoryItemsByFolder
    }

    private fun buildItemWeights(
        categoryFolder: String,
        categoryItems: List<GraviPickerItem>,
        settings: GraviPickerSettings,
    ): Map<String, Double> {
        val itemsByParentFolder = categoryItems.groupBy { it.folderPath }
        val descendantCountByFolder = mutableMapOf<String, Int>()
        categoryItems.forEach { pickerItem ->
            foldersFromChildToParent(pickerItem.folderPath, categoryFolder).forEach { folderPath ->
                descendantCountByFolder[folderPath] =
                    descendantCountByFolder.getOrDefault(folderPath, 0) + 1
            }
        }
        val childrenByFolder = mutableMapOf<String, Set<String>>()
        itemsByParentFolder.keys.forEach { folderPath ->
            val parts = folderPath.folderParts()
            val categoryParts = categoryFolder.folderParts()
            for (index in categoryParts.size until parts.size) {
                val parentFolder = parts.take(index).joinToString("/")
                val childFolder = parts.take(index + 1).joinToString("/")
                childrenByFolder[parentFolder] =
                    childrenByFolder.getOrDefault(parentFolder, emptySet()) + childFolder
            }
        }

        return categoryItems.associate { pickerItem ->
            var itemWeight = 1.0
            if (settings.childOdds) {
                val parts = pickerItem.folderPath.folderParts()
                val categoryParts = categoryFolder.folderParts()
                for (index in categoryParts.size until parts.size) {
                    val parentFolder = parts.take(index).joinToString("/")
                    val childFolders = childrenByFolder.getOrDefault(parentFolder, emptySet())
                    if (childFolders.isNotEmpty()) {
                        itemWeight *= 1.0 / childFolders.size
                    }
                }
            }

            if (settings.evenOddsMinFileCount != -1) {
                foldersFromChildToParent(
                    pickerItem.folderPath,
                    categoryFolder
                ).forEach { folderPath ->
                    val descendantCount = descendantCountByFolder.getOrDefault(folderPath, 0)
                    if (descendantCount <= settings.evenOddsMinFileCount) {
                        itemWeight *= 1.0 / settings.lessLikelyDivisor
                    }
                }
            }

            pickerItem.audioItem.uriString to itemWeight
        }
    }

    private fun applyParentOdds(categoryEntries: List<GraviCategoryEntry>): List<GraviCategoryEntry> {
        val childrenByFolder = mutableMapOf<String, Set<String>>()
        categoryEntries.forEach { categoryEntry ->
            val parts = categoryEntry.folderPath.folderParts()
            for (index in parts.indices) {
                val parentFolder = parts.take(index).joinToString("/")
                val childFolder = parts.take(index + 1).joinToString("/")
                childrenByFolder[parentFolder] =
                    childrenByFolder.getOrDefault(parentFolder, emptySet()) + childFolder
            }
        }

        return categoryEntries.map { categoryEntry ->
            var categoryMultiplier = 1.0
            val parts = categoryEntry.folderPath.folderParts()
            for (index in parts.indices) {
                val parentFolder = parts.take(index).joinToString("/")
                val childFolders = childrenByFolder.getOrDefault(parentFolder, emptySet())
                if (childFolders.isNotEmpty()) {
                    categoryMultiplier *= 1.0 / childFolders.size
                }
            }
            categoryEntry.copy(weight = categoryEntry.weight * categoryMultiplier)
        }
    }

    private fun resolveTargetDepth(folderPath: String, settings: GraviPickerSettings): Int {
        val normalizedFolderPath = folderPath.normalizedFolderPath()
        var targetDepth = settings.depth
        settings.edgeCaseFolderDepths.forEach { (edgeCaseFolder, depthIncrement) ->
            if (normalizedFolderPath == edgeCaseFolder || normalizedFolderPath.startsWith("$edgeCaseFolder/")) {
                targetDepth = maxOf(targetDepth, settings.depth + depthIncrement)
            }
        }
        return targetDepth
    }

    private fun isBlacklisted(folderPath: String, blacklistFolders: Set<String>): Boolean {
        return foldersFromChildToParent(folderPath, "").any { it in blacklistFolders }
    }

    private fun foldersFromChildToParent(
        folderPath: String,
        parentFolderPath: String
    ): List<String> {
        val folderParts = folderPath.folderParts()
        val parentParts = parentFolderPath.folderParts()
        return (folderParts.size downTo parentParts.size).map { index ->
            folderParts.take(index).joinToString("/")
        }
    }

    private fun String.relativeToScopeFolder(scopeFolderPath: String): String {
        val normalizedPath = normalizedFolderPath()
        val normalizedScopeFolderPath = scopeFolderPath.normalizedFolderPath()
        if (normalizedScopeFolderPath.isBlank()) return normalizedPath
        if (normalizedPath == normalizedScopeFolderPath) return ""
        return normalizedPath.removePrefix("$normalizedScopeFolderPath/")
    }

    private fun <Item> pickWeighted(items: List<Item>, weight: (Item) -> Double): Item {
        val weights = items.map { weight(it).coerceAtLeast(0.0) }
        val totalWeight = weights.sum()
        if (totalWeight <= 0.0) return items[random.nextInt(items.size)]

        var selectedWeight = random.nextDouble(totalWeight)
        items.forEachIndexed { index, item ->
            selectedWeight -= weights[index]
            if (selectedWeight <= 0.0) return item
        }

        return items.last()
    }
}