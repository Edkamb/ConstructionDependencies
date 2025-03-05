package org.smolang.depender

import antlr.Python3Parser
import antlr.Python3ParserBaseListener
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.query.algebra.FunctionCall
import org.eclipse.rdf4j.query.algebra.Service
import org.eclipse.rdf4j.query.algebra.StatementPattern
import org.eclipse.rdf4j.query.algebra.ValueConstant
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor
import java.io.BufferedReader
import java.io.FileReader
import java.io.IOException
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.repository.Repository
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.eclipse.rdf4j.rio.RDFFormat
import java.io.File

/** Needed by Clikt for context propagation between subcommands s**/
data class GlobalConfig(var verbose: Boolean = false)

/** Extracts all String literals from a Python AST **/
class StringLiteralListener : Python3ParserBaseListener() {
    val stringLiterals = mutableListOf<String>()

    override fun enterAtom(ctx: Python3Parser.AtomContext?) {
        if(ctx!!.STRING() != null && ctx.STRING(0) != null) {
            val text = ctx.STRING(0).text
            val withoutQuotes = text.substring(1, text.length - 1)
            stringLiterals.add(withoutQuotes)
        }
    }
}

/**
 * Visitor implementation that extracts URIs from tuple expressions in SPARQL queries
 */
class UriExtractorVisitor(
    private val uris: MutableSet<String>
) : AbstractQueryModelVisitor<RuntimeException>() {

    override fun meet(node: StatementPattern) {
        super.meet(node)

        // Extract URIs from subject
        extractUriFromValue(node.subjectVar.value)

        // Extract URI from predicate
        extractUriFromValue(node.predicateVar.value)

        // Extract URI from object
        extractUriFromValue(node.objectVar.value)

        // If there's a context (named graph), extract its URI too
        node.contextVar?.value?.let { extractUriFromValue(it) }
    }

    override fun meet(node: Service) {
        // Extract service endpoint URI
        if (node.serviceRef.hasValue() && node.serviceRef.value is IRI) {
            uris.add(node.serviceRef.value.toString())
        }
        super.meet(node)
    }


    override fun meet(node: FunctionCall) {
        // Extract function URI
        uris.add(node.uri)
        super.meet(node)
    }

    override fun meet(node: ValueConstant) {
        // Extract URI from constant value if it's an IRI
        extractUriFromValue(node.value)
        super.meet(node)
    }

    private fun extractUriFromValue(value: Value?) {
        if (value is IRI) {
            uris.add(value.toString())
        }
    }
}

var standardNamespacePrefixes = mutableSetOf(
    "http://www.w3.org/1999/02/22-rdf-syntax-ns#",      // RDF
    "http://www.w3.org/2000/01/rdf-schema#",            // RDFS
    "http://www.w3.org/2002/07/owl#",                   // OWL
    "http://www.w3.org/2001/XMLSchema#",                // XSD
    "http://www.w3.org/ns/shacl#",                      // SHACL
    "http://www.w3.org/XML/1998/namespace",             // XML Namespace
    "http://purl.org/dc/elements/1.1/",                 // Dublin Core
    "http://purl.org/dc/terms/",                        // Dublin Core Terms
    "http://semweb.mmlab.be/ns/rml#",                   // RML,
    "http://www.w3.org/ns/r2rml#",                      // also RML
    "http://mapping.example.com/",                      // hack
    "https://astrea.linkeddata.es/shapes#",             // also a hack
    "http://www.w3.org/2004/02/skos/core#",             // SKOS
)

fun filterOutStandardNamespaces(uris: Set<String>): Set<String> {
    // Define standard namespace prefixes to filter out


    // Filter out URIs that start with any of the standard namespace prefixes
    return uris.filterNot { uri ->
        standardNamespacePrefixes.any { prefix ->
            uri.startsWith(prefix)
        }
    }.toSet()
}

fun extractCsvColumns(filePath: String): Set<String> {
    try {
        BufferedReader(FileReader(filePath)).use { reader ->
            // Read the first line (header)
            val headerLine = reader.readLine()
                ?: throw IllegalArgumentException("CSV file is empty")

            // Split the header line, handling potential variations in CSV formats
            return headerLine
                .split(',')  // Default CSV delimiter
                .map { it.trim().replace("\"", "") }  // Remove quotes and whitespace
                .toSet()
        }
    } catch (e: IOException) {
        throw IllegalArgumentException("Error reading CSV file: ${e.message}")
    }
}







fun findUrisWithSuffixes(
    ontologyFilePath: String,
    suffixes: List<String>
): Set<String> {
    // Create a new in-memory RDF repository
    val repository: Repository = SailRepository(MemoryStore())

    try {
        // Open a connection to the repository
        repository.connection.use { conn ->
            // Load the ontology file
            conn.add(File(ontologyFilePath), "", RDFFormat.TRIG)

            // Prepare the result set
            val matchingUris = mutableSetOf<String>()

            // Execute a SPARQL-like query to find all subjects and objects
            val statements = conn.getStatements(null, null, null)

            // Iterate through all statements
            statements.use {
                while (it.hasNext()) {
                    val statement = it.next()

                    // Check subject
                    statement.subject?.let { subject ->
                        checkAndAddUri(subject, suffixes, matchingUris)
                    }

                    // Check object if it's a resource
                    statement.`object`?.let { obj ->
                        checkAndAddUri(obj, suffixes, matchingUris)
                    }
                }
            }

            return matchingUris
        }
    } finally {
        // Ensure repository is shut down
        repository.shutDown()
    }
}

// Helper function to check and add URIs with matching suffixes
private fun checkAndAddUri(
    value: Value,
    suffixes: List<String>,
    matchingUris: MutableSet<String>
) {
    // Check if the value is a resource (has a string representation)
    if (value is Resource) {
        val uriString = value.stringValue()

        // Check if any suffix matches the end of the URI
        if (suffixes.any { suffix ->
                uriString.endsWith(suffix)
            }) {
            matchingUris.add(uriString)
        }
    }
}
