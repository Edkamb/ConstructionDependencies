package org.smolang.depender

import antlr.Python3Lexer
import antlr.Python3Parser
import antlr.Python3Parser.Literal_exprContext
import antlr.Python3ParserBaseListener
import be.ugent.rml.store.RDF4JStore
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.sun.tools.javac.code.Kinds
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.query.algebra.FunctionCall
import org.eclipse.rdf4j.query.algebra.Service
import org.eclipse.rdf4j.query.algebra.StatementPattern
import org.eclipse.rdf4j.query.algebra.ValueConstant
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor
import org.eclipse.rdf4j.query.parser.ParsedQuery
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.sail.nativerdf.NativeStore
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

enum class Kind { SOURCE, MAPPING, SHAPE, QUERY, PYTHON}

data class Asset(val name : String?,
                 val origin : String,
                 val kind : Kind,
                 val dependsOn : MutableSet<Asset> = mutableSetOf(),
                 val URIs : Set<String> = setOf()
){
    override fun toString(): String {
        return "${kind}: $origin DEPS(${dependsOn.joinToString (",") { it.origin + ":" + it.kind  } })"
    }
}
data class GlobalConfig(var verbose: Boolean = false)

val assets = mutableSetOf<Asset>()

class Sources: CliktCommand() {
    private val files by argument().file().multiple()
    private val config by requireObject<GlobalConfig>()
    override fun run() {
        if(config.verbose) echo("Sources $files")
        files.forEach{ assets.add(Asset(it.path, it.name, Kind.SOURCE)) }
    }
}

/** this ignores parentTriplesMap **/
class Mappings: CliktCommand() {
    private val files by argument().file().multiple()
    private val config by requireObject<GlobalConfig>()
    override fun run() {
        if(config.verbose) echo("Mapping $files")
        files.forEach{
            val res = getAllURIMap(it.path)
            res.total.values.forEach{it2 -> assets.add(Asset(it.path, it2.name, Kind.MAPPING, mutableSetOf(assets.first { it3 -> it3.origin == it2.source }), filterOutStandardNamespaces( it2.URIS )))}}
    }
}
class Shapes: CliktCommand() {
    private val files by argument().file().multiple()
    private val config by requireObject<GlobalConfig>()
    override fun run() {
        if(config.verbose) echo("Filter $files")
        files.forEach{ it ->
            val res = filterOutStandardNamespaces(getAllURIShape(it.path))
            assets.add(Asset(it.path, it.name, Kind.SHAPE, assets.filter { it.kind == Kind.MAPPING }.filter { it.URIs.intersect(res).isNotEmpty() }.toMutableSet(), res))
        }
    }
}
class Queries: CliktCommand() {
    private val files by argument().file().multiple()
    private val config by requireObject<GlobalConfig>()
    override fun run() {
        if(config.verbose) echo("Access $files")
        files.forEach{ it ->
            val res = getAllURIQuery(it.path)
            val deps = assets.filter { it.kind == Kind.MAPPING || it.kind == Kind.SHAPE }.filter { it.URIs.intersect(res).isNotEmpty() }
            assets.add(Asset(it.path, it.name, Kind.QUERY, deps.toMutableSet(), res)) }
    }
}
class Runners: CliktCommand() {
    private val files by argument().file().multiple()
    private val config by requireObject<GlobalConfig>()
    override fun run() {
        if(config.verbose) echo("Runs $files")
        files.forEach{
            val parser = Python3Parser(CommonTokenStream(Python3Lexer(CharStreams.fromFileName(it.path))))
            val tree = parser.file_input()

            val listener = StringLiteralListener()
            ParseTreeWalker.DEFAULT.walk(listener, tree)

            val res = listener.stringLiterals.filter { it -> assets.any { it2 -> it2.name == it ||  it2.origin == it } }.toSet()

            val deps = assets.filter { it -> it.kind == Kind.QUERY }
                             .filter { it -> res.any { it2 -> it.name == it2 ||  it.origin == it2 } }
                             .toMutableSet()

            assets.add(Asset(it.path, it.name, Kind.PYTHON, deps, res)) }
        assets.forEach{ println(it) }
    }

}
class Main : CliktCommand() {
    private val verbose by option().flag()
    private val config by findOrSetObject { GlobalConfig() }
    override val allowMultipleSubcommands = true
    override fun run() {
        config.verbose = verbose
        echo("Verbose mode is ${if (verbose) "on" else "off"}")
    }
}

fun main(args:Array<String>) = Main().subcommands(Sources(), Mappings(), Shapes(), Queries(), Runners()).main(args)

fun filterOutStandardNamespaces(uris: Set<String>): Set<String> {
    // Define standard namespace prefixes to filter out
    val standardNamespacePrefixes = setOf(
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
    )

    // Filter out URIs that start with any of the standard namespace prefixes
    return uris.filterNot { uri ->
        standardNamespacePrefixes.any { prefix ->
            uri.startsWith(prefix)
        }
    }.toSet()
}


