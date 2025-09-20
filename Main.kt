package org.example.data_transformation

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.math.BigDecimal

private const val DELIMITER = "___"

fun main() {
    val files = loadJsonFiles()
    files.forEach(::verifyFile)
    println("OK: verified ${files.size} files")

    val entries = files.asSequence().flatMap(::entriesFromFile)
    val grouped =
        entries
            .map { it.copy(segments = it.segments.filterNot(String::isNumeric)) }
            .groupBy { it.segments }

    val fieldDefinitions =
        grouped
            .filterKeys { it.isNotEmpty() }
            .map { (segments, groupEntries) ->
                FieldDefinitionDraft(
                    name = segments.joinToString(DELIMITER),
                    label = mapOf("en" to segments.joinToString(".")),
                    required = false,
                    type = toCommercetoolsType(groupEntries),
                )
            }.sortedBy { it.name }

    val uniqueNormalizedPaths = grouped.filterKeys { it.isNotEmpty() }.size

    require(fieldDefinitions.size == uniqueNormalizedPaths) {
        "fieldDefinitions (${fieldDefinitions.size}) != uniqueNormalizedPaths ($uniqueNormalizedPaths)"
    }

    val fieldNames = fieldDefinitions.map { it.name }.toSet()
    entries.forEach { e ->
        val normalized = e.segments.filterNot(String::isNumeric).joinToString(DELIMITER)
        require(normalized in fieldNames) { "Entry path $normalized missing in fieldDefinitions" }
    }

    val typeDraft =
        TypeDraft(
            key = "Brand",
            name = mapOf("en" to "Brand"),
            description = mapOf("en" to "Fields definitions for brand (top-level category)"),
            resourceTypeIds = listOf("category"),
            fieldDefinitions = fieldDefinitions,
        )

    val report = createVerificationReport(files, entries, grouped)

    mapper.writerWithDefaultPrettyPrinter().writeValue(File("verification.json"), report)
    mapper.writerWithDefaultPrettyPrinter().writeValue(File("brand-type.json"), typeDraft)
}

data class VerificationReport(
    val filesProcessed: Int,
    val totalLeavesStreaming: Int,
    val totalFlattenedEntries: Int,
    val uniqueNormalizedPaths: Int,
    val pathsMarkedAsSet: Int,
    val pathsWithMixedKinds: Int,
)

data class Entry(
    val segments: List<String>,
    val value: JVal,
    val cameFromArray: Boolean,
)

sealed interface JVal
data class JString(val value: String) : JVal
data class JNumber(val value: BigDecimal) : JVal
data class JBool(val value: Boolean) : JVal
data object JNull : JVal

sealed interface CtFieldType
data class CtSimple(val name: String) : CtFieldType
data class CtSet(val name: String = "Set", val elementType: CtSimple) : CtFieldType

data class FieldDefinitionDraft(
    val name: String,
    val label: Map<String, String>,
    val required: Boolean,
    val type: CtFieldType,
)

data class TypeDraft(
    val key: String,
    val name: Map<String, String>,
    val description: Map<String, String>,
    val resourceTypeIds: List<String>,
    val fieldDefinitions: List<FieldDefinitionDraft>,
)

private val mapper = ObjectMapper()
private val jsonFactory = JsonFactory()

private fun loadJsonFiles(): List<File> =
    requireNotNull(::main.javaClass.classLoader.getResource("response-body-config-appinit/web")) {
        "Resource directory not found"
    }.toURI()
        .let(::File)
        .walkTopDown()
        .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
        .toList()

fun entriesFromFile(jsonFile: File): Sequence<Entry> = flattenJson(mapper.readTree(jsonFile))

fun flattenJson(
    node: JsonNode,
    breadcrumb: List<String> = emptyList(),
    fromArray: Boolean = false,
): Sequence<Entry> =
    sequence {
        when {
            node.isObject -> {
                for ((key, child) in node.properties()) {
                    yieldAll(flattenJson(child, breadcrumb + key, fromArray))
                }
            }
            node.isArray -> {
                for (child in node) {
                    yieldAll(flattenJson(child, breadcrumb, fromArray = true))
                }
            }
            else -> yield(Entry(breadcrumb, node.toJVal(), fromArray))
        }
    }

private fun JsonNode.toJVal(): JVal =
    when {
        isTextual -> JString(asText())
        isNumber -> JNumber(decimalValue())
        isBoolean -> JBool(asBoolean())
        isNull -> JNull
        else -> error("Unexpected JSON node type")
    }

private fun createVerificationReport(
    files: List<File>,
    entries: Sequence<Entry>,
    normalizedGroups: Map<List<String>, List<Entry>>,
): VerificationReport {
    val totalLeaves = files.sumOf(::countLeavesStreaming)
    val flatCount = entries.count()

    require(flatCount == totalLeaves) {
        "Flattened leaf count ($flatCount) != streaming leaf count ($totalLeaves)"
    }

    val groups = normalizedGroups.filterKeys { it.isNotEmpty() }
    val mixedKindPaths =
        groups.count { (_, entries) ->
            entries
                .map { it.value.kind() }
                .filterNot { it == "Null" }
                .toSet()
                .size > 1
        }
    val setPaths = groups.count { (_, entries) -> entries.any { it.cameFromArray } }

    return VerificationReport(
        filesProcessed = files.size,
        totalLeavesStreaming = totalLeaves,
        totalFlattenedEntries = flatCount,
        uniqueNormalizedPaths = groups.size,
        pathsMarkedAsSet = setPaths,
        pathsWithMixedKinds = mixedKindPaths,
    )
}

private fun verifyFile(file: File) {
    val flatCount = entriesFromFile(file).count()
    val leafCount = countLeavesStreaming(file)
    require(flatCount == leafCount) {
        "Mismatch in ${file.name}: flattened=$flatCount, jsonLeaves=$leafCount"
    }
}

private fun countLeavesStreaming(file: File): Int =
    jsonFactory.createParser(file).use { parser ->
        generateSequence { parser.nextToken() }.count { it.isScalarValue }
    }

private fun String.isNumeric(): Boolean = isNotEmpty() && all(Char::isDigit)

private fun JVal.kind(): String =
    when (this) {
        is JString -> "String"
        is JNumber -> "Number"
        is JBool -> "Boolean"
        is JNull -> "Null"
    }

private fun toCommercetoolsType(entries: List<Entry>): CtFieldType {
    val distinctKinds =
        entries
            .map { it.value.kind() }
            .filterNot { it == "Null" }
            .toSet()

    val base =
        when {
            distinctKinds.size == 1 ->
                when (distinctKinds.first()) {
                    "String" -> CtSimple("String")
                    "Number" -> CtSimple("Number")
                    "Boolean" -> CtSimple("Boolean")
                    else -> CtSimple("String")
                }
            distinctKinds.isEmpty() -> CtSimple("String")
            else -> CtSimple("String") // Mixed types default to String
        }

    val isSet = entries.any { it.cameFromArray }
    return if (isSet) CtSet(elementType = base) else base
}
