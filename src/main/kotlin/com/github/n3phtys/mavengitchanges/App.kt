package com.github.n3phtys.mavengitchanges

import org.apache.maven.model.Dependency
import org.apache.maven.model.Model
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.lang.UnsupportedOperationException
import java.util.*
import java.util.concurrent.TimeUnit


class App(
    val rootPomXml: File,
    val compareCommit: String?,
    val currentCommit: String?,
    val includeDependents: Boolean,
    val includeDependencies: Boolean,
    val useCheckout: Boolean,
    val timeExecution: Boolean
) {
    val rootDir = rootPomXml.parentFile

    init {
        if (!rootDir.isAGitRepo()) {
            throw UnsupportedOperationException("the given directory ${rootDir.absolutePath} is not part of a git repository")
        }
    }


    fun run(): List<String> {
        val timer = PrintTimer(timeExecution)

        val current = rootDir.getHeadCommitHash()
        val newRelease = currentCommit ?: "HEAD"
        val oldRelease = compareCommit ?: "--"

        //check out old code if requested
        timer.print("setup")
        if (useCheckout && compareCommit != null) {
            rootDir.checkoutCommit(oldRelease)
            timer.print("checkout of old commit")
        }
        val oldSet = if (compareCommit != null) {
            loadModules(rootDir, compareCommit)
        } else {
            setOf()
        }
        timer.print("computing modules of old commit")

        //check out new code if requested
        if (useCheckout && currentCommit != null) {
            rootDir.checkoutCommit(newRelease)
            timer.print("checkout of new commit")
        }

        //retrieve current pom.xml with timestamps of each module
        //remove all pom.xmls that are not actual artifacts
        val allModules: Set<Module> = loadModules(rootDir, newRelease) //TODO: does this actually work correctly?
        timer.print("computing modules of new commit")

        //compare with old list (if one is set), remove all exact duplicates
        val modules = allModules.filter { !oldSet.contains(it) }.toSet()
        timer.print("cross-intersecting result sets")

        //to each pom.xml, find dependents, also include them if requested
        val output: Set<Module> = if (includeDependencies) {
            val allPoms = findAllPoms(rootDir)
            timer.print("finding poms")
            val foundModules = mutableSetOf<Module>()
            val queue = ArrayDeque<Module>()
            queue.addAll(modules)
            while (queue.isNotEmpty()) {
                val mod = queue.poll()
                if (!foundModules.contains(mod)) {
                    foundModules.add(mod)
                    val dependents: Set<Module> = findDependencies(allPoms, mod)
                    queue.addAll(dependents)
                }
            }
            timer.print("dependences queue completion")
            foundModules
        } else if (includeDependents) {
            val allPoms = findAllPoms(rootDir)
            timer.print("finding poms")
            val foundModules = mutableSetOf<Module>()
            val queue = ArrayDeque<Module>()
            queue.addAll(modules)
            while (queue.isNotEmpty()) {
                val mod = queue.poll()
                if (!foundModules.contains(mod)) {
                    foundModules.add(mod)
                    val dependents: Set<Module> = findDependents(allPoms, mod)
                    queue.addAll(dependents)
                }
            }
            timer.print("dependents queue completion")
            foundModules
        } else {
            modules
        }


        //back to original commit
        if (useCheckout) {
            rootDir.checkoutCommit(current)
            timer.print("checkout of previous commit")
        }

        val finalResult =
            output.filter { it.model?.artifactId?.isNotBlank() == true }.map { it.model.transformToIdString() }
        timer.print("computation of final result")
        return finalResult
    }

    private fun findDependencies(allPoms: Map<File, Model>, mod: Module): Set<Module> {
        return mod.model.dependencies.flatMap { dependency ->
            allPoms.entries.filter { it.value.transformToIdString() == dependency.transformDependencyToString() }
        }.map { buildModuleFromFile(it.key, it.value, mod) }.toSet()
    }

    private fun buildModuleFromFile(file: File, model: Model, depndent: Module): Module {
        return Module(file, computeSingleFileStamp(file))
    }

    private fun computeSingleFileStamp(file: File): String {
        val key = file.absolutePath.substring(rootDir.absolutePath.length + 1)
        val ts =
            "git log -1 --format=\"%ad\" -- $key".runCmdInPwd(rootDir)!!.trim().replace("\"", "")
        return ts
    }

    private fun findDependents(allPoms: Map<File, Model>, module: Module): Set<Module> {
        return allPoms.entries.filter {
            it.value.dependencies.map { it.transformDependencyToString() }.contains(module.moduleId())
        }.map { tryParseModule(it.key) }.toSet()
    }

    private fun tryParseModule(pom: File): Module {
        TODO("not yet implemented")
    }


    private fun loadModules(rootDir: File, compareCommit: String): Set<Module> {
        val listDirsInGeneral = "git ls-tree -r -d --name-only $compareCommit".runCmdInPwd(rootDir)!!.trim().lines()
        val modifiedAt = listDirsInGeneral.map {
            val op = "git log -1 --format=\"%ad\" $compareCommit $it".runCmdInPwd(rootDir)!!
            Pair(it, op.trim().replace("\"", ""))
        }.toMap()
        return modifiedAt.keys.filter { key -> rootDir.resolve(key).resolve("pom.xml").canRead() }
            .map { buildModule(it, modifiedAt[it] ?: error("INVALID REF $it")) }.toSet()
    }

    private fun buildModule(key: String, timeStamp: String): Module {
        val pom = rootDir.resolve(key).resolve("pom.xml")
        return Module(pom, timeStamp)
    }

    private fun findAllPoms(rootDir: File): Map<File, Model> {
        val output = mutableMapOf<File, Model>()
        val cmd = "git ls-tree -r -d --name-only HEAD"
        output[rootPomXml] = rootPomXml.parsePOM()
        val stout = cmd.runCmdInPwd(rootDir)!!.lines()
        stout.map { "$it/pom.xml" }.map { rootDir.resolve(it.trim()) }
            .filter { it.exists() && it.canRead() }.forEach {
                val model = it.parsePOM()
                if (model.artifactId?.isNotBlank() == true) {
                    output[it] = model
                }
            }
        return output
    }
}

