import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.*
import java.io.File


class Hello : CliktCommand() {

    val parentPomFile: File by option(help="top level pom").file(exists = true, readable = true).required()
    val comparedToCommit: String? by option(help="commit hash or tag to which to compare")
    val includeDependents: Boolean by option(help="should dependent modules also be included in output? often recommandable").flag()


    override fun run() {
        println("File Location: ${parentPomFile.absolutePath}   with commit: ${comparedToCommit?:"N/A"}")
        App(parentPomFile, comparedToCommit, includeDependents).run().forEach { println(it) }
    }
}

fun main(args: Array<String>) = Hello().main(args)
