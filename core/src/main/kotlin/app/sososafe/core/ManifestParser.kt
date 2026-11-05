package app.sososafe.core

import app.noir.core.apk.Axml
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

object ManifestParser {
    fun nodes(bytes: ByteArray): List<Axml.Node> {
        require(bytes.size in 8..(8 * 1024 * 1024)) { "Manifest size outside supported bounds" }
        if(bytes.take(100).toByteArray().toString(Charsets.UTF_8).trimStart().startsWith('<')) {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val root = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
            val list = mutableListOf<Axml.Node>()
            fun walk(e: Element, depth: Int) {
                val attrs = (0 until e.attributes.length).associate { val a=e.attributes.item(it); (a.localName ?: a.nodeName) to a.nodeValue }
                list += Axml.Node(e.tagName, attrs, depth)
                for(i in 0 until e.childNodes.length) (e.childNodes.item(i) as? Element)?.let { walk(it, depth+1) }
            }
            walk(root, 0); return list
        }
        return Axml.parse(bytes).also { require(it.any { n -> n.name == "manifest" }) { "Invalid binary Manifest" } }
    }
    fun parse(bytes: ByteArray): ManifestResult = fromNodes(nodes(bytes))
    fun fromNodes(nodes: List<Axml.Node>): ManifestResult {
        val app=nodes.firstOrNull { it.name=="application" && it.depth==1 }
        val target=nodes.firstOrNull { it.name=="uses-sdk" }?.attrs?.get("targetSdkVersion")?.toIntOrNull()
        val appPerm=app?.attrs?.get("permission")?.takeIf { it.isNotBlank() }
        val components=nodes.mapIndexedNotNull { index,n ->
            if(n.depth!=2 || n.name !in setOf("activity","activity-alias","service","receiver","provider")) return@mapIndexedNotNull null
            val hasFilter=nodes.drop(index+1).takeWhile { it.depth>n.depth }.any { it.name=="intent-filter" }
            val defaultExport=if(n.name=="provider") (target ?: 1)<17 else hasFilter
            val permission=n.attrs["permission"]?.takeIf { it.isNotBlank() } ?: appPerm
            val children=nodes.drop(index+1).takeWhile{it.depth>n.depth}
            Component(n.name,n.attrs["name"].orEmpty(),n.attrs["exported"]?.toBooleanStrictOrNull() ?: defaultExport,permission,n.attrs["authorities"],n.attrs["readPermission"] ?: permission,n.attrs["writePermission"] ?: permission,children.any{it.name=="path-permission"})
        }
        return ManifestResult(
            nodes.firstOrNull { it.name=="manifest" }?.attrs?.get("package").orEmpty(),
            nodes.filter { it.name.startsWith("uses-permission") }.mapNotNull { it.attrs["name"] }.distinct(),target,
            app?.attrs?.get("allowBackup")?.toBooleanStrictOrNull() ?: true,
            app?.attrs?.get("debuggable")?.toBooleanStrictOrNull() ?: false,
            app?.attrs?.get("usesCleartextTraffic")?.toBooleanStrictOrNull() ?: target?.let { it<28 },
            app?.attrs?.get("networkSecurityConfig"),components
        )
    }
    fun findings(m: ManifestResult, source: String): List<Finding> = buildList {
        fun addFact(id:String,title:String,points:Int,detail:String,advice:String) = add(Finding(id,title,if(points>=10) Severity.HIGH else Severity.MEDIUM,points,Evidence(source,detail,EvidenceLevel.STATIC_REFERENCE),advice))
        if(m.debuggable==true) addFact("manifest:debuggable","Debuggable application",15,"android:debuggable=true","Prefer the publisher's production build.")
        if(m.allowBackup==true) addFact("manifest:backup","Backup is permitted",2,"allowBackup=true (explicit or platform default). Data extraction rules and OEM behavior can narrow actual backup.","Review device backup and the app's data extraction rules.")
        if(m.cleartext==true) addFact("manifest:cleartext","Cleartext traffic permitted",7,"usesCleartextTraffic=true/effective target default; network security config may override this.","This is permission to use cleartext, not proof that it is used.")
        if((m.targetSdk ?: 99)<28) addFact("manifest:target","Legacy target SDK",5,"targetSdk=${m.targetSdk}","Look for a maintained app version.")
        m.components.filter { it.exported && it.permission.isNullOrBlank() && (it.kind!="provider" || (it.readPermission.isNullOrBlank() || it.writePermission.isNullOrBlank())) }.forEach {
            addFact("manifest:${it.kind}:${it.name}","Exported ${it.kind} without component permission",if(it.kind=="activity" || it.kind=="activity-alias") 1 else 4,"${it.name}; exported=true; no inherited application permission. Providers may have read/write or path permissions, and code may enforce access.","Review the public interface; exported components can be intentional.")
        }
    }
}