/**
 * Extracts all URIs from a ParsedQuery by traversing its tuple expression
 * @param query The parsed SPARQL query
 * @return Set of URI strings found in the query
 */
fun extractUrisFromQuery(query: ParsedQuery): Set<String> {
    val uris = mutableSetOf<String>()
    val visitor = UriExtractorVisitor(uris)
    query.tupleExpr.visit(visitor)
    return uris
}

/**
 * Visitor implementation that extracts URIs from tuple expressions
 */
private class UriExtractorVisitor(
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

/**
 * This one uses a visitor over the AST for collection.
 * Works for simple queries, didn't check in detail, generated by Claude 3.5 Sonnet
 */
fun getAllURIQuery(fileName : String) : Set<String>{
    val pQ = SPARQLParser().parseQuery(File(fileName).readText(),"http://www.w3.org/ns/r2rml#")
    return filterOutStandardNamespaces(extractUrisFromQuery(pQ))
}

/**
 * This one runs a generic query, should be done similarly to the RML one.
 */
fun getAllURIShape(fileName: String) : Set<String>{
    val shapes = FileInputStream(fileName)
    val shaclStore = RDF4JStore()
    shaclStore.read(shapes, "http://www.w3.org/ns/r2rml#", RDFFormat.TURTLE)
    val repoShapes = SailRepository( NativeStore())
    shaclStore.model.forEach { repoShapes.connection.add(it) }


    val rs = repoShapes.connection.prepareTupleQuery("""
    PREFIX sh: <http://www.w3.org/ns/shacl#> 
    SELECT * { ?s ?p ?o }""")

    val names2 = mutableSetOf<String>()
    rs.evaluate().forEach { it.bindingNames.forEach { it2 -> if(it.getValue(it2).isIRI) names2.add((it.getValue(it2) as IRI).stringValue()) } }
    return filterOutStandardNamespaces(names2).toMutableSet()
}

data class SingleMappingDependency(val name: String, val source : String, val URIS : MutableSet<String> )
data class MappingDependencies(val internal : Map<String, String>, val total : Map<String, SingleMappingDependency>)

fun getAllURIMap(fileName: String) : MappingDependencies{
    val istr: InputStream = FileInputStream(fileName) //does not work on .yml
    val rmlStore = RDF4JStore()
    rmlStore.read(istr, "http://www.w3.org/ns/r2rml#", RDFFormat.TURTLE)
    val repo = SailRepository( NativeStore())
    rmlStore.model.forEach { repo.connection.add(it) }
    val r = repo.connection.prepareTupleQuery("""
    PREFIX rml: <http://semweb.mmlab.be/ns/rml#> 
    PREFIX rr: <http://www.w3.org/ns/r2rml#>         

    SELECT *
    {
        ?s a rr:TriplesMap;
          rr:predicateObjectMap [
            rr:objectMap ?om;
            rr:predicateMap ?pm];
          rr:subjectMap ?sm;
          rml:logicalSource [ rml:source ?source ];
          rdfs:label ?name.
        OPTIONAL{ ?sm rr:termType ?smterm. }
        OPTIONAL{ ?sm rr:template ?smtemp. }
        OPTIONAL{ ?sm rr:constant ?smconst. }
        OPTIONAL{ ?sm rr:dataType ?smdatatype. }
        OPTIONAL{ ?pm rr:termType ?pmterm. }
        OPTIONAL{ ?pm rr:template ?pmtemp. }
        OPTIONAL{ ?pm rr:constant ?pmconst. }
        OPTIONAL{ ?pm rr:dataType ?pmdatatype. }
        OPTIONAL{ ?om rr:termType ?omterm. }
        OPTIONAL{ ?om rr:template ?omtemp. }
        OPTIONAL{ ?om rr:constant ?omconst. }
        OPTIONAL{ ?om rr:dataType ?omdatatype. }
        OPTIONAL{ ?om rr:parentTriplesMap [rdfs:label ?other]. }
    }""")
    val res = r.evaluate()
    val names = mutableMapOf<String,SingleMappingDependency>()
    val deps = mutableMapOf<String,String>() //this obviously handles only one parent map, but the query must be changed anyway
    res.forEach {
        val key = it.getValue("s").stringValue()
        if(!names.containsKey(key)) names[key] = SingleMappingDependency(it.getValue("name").stringValue(),
                                                        it.getValue("source").stringValue(),
                                                        mutableSetOf())
        it.bindingNames.forEach { it2 -> if(it2 != "source" &&
                                            it2 != "name" &&
                                            it2 != "other" &&
                                            it.getValue(it2).isIRI)
                                            names[key]!!.URIS.add((it.getValue(it2) as IRI).stringValue()) }
        if(it.hasBinding("other")) deps[it.getValue("name").stringValue()] = it.getValue("other").stringValue()
    }


    return MappingDependencies(deps, names)
}

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
