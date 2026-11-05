package app.sososafe.core

import app.noir.core.Domains
import app.noir.core.DomainClass
import java.net.IDN
import java.net.URI
import java.util.Locale

data class DomainRule(val domain: String, val provider: String, val category: String, val tracker: Boolean)
object DomainRules {
    val rules: List<DomainRule> by lazy {
        checkNotNull(javaClass.getResourceAsStream("/domain-rules.tsv")).bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
                val p = line.split('\t'); require(p.size == 4); DomainRule(p[0], p[1], p[2], p[3].toBooleanStrict())
            }.sortedByDescending { it.domain.length }.toList()
        }
    }
    fun normalize(value: String): String? = runCatching {
        val host = if(value.contains("://")) URI(value).host else value.trim().trimEnd('.')
        if(host.isNullOrEmpty() || ':' in host) return null
        val ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        ascii.takeIf { it.length <= 253 && it.contains('.') && it.split('.').all { l -> l.length in 1..63 } }
    }.getOrNull()
    fun classify(value: String): DomainRule {
        val host = normalize(value) ?: return DomainRule(value, "Unknown", "Unknown", false)
        return rules.firstOrNull { host == it.domain || host.endsWith(".${it.domain}") }
            ?: DomainRule(host, "Unknown", if(Domains.classify(host) == DomainClass.TRACKER) "Telemetry (heuristic)" else "Unknown", Domains.classify(host) == DomainClass.TRACKER)
    }
    private val urls = Regex("https?://[^\\s\"'<>\\x00-\\x20]{4,2048}", RegexOption.IGNORE_CASE)
    private val bare = Regex("(?<![A-Za-z0-9_])(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,24}(?![A-Za-z0-9_])")
    fun extract(text: String, source: String): List<Endpoint> {
        val found = linkedMapOf<String, Endpoint>()
        val values = urls.findAll(text).map { it.value }.toList() + if(text.length < 2048 && '/' !in text && ';' !in text && !text.startsWith("L")) bare.findAll(text).map { it.value }.toList() else emptyList()
        for(value in values) {
            val host = normalize(value) ?: continue
            if(Domains.isIgnored(host) || host.endsWith(".java") || host.endsWith(".kt") || host.startsWith("android.") || host.startsWith("com.android.")) continue
            val rule = classify(host)
            found[value] = Endpoint(host, value, rule.provider, rule.category, rule.tracker, Evidence(source, "DEX string reference: $value", EvidenceLevel.STATIC_REFERENCE))
        }
        return found.values.toList()
    }
}
