package momoi.plugin.apkmixin.utils

import com.wind.meditor.xml.ResourceIdXmlReader
import org.w3c.dom.Element
import pxb.android.axml.Axml
import pxb.android.axml.AxmlReader
import pxb.android.axml.AxmlWriter
import pxb.android.axml.NodeVisitor
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Generic binary-AndroidManifest (AXML) merger. Reads a plain-text AndroidManifest XML "patch" and
 * merges it into the target APK's compiled `AndroidManifest.xml`, then re-serializes to binary.
 *
 * Built on the `pxb.android.axml` tree model and the `public.xml` attr→resourceId table that both
 * ship inside ManifestEditor-2.0.jar (already on the plugin classpath). ManifestEditor's own CLI
 * can only add permissions / meta-data / attributes — it can't insert nested nodes — so we operate
 * on the mutable [Axml] tree directly. Nothing here is app-specific: what gets merged is entirely
 * defined by the patch XML the user authors (intent-filters, components, permissions, …).
 *
 * Merge semantics ("new replaces old"):
 *  - Attributes on a matched node: the patch value overrides the existing one (added if absent).
 *  - Child elements: matched against existing children by element name + `android:name` (or, for the
 *    singleton containers `manifest`/`application`, by element name alone). A match is merged
 *    recursively; otherwise the patch subtree is appended. Appends are de-duplicated by structural
 *    equality so repeated builds stay idempotent.
 */
object ManifestMerger {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private val SINGLETON_TAGS = setOf("manifest", "application")

    /**
     * Merge [patchXml] into `AndroidManifest.xml` read from [apk], writing the patched binary
     * manifest to [out]. Returns true if a merge was performed (the patched manifest should be
     * injected), false if there was nothing to do.
     */
    fun merge(apk: File, patchXml: File, out: File): Boolean {
        if (!patchXml.isFile) return false

        val manifestBytes = ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: return false
            zip.getInputStream(entry).use { it.readBytes() }
        }

        val axml = Axml()
        AxmlReader(manifestBytes).accept(axml)

        val patchRoot = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(patchXml)
            .documentElement

        val targetRoot = axml.firsts.firstOrNull { it.name == patchRoot.localName }
            ?: return false

        mergeNode(targetRoot, patchRoot)

        val writer = AxmlWriter()
        axml.accept(writer)
        out.writeBytes(writer.toByteArray())
        return true
    }

    private fun mergeNode(target: Axml.Node, patch: Element) {
        // Attributes: patch overrides existing.
        val attrs = patch.attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            val ns = a.namespaceURI
            // Skip xmlns:* namespace declarations.
            if (ns == "http://www.w3.org/2000/xmlns/" || a.nodeName == "xmlns") continue
            setAttr(target, ns, a.localName ?: a.nodeName, a.nodeValue)
        }

        // Child elements.
        val children = patch.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            val tag = child.localName ?: child.tagName
            val key = androidName(child)
            val match = target.children.firstOrNull { existing ->
                existing.name == tag &&
                    if (key != null) androidName(existing) == key else tag in SINGLETON_TAGS
            }
            if (match != null) {
                mergeNode(match, child)
            } else {
                val built = buildNode(child)
                if (target.children.none { sameNode(it, built) }) {
                    target.children.add(built)
                }
            }
        }
    }

    /** Convert a patch DOM element (and its subtree) into a detached [Axml.Node]. */
    private fun buildNode(el: Element): Axml.Node {
        val node = Axml.Node()
        node.name = el.localName ?: el.tagName
        node.ns = null
        val attrs = el.attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            val ns = a.namespaceURI
            if (ns == "http://www.w3.org/2000/xmlns/" || a.nodeName == "xmlns") continue
            setAttr(node, ns, a.localName ?: a.nodeName, a.nodeValue)
        }
        val children = el.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            node.children.add(buildNode(child))
        }
        return node
    }

    /** Add or override an attribute on [node], resolving android attrs via the public.xml table. */
    private fun setAttr(node: Axml.Node, ns: String?, name: String, rawValue: String) {
        val isAndroid = ns == ANDROID_NS
        val attrNs = if (isAndroid) ANDROID_NS else null
        // meditor's public.xml table is missing some newer android attrs (e.g. foregroundServiceType);
        // fall back to a small hardcoded map so they resolve instead of being written id-less (which
        // the platform silently ignores).
        val resourceId = if (isAndroid) {
            ResourceIdXmlReader.parseIdFromXml(name).takeIf { it != -1 }
                ?: ANDROID_ATTR_FALLBACK[name] ?: -1
        } else -1
        val (type, value) = typeAndValue(rawValue)
        node.attrs.removeAll { it.name == name && it.ns == attrNs }
        node.attr(attrNs, name, resourceId, type, value)
    }

    /** Infer the AXML value type from a textual attribute value. */
    private fun typeAndValue(raw: String): Pair<Int, Any> = when {
        raw.equals("true", true) -> NodeVisitor.TYPE_INT_BOOLEAN to true
        raw.equals("false", true) -> NodeVisitor.TYPE_INT_BOOLEAN to false
        raw.startsWith("0x") && raw.drop(2).all { it.isHex() } ->
            NodeVisitor.TYPE_INT_HEX to raw.drop(2).toLong(16).toInt()
        raw.toIntOrNull() != null -> NodeVisitor.TYPE_FIRST_INT to raw.toInt()
        else -> NodeVisitor.TYPE_STRING to raw
    }

    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** android attr resource ids missing from meditor's public.xml table (name → platform R.attr id). */
    private val ANDROID_ATTR_FALLBACK = mapOf(
        "foregroundServiceType" to 0x01010599, // android.R.attr.foregroundServiceType (API 28)
    )

    private fun androidName(el: Element): String? =
        el.getAttributeNS(ANDROID_NS, "name").takeIf { it.isNotEmpty() }

    private fun androidName(node: Axml.Node): String? =
        node.attrs.firstOrNull { it.ns == ANDROID_NS && it.name == "name" }?.value as? String

    /** Order-sensitive structural equality, used to de-duplicate appended subtrees. */
    private fun sameNode(a: Axml.Node, b: Axml.Node): Boolean {
        if (a.name != b.name) return false
        if (a.attrs.size != b.attrs.size) return false
        val aAttrs = a.attrs.map { "${it.ns}|${it.name}|${it.value}" }.toSet()
        val bAttrs = b.attrs.map { "${it.ns}|${it.name}|${it.value}" }.toSet()
        if (aAttrs != bAttrs) return false
        if (a.children.size != b.children.size) return false
        return a.children.indices.all { sameNode(a.children[it], b.children[it]) }
    }
}
