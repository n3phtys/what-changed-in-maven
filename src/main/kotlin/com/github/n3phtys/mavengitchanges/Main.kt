package com.github.n3phtys.mavengitchanges

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.*
import java.io.File


class Hello : CliktCommand() {

    private val parentPomFile: File by option(help = "top level pom").file(exists = true, readable = true).required()
    private val comparedToCommit: String? by option(help = "commit hash or tag to which to compare (the older release)")
    private val includeDependents: Boolean by option(help = "should dependent modules also be included in output? often not recommandable").flag()
    private val includeDependencies: Boolean by option(help = "should dependency modules also be included in output? often recommandable").flag()
    private val useCheckout: Boolean by option(help = "should git checkout be used for exact information?").flag()
    private val currentCommit: String? by option(help = "commit hash or tag from which to compare (the newer / current release)")
    private val timeExecution: Boolean by option(help = "print timer for each step").flag()

    override fun run() {
        App(
            parentPomFile,
            comparedToCommit,
            currentCommit,
            includeDependents,
            includeDependencies,
            useCheckout,
            timeExecution
        ).run().forEach { println(it) }
    }
}

fun main(args: Array<String>) = Hello().main(args)
