package org.smolang.depender

import antlr.Python3Lexer
import antlr.Python3Parser
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.tree.ParseTreeWalker


/** Kind of asset **/
enum class Kind { SOURCE, PMAPPING, MAPPING, SHAPE, QUERY, PYTHON}

data class Asset(val name : String?,  //identifier
                 val origin : String, //file we read it from
                 val kind : Kind,     //kind
                 val dependsOn : MutableSet<Asset> = mutableSetOf(), //dependencies
                 val URIs : Set<String> = setOf() //for mappings, shapes and queries, the contained and filtered URIs
){
    override fun toString(): String {
        return "${kind}: $name DEPS(${dependsOn.joinToString (",") { it.name + ":" + it.kind  } })"
    }
}

// keeps track of all the assets, indexed by name
val assets = mutableMapOf<String,Asset>()
val cols = mutableMapOf<String,Set<String>>()
var ontology = ""
fun findDependencies(res : Set<String>, kinds : Set<Kind>) : MutableSet<Asset>
        = assets.values.filter { kinds.contains(it.kind) }.filter { it.URIs.intersect(res).isNotEmpty() }.toMutableSet()

/** The pipeline is done with Clikt subcommands, check their documentation for details **/
class Ontology: CliktCommand() {
    private val file by argument().file()
    private val config by requireObject<GlobalConfig>()
    override fun run() {
        if(config.verbose) echo("Ontology $file")
        ontology = file.path
    }
}

class Sources: CliktCommand() {
    private val files by argument().file().multiple()
    private val config by requireObject<GlobalConfig>()
    override fun run() {
        if(config.verbose) echo("Sources $files")
        files.forEach {
            if (assets.containsKey(it.name)) throw Exception("Asset with name ${it.name} already exists!")
            assets[it.name] = (Asset(it.name, it.path, Kind.SOURCE))
            cols[it.name] = extractCsvColumns(it.path)
            if(config.verbose) echo("Source ${it.name} has columns ${cols[it.name]}")
        }
    }
}

class Mappings: CliktCommand() {
    private val files by argument().file().multiple()
    private val config by requireObject<GlobalConfig>()
    override fun run() {
        if(config.verbose) echo("Mapping $files")
        val extra = mutableSetOf<Map<String,String>>()
        files.forEach{ file ->
            val res = getAllURIMap(file.path)
            if(config.verbose) echo("Mapping $file has mappings $res")
            res.total.values.forEach{
                dep ->
                if(assets.containsKey(dep.name)) throw Exception("Asset with name ${dep.name} already exists!")
                assets[dep.name] = (Asset(dep.name, file.path, Kind.MAPPING,
                    mutableSetOf(assets[dep.source]!!), filterOutStandardNamespaces( dep.URIS )))}
            extra += res.internal
        }
        extra.forEach { it.entries.forEach{ it2 -> assets[it2.key]!!.dependsOn.add(assets[it2.value]!!) } }
    }
}

class PMappings: CliktCommand() {
    private val files by argument().file().multiple()
    private val config by requireObject<GlobalConfig>()
    override fun run() {
        files.forEach { file ->
            if(config.verbose) echo("Python mapping $file")
            val results = PythonFileAnalyzer().analyzeFile(file.path)
            results.forEach { res ->
                val dependsOn = assets.values.filter { it.kind == Kind.SOURCE && cols[it.name]!!.intersect(res.columnsAccessed).isNotEmpty()}.toMutableSet()
                val internalUris = filterOutStandardNamespaces(findUrisWithSuffixes(ontology, res.classesCreated.toList() + res.classFields.toList()))
                assets[res.methodName] = (Asset(res.methodName, file.path, Kind.PMAPPING, dependsOn, internalUris))
            }
        }
    }
}

class Shapes: CliktCommand() {
    private val files by argument().file().multiple()
    private val config by requireObject<GlobalConfig>()
    override fun run() {
        if(config.verbose) echo("Filter $files")
        files.forEach{ file ->
            if(assets.containsKey(file.name)) throw Exception("Asset with name ${file.name} already exists!")
            val res = filterOutStandardNamespaces(getAllURIShape(file.path))

            if(config.verbose) echo("Filter $file has $res")
            assets[file.name] = (Asset(file.name, file.path, Kind.SHAPE, findDependencies(res, setOf(Kind.MAPPING, Kind.PMAPPING)), res))
        }
    }
}


class Queries: CliktCommand() {
    private val files by argument().file().multiple()
    private val config by requireObject<GlobalConfig>()
    override fun run() {
        if(config.verbose) echo("Access $files")
        files.forEach{ file ->
            if(assets.containsKey(file.name)) throw Exception("Asset with name ${file.name} already exists!")
            val res = getAllURIQuery(file.path)
            val deps = findDependencies(res, setOf(Kind.MAPPING, Kind.SHAPE, Kind.PMAPPING))
            assets[file.name] = (Asset(file.name, file.path, Kind.QUERY, deps, res)) }
    }
}
class Runners: CliktCommand() {
    private val files by argument().file().multiple()
    private val config by requireObject<GlobalConfig>()
    override fun run() {
        if(config.verbose) echo("Runs $files")
        files.forEach{ file ->
            if(assets.containsKey(file.name)) throw Exception("Asset with name ${file.name} already exists!")
            val parser = Python3Parser(CommonTokenStream(Python3Lexer(CharStreams.fromFileName(file.path))))
            val tree = parser.file_input()

            val listener = StringLiteralListener()
            ParseTreeWalker.DEFAULT.walk(listener, tree)

            val res = listener.stringLiterals.filter { lit -> assets.keys.any { name -> name == lit } }.toSet()

            val deps = assets.values.filter { it.kind == Kind.QUERY }
                                    .filter { res.any { it2 -> it.name == it2 ||  it.origin == it2 } }
                                    .toMutableSet()

            assets[file.name] = (Asset(file.name, file.path, Kind.PYTHON, deps, res)) }
        assets.values.forEach{ println(it) }
    }

}
class Main : CliktCommand() {
    private val verbose by option().flag()
    private val config by findOrSetObject { GlobalConfig() }
    private val exclude by option().multiple()
    override val allowMultipleSubcommands = true
    override fun run() {
        config.verbose = verbose
        for( exc in exclude ){
            echo("Excluding prefix $exc")
            standardNamespacePrefixes += exc
        }
        echo("Verbose mode is ${if (verbose) "on" else "off"}")
    }
}

fun main(args:Array<String>) = Main().subcommands(Ontology(), Sources(), Mappings(), PMappings(), Shapes(), Queries(), Runners()).main(args)
