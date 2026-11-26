package com.quickstart.md3.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.ConcurrentHashMap

data class IconPackInfo(
    val packageName: String,
    val label: String
)

/**
 * Lightweight, lazy-cached ADW/Nova/GO/Apex icon pack loader.
 * Does zero work when no third-party icon pack is selected.
 */
object IconPackManager {

    private val themeActions = listOf(
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "com.gau.go.launcherex.theme",
        "com.anddoes.launcher.THEME"
    )

    private val componentMapCache = ConcurrentHashMap<String, Map<String, String>>()
    private val packageMapCache = ConcurrentHashMap<String, Map<String, String>>()
    private val resourceCache = ConcurrentHashMap<String, Resources>()

    fun getInstalledIconPacks(context: Context): List<IconPackInfo> {
        val pm = context.packageManager
        val seen = LinkedHashMap<String, IconPackInfo>()
        for (action in themeActions) {
            val intent = Intent(action)
            val resolved = runCatching {
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }.getOrDefault(emptyList())
            for (ri in resolved) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (!seen.containsKey(pkg)) {
                    val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
                    seen[pkg] = IconPackInfo(packageName = pkg, label = label.ifBlank { pkg })
                }
            }
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    fun loadIconDrawable(
        context: Context,
        iconPackPkg: String,
        targetPkg: String,
        targetActivity: String
    ): Drawable? {
        if (iconPackPkg.isBlank() || targetPkg.isBlank()) return null
        val packRes = getPackResources(context, iconPackPkg) ?: return null
        ensureParsed(packRes, iconPackPkg)

        val compMap = componentMapCache[iconPackPkg].orEmpty()
        val pkgMap = packageMapCache[iconPackPkg].orEmpty()

        val drawableName = buildList {
            if (targetActivity.isNotBlank()) {
                val fullAct = if (targetActivity.startsWith(".")) "$targetPkg$targetActivity" else targetActivity
                add(compMap["$targetPkg/$fullAct"])
                add(compMap["$targetPkg/$targetActivity"])
            }
            add(pkgMap[targetPkg])
        }.firstOrNull { !it.isNullOrBlank() } ?: return null

        val resId = packRes.getIdentifier(drawableName, "drawable", iconPackPkg)
        if (resId == 0) return null
        return runCatching { packRes.getDrawable(resId, null) }.getOrNull()
    }

    fun clearCache() {
        componentMapCache.clear()
        packageMapCache.clear()
        resourceCache.clear()
    }

    private fun getPackResources(context: Context, iconPackPkg: String): Resources? {
        resourceCache[iconPackPkg]?.let { return it }
        return runCatching {
            context.packageManager.getResourcesForApplication(iconPackPkg).also {
                resourceCache[iconPackPkg] = it
            }
        }.getOrNull()
    }

    private fun ensureParsed(packRes: Resources, iconPackPkg: String) {
        if (componentMapCache.containsKey(iconPackPkg)) return
        val compMap = HashMap<String, String>(512)
        val pkgMap = HashMap<String, String>(512)

        runCatching {
            val xmlResId = packRes.getIdentifier("appfilter", "xml", iconPackPkg)
            if (xmlResId != 0) {
                packRes.getXml(xmlResId).use { parser ->
                    parseAppFilter(parser, compMap, pkgMap)
                }
            } else {
                packRes.assets.open("appfilter.xml").use { input ->
                    val parser = Xml.newPullParser()
                    parser.setInput(input, "UTF-8")
                    parseAppFilter(parser, compMap, pkgMap)
                }
            }
        }

        componentMapCache[iconPackPkg] = compMap
        packageMapCache[iconPackPkg] = pkgMap
    }

    private fun parseAppFilter(
        parser: XmlPullParser,
        compMap: MutableMap<String, String>,
        pkgMap: MutableMap<String, String>
    ) {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                val componentRaw = parser.getAttributeValue(null, "component")
                val drawable = parser.getAttributeValue(null, "drawable")
                if (!componentRaw.isNullOrBlank() && !drawable.isNullOrBlank()) {
                    val inner = componentRaw
                        .removePrefix("ComponentInfo{")
                        .removeSuffix("}")
                        .trim()
                    if (inner.isNotEmpty()) {
                        compMap.putIfAbsent(inner, drawable)
                        val slash = inner.indexOf('/')
                        if (slash > 0) {
                            val pkg = inner.substring(0, slash)
                            pkgMap.putIfAbsent(pkg, drawable)
                        }
                    }
                }
            }
            event = parser.next()
        }
    }
}
