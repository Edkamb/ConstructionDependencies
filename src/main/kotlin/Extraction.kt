package org.smolang.depender

import be.ugent.rml.store.RDF4JStore
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.query.parser.ParsedQuery
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.sail.nativerdf.NativeStore
import java.io.File
import java.io.FileInputStream
import java.io.InputStream


data class SingleMappingDependency(val name: String, val source : String, val URIS : MutableSet<String> )
data class MappingDependencies(val internal : Map<String, String>, val total : Map<String, SingleMappingDependency>)

/**
 * Extracts all URIs from a ParsedQuery by traversing its tuple expression
 */
fun extractUrisFromQuery(query: ParsedQuery): Set<String> {
    val uris = mutableSetOf<String>()
    val visitor = UriExtractorVisitor(uris)
    query.tupleExpr.visit(visitor)
    return uris
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
