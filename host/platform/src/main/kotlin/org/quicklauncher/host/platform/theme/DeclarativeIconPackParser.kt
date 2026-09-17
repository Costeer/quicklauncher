package org.quicklauncher.host.platform.theme

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import org.xmlpull.v1.XmlPullParser
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

data class IconComponentKey(val packageName: String, val activityName: String)

object DeclarativeIconPackParser {
    const val MAX_MAPPING_CHARS: Int = 2 * 1024 * 1024
    const val MAX_MAPPINGS: Int = 20_000
    const val MAX_COMPONENT_CHARS: Int = 512

    fun parse(xml: String): Map<IconComponentKey, String>? {
        if (xml.length !in 1..MAX_MAPPING_CHARS) return null
        return runCatching {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            }
            val mappings = linkedMapOf<IconComponentKey, String>()
            var events = 0
            factory.newSAXParser().parse(
                InputSource(StringReader(xml)),
                object : DefaultHandler() {
                    override fun startElement(
                        uri: String?,
                        localName: String?,
                        qName: String,
                        attributes: Attributes,
                    ) {
                        events += 1
                        require(events <= MAX_MAPPINGS * 4)
                        if (qName != "item") return
                        val component = attributes.getValue("component") ?: return
                        val drawable = attributes.getValue("drawable") ?: return
                        val key = parseComponent(component) ?: return
                        if (!RESOURCE_NAME.matches(drawable)) return
                        if (key !in mappings) {
                            require(mappings.size < MAX_MAPPINGS)
                            mappings[key] = drawable
                        }
                    }
                },
            )
            mappings.toMap()
        }.getOrNull()
    }

    fun parse(parser: XmlPullParser): Map<IconComponentKey, String>? = runCatching {
        val mappings = linkedMapOf<IconComponentKey, String>()
        var events = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            events += 1
            require(events <= MAX_MAPPINGS * 4)
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                addMapping(
                    mappings,
                    parser.getAttributeValue(null, "component"),
                    parser.getAttributeValue(null, "drawable"),
                )
            }
            event = parser.next()
        }
        mappings.toMap()
    }.getOrNull()

    private fun addMapping(
        mappings: MutableMap<IconComponentKey, String>,
        component: String?,
        drawable: String?,
    ) {
        val key = component?.let(::parseComponent) ?: return
        if (drawable == null || !RESOURCE_NAME.matches(drawable)) return
        if (key !in mappings) {
            require(mappings.size < MAX_MAPPINGS)
            mappings[key] = drawable
        }
    }

    private fun parseComponent(value: String): IconComponentKey? {
        if (value.length !in 1..MAX_COMPONENT_CHARS) return null
        val unwrapped = if (value.startsWith("ComponentInfo{") && value.endsWith("}")) {
            value.substring(14, value.length - 1)
        } else {
            value
        }
        val packageName = unwrapped.substringBefore('/', "")
        var activityName = unwrapped.substringAfter('/', "")
        if (!PACKAGE_NAME.matches(packageName) || activityName.isEmpty()) return null
        if (activityName.startsWith('.')) activityName = packageName + activityName
        if (!CLASS_NAME.matches(activityName)) return null
        return IconComponentKey(packageName, activityName)
    }

    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val CLASS_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    private val RESOURCE_NAME = Regex("[a-z][a-z0-9_]{0,127}")
}