class PrintTimer(private val actuallyPrint: Boolean, val name: String = "basic") {
    private val startOfLifecycleNanos = System.nanoTime()
    private var startOfIntervalNanos = startOfLifecycleNanos

    fun print(stepname: String? = null) {
        val calledAtNano = System.nanoTime()
        val msRound = TimeUnit.NANOSECONDS.toMillis(calledAtNano - startOfIntervalNanos)
        val msTotal = TimeUnit.NANOSECONDS.toMillis(calledAtNano - startOfLifecycleNanos)
        startOfIntervalNanos = calledAtNano
        if (actuallyPrint) {
            val step = if (stepname != null) "'$stepname'" else "Previous step"
            println("Timer $name - $step took $msRound ms, with total time lapsed of $msTotal ms.")
        }
    }
}


data class Module(val location: File, val timeStamp: String) {
    val model = MavenXpp3Reader().read(FileReader(location))
    fun moduleId() = model.transformToIdString()
}

fun File.getHeadCommitHash(): String {
    val cmd = "git rev-parse HEAD"
    val output = cmd.runCmdInPwd(this)!!.trim()
    return output
}

fun File.checkoutCommit(hashOrTag: String) {
    val cmd = "git checkout $hashOrTag"
    val output = cmd.runCmdInPwd(this)
}

fun File.isAGitRepo(): Boolean {
    val res = "git rev-parse --is-inside-work-tree".runCmdInPwd(this)
    return res!!.trim().equals(true.toString())
}


fun File.parsePOM(): Model {
    val reader = MavenXpp3Reader()
    val model: Model = reader.read(FileReader(this))
    return model
}

private fun Model.transformToIdString(): String {
    return this.groupId ?: (this.parent.groupId) + if (this.artifactId != null) {
        "::" + this.artifactId
    } else ""
    /*+ "::" + this.version ?: (this.parent.version)*/ //TODO: fix version == null case
}

private fun Dependency.transformDependencyToString(): String {
    return this.groupId + if (this.artifactId != null) {
        "::" + this.artifactId
    } else "" /*+ "::" + this.version*/ //TODO: fix version == null case
}


fun String.runCmdInPwd(workingDir: File): String? {
    try {
        val parts = this.split("\\s".toRegex())
        val process = ProcessBuilder(*parts.toTypedArray())
            .directory(workingDir)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        process.waitFor(300, TimeUnit.SECONDS)
        return process.inputStream.bufferedReader().readText()
    } catch (e: IOException) {
        e.printStackTrace()
        return null
    }
}